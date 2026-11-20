package com.nativelib.restudio.core.elf

import com.nativelib.restudio.core.common.BinaryReader
import com.nativelib.restudio.core.common.ByteSource
import com.nativelib.restudio.core.common.MappedFileSource
import com.nativelib.restudio.core.common.OutOfBoundsException
import java.io.File
import java.nio.ByteOrder

/**
 * Defensive ELF32/ELF64 parser (little and big endian).
 *
 * Design rules:
 *  - Every offset/size read from the file is validated against the file size before use.
 *  - Problems in optional structures produce [ElfWarning]s instead of failing the whole parse.
 *  - Only a broken identification/header causes [ElfFormatException].
 *  - Nothing is ever executed or loaded; this is pure byte inspection.
 */
class ElfParser(private val limits: Limits = Limits()) {

    data class Limits(
        val maxSymbolsPerTable: Int = 2_000_000,
        val maxRelocations: Int = 2_000_000,
        val maxStringTableBytes: Long = 256L * 1024 * 1024,
        val maxNotes: Int = 1024,
    )

    fun parse(file: File, checkCancelled: () -> Unit = {}): ElfFile =
        MappedFileSource(file).use { parse(it, checkCancelled) }

    fun parse(source: ByteSource, checkCancelled: () -> Unit = {}): ElfFile =
        try {
            Session(source, checkCancelled).run()
        } catch (e: OutOfBoundsException) {
            throw ElfFormatException("Truncated or malformed ELF: ${e.message}")
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Last-resort containment for malformed input; fuzzing found none, but never crash the app.
            throw ElfFormatException("Malformed ELF: ${e.message}")
        } catch (e: ArithmeticException) {
            throw ElfFormatException("Malformed ELF: ${e.message}")
        }

    private inner class Session(val src: ByteSource, val checkCancelled: () -> Unit) {
        val warnings = mutableListOf<ElfWarning>()
        val r = BinaryReader(src)
        var is64 = false

        fun warn(msg: String) { warnings += ElfWarning(WarningSeverity.WARNING, msg) }
        fun info(msg: String) { warnings += ElfWarning(WarningSeverity.INFO, msg) }
        fun error(msg: String) { warnings += ElfWarning(WarningSeverity.ERROR, msg) }

        fun run(): ElfFile {
            val header = readHeader()
            checkCancelled()
            val phdrs = readProgramHeaders(header)
            val sections = readSections(header)
            checkCancelled()
            val loads = phdrs.filter { it.type == ElfConstants.PT_LOAD }

            // ---- dynamic section ----
            val dynSection = sections.firstOrNull { it.type == ElfConstants.SHT_DYNAMIC }
            val dynSegment = phdrs.firstOrNull { it.type == ElfConstants.PT_DYNAMIC }
            val dynRaw = readDynamicRaw(dynSection, dynSegment)
            fun dynVal(tag: Long) = dynRaw.firstOrNull { it.first == tag }?.second

            // dynamic string table: prefer section link, fall back to DT_STRTAB/DT_STRSZ mapped through PT_LOAD
            val dynStr: ByteArray? = run {
                val linked = dynSection?.let { sections.getOrNull(it.link.toInt()) }?.takeIf { it.type == ElfConstants.SHT_STRTAB }
                    ?: sections.firstOrNull { it.name == ".dynstr" && it.type == ElfConstants.SHT_STRTAB }
                if (linked != null) loadSection(linked)
                else {
                    val addr = dynVal(ElfConstants.DT_STRTAB); val sz = dynVal(ElfConstants.DT_STRSZ)
                    if (addr != null && sz != null) vaddrToOffset(loads, addr)?.let { off -> loadRange(off, sz, "DT_STRTAB") } else null
                }
            }
            val dynamic = dynRaw.map { (tag, v) ->
                DynamicEntry(tag, v, if (ElfConstants.dynamicTagIsString(tag) && dynStr != null) strAt(dynStr, v) else null)
            }
            if (dynRaw.isNotEmpty() && dynStr == null) warn("Dynamic string table could not be located; library names and SONAME are unavailable.")

            // ---- symbol versioning ----
            val (verDefNames, verDefList) = readVerDef(sections, dynStr)
            val (verNeedNames, verNeeds) = readVerNeed(sections, dynStr)
            val versionNames = HashMap<Int, String>().apply { putAll(verDefNames); putAll(verNeedNames) }

            // ---- symbols ----
            val dynsymSec = sections.firstOrNull { it.type == ElfConstants.SHT_DYNSYM }
            val versym = sections.firstOrNull { it.type == ElfConstants.SHT_GNU_VERSYM }?.let { loadSection(it) }
            val dynSyms: List<ElfSymbol> = when {
                dynsymSec != null -> readSymbolTable(dynsymSec, sections, SymbolTableKind.DYNSYM, versym, versionNames)
                dynVal(ElfConstants.DT_SYMTAB) != null && dynStr != null -> readDynSymsFromDynamic(loads, dynRaw, dynStr)
                else -> emptyList()
            }
            checkCancelled()
            val symtabSec = sections.firstOrNull { it.type == ElfConstants.SHT_SYMTAB }
            val staticSyms = symtabSec?.let { readSymbolTable(it, sections, SymbolTableKind.SYMTAB, null, emptyMap()) } ?: emptyList()
            checkCancelled()

            // ---- relocations ----
            val relocs = readRelocations(header, sections, dynSyms, staticSyms)
            checkCancelled()

            // ---- notes ----
            val notes = readNotes(sections, phdrs)

            val security = computeSecurity(header, phdrs, dynRaw)
            validateLayout(header, phdrs, sections)

            return ElfFile(
                fileSize = src.size, header = header, programHeaders = phdrs, sections = sections, dynamic = dynamic,
                dynamicSymbols = dynSyms, staticSymbols = staticSyms, relocations = relocs, notes = notes,
                versionNeeds = verNeeds, versionDefinitions = verDefList, security = security, warnings = warnings.toList(),
            )
        }

        // ------------------------------------------------------------------ header
        fun readHeader(): ElfHeader {
            if (src.size < 16) throw ElfFormatException("File too small to be ELF (${src.size} bytes)")
            val id = src.readBytes(0, 16)
            if (id[0] != 0x7f.toByte() || id[1] != 'E'.code.toByte() || id[2] != 'L'.code.toByte() || id[3] != 'F'.code.toByte())
                throw ElfFormatException("Not an ELF file (bad magic)")
            val cls = when (id[4].toInt()) { 1 -> ElfClass.ELF32; 2 -> ElfClass.ELF64; else -> throw ElfFormatException("Invalid ELF class ${id[4]}") }
            val endian = when (id[5].toInt()) { 1 -> Endianness.LITTLE; 2 -> Endianness.BIG; else -> throw ElfFormatException("Invalid ELF data encoding ${id[5]}") }
            is64 = cls == ElfClass.ELF64
            r.order = if (endian == Endianness.LITTLE) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val ident = ElfIdent(cls, endian, id[6].toInt() and 0xFF, id[7].toInt() and 0xFF, id[8].toInt() and 0xFF)
            if (ident.version != 1) warn("EI_VERSION is ${ident.version}, expected 1")

            val minHeader = if (is64) 64L else 52L
            if (src.size < minHeader) throw ElfFormatException("Truncated ELF header (${src.size} < $minHeader bytes)")
            val type = r.u16(16); val machine = r.u16(18); val version = r.u32(20)
            val entry: Long; val phoff: Long; val shoff: Long; val flags: Long; val base: Long
            if (is64) { entry = r.u64(24); phoff = r.u64(32); shoff = r.u64(40); flags = r.u32(48); base = 52 }
            else { entry = r.u32(24); phoff = r.u32(28); shoff = r.u32(32); flags = r.u32(36); base = 40 }
            val ehsize = r.u16(base); val phentsize = r.u16(base + 2); var phnum = r.u16(base + 4)
            val shentsize = r.u16(base + 6); var shnum = r.u16(base + 8); var shstrndx = r.u16(base + 10)

            if (version != 1L) warn("e_version is $version, expected 1")
            if (ehsize.toLong() != minHeader) warn("e_ehsize is $ehsize, expected $minHeader")
            val expectedMachine64 = when (machine) { ElfConstants.EM_AARCH64, ElfConstants.EM_X86_64 -> true; ElfConstants.EM_ARM, ElfConstants.EM_386 -> false; else -> null }
            if (expectedMachine64 != null && expectedMachine64 != is64) warn("Machine ${ElfConstants.machineName(machine)} does not match ELF class ${cls.bits}-bit")

            // Extended numbering: real counts live in section header 0.
            val shdrSize = if (is64) 64 else 40
            if (shoff != 0L && shentsize >= shdrSize && src.contains(shoff, shdrSize.toLong())) {
                val s0Size = if (is64) r.u64(shoff + 32) else r.u32(shoff + 20)
                val s0Link = r.u32(shoff + if (is64) 40 else 24)
                val s0Info = r.u32(shoff + if (is64) 44 else 28)
                if (shnum == 0 && s0Size != 0L) { shnum = s0Size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); info("Extended section numbering: e_shnum stored in section 0 ($shnum)") }
                if (shstrndx == ElfConstants.SHN_XINDEX) { shstrndx = s0Link.toInt(); info("Extended section numbering: e_shstrndx stored in section 0") }
                if (phnum == ElfConstants.PN_XNUM && s0Info != 0L) { phnum = s0Info.toInt(); info("Extended program header numbering in use ($phnum)") }
            }
            return ElfHeader(ident, type, machine, version, entry, phoff, shoff, flags, ehsize, phentsize, phnum, shentsize, shnum, shstrndx)
        }

        // ------------------------------------------------------------------ program headers
        fun readProgramHeaders(h: ElfHeader): List<ProgramHeader> {
            if (h.phNum == 0 || h.phOff == 0L) return emptyList()
            val need = if (is64) 56 else 32
            if (h.phEntSize < need) { error("e_phentsize ${h.phEntSize} is smaller than required $need; program headers ignored"); return emptyList() }
            val total = h.phEntSize.toLong() * h.phNum
            if (!src.contains(h.phOff, total)) { error("Program header table (offset ${h.phOff}, ${h.phNum} entries) extends beyond end of file"); return emptyList() }
            return (0 until h.phNum).map { i ->
                val o = h.phOff + i.toLong() * h.phEntSize
                if (is64) ProgramHeader(i, r.u32(o), r.u32(o + 4), r.u64(o + 8), r.u64(o + 16), r.u64(o + 24), r.u64(o + 32), r.u64(o + 40), r.u64(o + 48))
                else ProgramHeader(i, r.u32(o), r.u32(o + 24), r.u32(o + 4), r.u32(o + 8), r.u32(o + 12), r.u32(o + 16), r.u32(o + 20), r.u32(o + 28))
            }
        }

        // ------------------------------------------------------------------ section headers
        fun readSections(h: ElfHeader): List<SectionHeader> {
            if (h.shNum == 0 || h.shOff == 0L) {
                info("No section header table (section headers stripped). Analysis relies on program headers and the dynamic segment.")
                return emptyList()
            }
            val need = if (is64) 64 else 40
            if (h.shEntSize < need) { error("e_shentsize ${h.shEntSize} is smaller than required $need; sections ignored"); return emptyList() }
            val total = h.shEntSize.toLong() * h.shNum
            if (!src.contains(h.shOff, total)) { error("Section header table (offset ${h.shOff}, ${h.shNum} entries) extends beyond end of file"); return emptyList() }

            data class Raw(val name: Long, val type: Long, val flags: Long, val addr: Long, val off: Long, val size: Long, val link: Long, val info: Long, val align: Long, val ent: Long)
            val raws = (0 until h.shNum).map { i ->
                if (i % 4096 == 0) checkCancelled()
                val o = h.shOff + i.toLong() * h.shEntSize
                if (is64) Raw(r.u32(o), r.u32(o + 4), r.u64(o + 8), r.u64(o + 16), r.u64(o + 24), r.u64(o + 32), r.u32(o + 40), r.u32(o + 44), r.u64(o + 48), r.u64(o + 56))
                else Raw(r.u32(o), r.u32(o + 4), r.u32(o + 8), r.u32(o + 12), r.u32(o + 16), r.u32(o + 20), r.u32(o + 24), r.u32(o + 28), r.u32(o + 32), r.u32(o + 36))
            }
            val strRaw = raws.getOrNull(h.shStrNdx)
            val names: ByteArray? = when {
                h.shStrNdx == ElfConstants.SHN_UNDEF -> { warn("No section name string table (e_shstrndx = 0)"); null }
                strRaw == null -> { error("e_shstrndx ${h.shStrNdx} is out of range (${h.shNum} sections)"); null }
                else -> loadRange(strRaw.off, strRaw.size, "section name table")
            }
            return raws.mapIndexed { i, s ->
                SectionHeader(i, names?.let { strAt(it, s.name) } ?: "", s.name, s.type, s.flags, s.addr, s.off, s.size, s.link, s.info, s.align, s.ent)
            }
        }

        // ------------------------------------------------------------------ helpers
        fun strAt(table: ByteArray, off: Long): String? {
            if (off < 0 || off >= table.size) return null
            var end = off.toInt()
            while (end < table.size && table[end] != 0.toByte()) end++
            return String(table, off.toInt(), end - off.toInt(), Charsets.UTF_8)
        }

        fun loadRange(off: Long, size: Long, what: String): ByteArray? {
            if (size == 0L) return ByteArray(0)
            if (size > limits.maxStringTableBytes) { warn("$what is too large ($size bytes); skipped"); return null }
            if (!src.contains(off, size)) { error("$what (offset $off, size $size) extends beyond end of file"); return null }
            return src.readBytes(off, size.toInt())
        }

        fun loadSection(s: SectionHeader): ByteArray? =
            if (s.type == ElfConstants.SHT_NOBITS) null else loadRange(s.offset, s.size, "Section '${s.name}'")

        fun vaddrToOffset(loads: List<ProgramHeader>, vaddr: Long): Long? {
            for (p in loads) {
                if (vaddr >= p.vaddr && vaddr - p.vaddr < p.fileSize) return p.offset + (vaddr - p.vaddr)
            }
            return null
        }

        // ------------------------------------------------------------------ dynamic
        fun readDynamicRaw(sec: SectionHeader?, seg: ProgramHeader?): List<Pair<Long, Long>> {
            val (off, size) = when {
                sec != null && sec.type != ElfConstants.SHT_NOBITS -> sec.offset to sec.size
                seg != null -> seg.offset to seg.fileSize
                else -> return emptyList()
            }
            if (!src.contains(off, size)) { error("Dynamic section extends beyond end of file"); return emptyList() }
            val ent = if (is64) 16 else 8
            val out = ArrayList<Pair<Long, Long>>()
            var o = off
            while (o + ent <= off + size) {
                val tag = r.word(o, is64); val v = r.word(o + ent / 2, is64)
                if (tag == ElfConstants.DT_NULL) break
                out += tag to v
                o += ent
            }
            return out
        }

        // ------------------------------------------------------------------ versions
        fun readVerDef(sections: List<SectionHeader>, dynStr: ByteArray?): Pair<Map<Int, String>, List<String>> {
            val sec = sections.firstOrNull { it.type == ElfConstants.SHT_GNU_VERDEF } ?: return emptyMap<Int, String>() to emptyList()
            val strs = sections.getOrNull(sec.link.toInt())?.let { loadSection(it) } ?: dynStr ?: return emptyMap<Int, String>() to emptyList()
            if (!src.contains(sec.offset, sec.size)) { error("Version definition section extends beyond end of file"); return emptyMap<Int, String>() to emptyList() }
            val map = HashMap<Int, String>(); val list = ArrayList<String>()
            var o = sec.offset; val end = sec.offset + sec.size
            val count = if (sec.info > 0) sec.info.toInt() else 4096
            for (i in 0 until minOf(count, 4096)) {
                if (o + 20 > end) break
                val ndx = r.u16(o + 4); val aux = r.u32(o + 12); val next = r.u32(o + 16)
                if (o + aux + 8 <= end) strAt(strs, r.u32(o + aux))?.let { map[ndx] = it; list += it }
                if (next == 0L) break
                o += next
            }
            return map to list
        }

        fun readVerNeed(sections: List<SectionHeader>, dynStr: ByteArray?): Pair<Map<Int, String>, List<VersionNeed>> {
            val sec = sections.firstOrNull { it.type == ElfConstants.SHT_GNU_VERNEED } ?: return emptyMap<Int, String>() to emptyList()
            val strs = sections.getOrNull(sec.link.toInt())?.let { loadSection(it) } ?: dynStr ?: return emptyMap<Int, String>() to emptyList()
            if (!src.contains(sec.offset, sec.size)) { error("Version requirement section extends beyond end of file"); return emptyMap<Int, String>() to emptyList() }
            val map = HashMap<Int, String>(); val needs = ArrayList<VersionNeed>()
            var o = sec.offset; val end = sec.offset + sec.size
            val count = if (sec.info > 0) sec.info.toInt() else 4096
            for (i in 0 until minOf(count, 4096)) {
                if (o + 16 > end) break
                val cnt = r.u16(o + 2); val file = strAt(strs, r.u32(o + 4)) ?: "?"
                val aux = r.u32(o + 8); val next = r.u32(o + 12)
                val versions = ArrayList<String>()
                var a = o + aux
                for (j in 0 until minOf(cnt, 4096)) {
                    if (a + 16 > end) break
                    val other = r.u16(a + 6); val name = strAt(strs, r.u32(a + 8))
                    if (name != null) { map[other] = name; versions += name }
                    val an = r.u32(a + 12); if (an == 0L) break; a += an
                }
                needs += VersionNeed(file, versions)
                if (next == 0L) break
                o += next
            }
            return map to needs
        }

        // ------------------------------------------------------------------ symbols
        fun readSymbolTable(
            sec: SectionHeader, sections: List<SectionHeader>, kind: SymbolTableKind,
            versym: ByteArray?, versionNames: Map<Int, String>,
        ): List<ElfSymbol> {
            val ent = if (is64) 24L else 16L
            val entSize = if (sec.entSize >= ent) sec.entSize else ent
            if (sec.entSize != 0L && sec.entSize < ent) warn("${sec.name}: sh_entsize ${sec.entSize} is smaller than $ent; using $ent")
            if (!src.contains(sec.offset, sec.size)) { error("${sec.name} extends beyond end of file; symbols skipped"); return emptyList() }
            val strSec = sections.getOrNull(sec.link.toInt())
            val strs = strSec?.takeIf { it.type == ElfConstants.SHT_STRTAB }?.let { loadSection(it) }
            if (strs == null) warn("${sec.name}: linked string table missing; symbol names unavailable")
            var count = sec.size / entSize
            if (count > limits.maxSymbolsPerTable) { warn("${sec.name}: $count symbols exceeds limit; truncated to ${limits.maxSymbolsPerTable}"); count = limits.maxSymbolsPerTable.toLong() }
            return readSymbolsAt(sec.offset, entSize, count.toInt(), strs, kind, versym, versionNames)
        }

        fun readSymbolsAt(
            base: Long, entSize: Long, count: Int, strs: ByteArray?, kind: SymbolTableKind,
            versym: ByteArray?, versionNames: Map<Int, String>,
        ): List<ElfSymbol> {
            val minEnt = if (is64) 24L else 16L
            if (entSize < minEnt || entSize > 1024 || count < 0) { error("Invalid symbol entry size $entSize; symbols skipped"); return emptyList() }
            val total = entSize * count
            if (total > Int.MAX_VALUE || !src.contains(base, total)) { error("Symbol table (offset $base, $count entries) extends beyond end of file"); return emptyList() }
            val bytes = src.readBytes(base, total.toInt())
            val sr = BinaryReader(com.nativelib.restudio.core.common.ByteArraySource(bytes), r.order)
            val vr = versym?.let { BinaryReader(com.nativelib.restudio.core.common.ByteArraySource(it), r.order) }
            val out = ArrayList<ElfSymbol>(count)
            for (i in 0 until count) {
                if (i % 65536 == 0) checkCancelled()
                val o = i * entSize
                val nameOff: Long; val value: Long; val size: Long; val info: Int; val other: Int; val shndx: Int
                if (is64) { nameOff = sr.u32(o); info = sr.u8(o + 4); other = sr.u8(o + 5); shndx = sr.u16(o + 6); value = sr.u64(o + 8); size = sr.u64(o + 16) }
                else { nameOff = sr.u32(o); value = sr.u32(o + 4); size = sr.u32(o + 8); info = sr.u8(o + 12); other = sr.u8(o + 13); shndx = sr.u16(o + 14) }
                var version: String? = null; var hidden = false
                if (vr != null && (i * 2L + 2) <= versym.size) {
                    val v = vr.u16(i * 2L)
                    val idx = v and 0x7fff
                    hidden = v and 0x8000 != 0
                    if (idx >= 2) version = versionNames[idx]
                }
                out += ElfSymbol(i, kind, strs?.let { strAt(it, nameOff) } ?: "", value, size, info ushr 4, info and 0xf, other and 0x3, shndx, version, hidden)
            }
            return out
        }

        /** Recovers .dynsym when section headers are absent, using DT_SYMTAB + symbol count from DT_HASH/DT_GNU_HASH. */
        fun readDynSymsFromDynamic(loads: List<ProgramHeader>, dyn: List<Pair<Long, Long>>, dynStr: ByteArray): List<ElfSymbol> {
            fun v(tag: Long) = dyn.firstOrNull { it.first == tag }?.second
            val symOff = vaddrToOffset(loads, v(ElfConstants.DT_SYMTAB)!!) ?: return emptyList()
            val ent = v(ElfConstants.DT_SYMENT)?.takeIf { it > 0 } ?: if (is64) 24L else 16L
            var count: Long? = null
            v(ElfConstants.DT_HASH)?.let { vaddrToOffset(loads, it) }?.let { off -> if (src.contains(off, 8)) count = r.u32(off + 4) }
            if (count == null) v(ElfConstants.DT_GNU_HASH)?.let { vaddrToOffset(loads, it) }?.let { count = gnuHashSymbolCount(it) }
            val c = count ?: run { warn("Cannot determine dynamic symbol count without section headers or hash tables"); return emptyList() }
            val safe = minOf(c, (src.size - symOff) / ent, limits.maxSymbolsPerTable.toLong()).toInt()
            info("Dynamic symbols recovered from the dynamic segment ($safe entries) because section headers are missing")
            val versym = v(ElfConstants.DT_VERSYM)?.let { vaddrToOffset(loads, it) }?.let { if (src.contains(it, safe * 2L)) src.readBytes(it, safe * 2) else null }
            return readSymbolsAt(symOff, ent, safe, dynStr, SymbolTableKind.DYNSYM, versym, emptyMap())
        }

        fun gnuHashSymbolCount(off: Long): Long? {
            if (!src.contains(off, 16)) return null
            val nbuckets = r.u32(off); val symoffset = r.u32(off + 4); val bloomSize = r.u32(off + 8)
            val wordSize = if (is64) 8 else 4
            val bucketsOff = off + 16 + bloomSize * wordSize
            if (nbuckets > 1_000_000 || !src.contains(bucketsOff, nbuckets * 4)) return null
            var maxIdx = 0L
            for (i in 0 until nbuckets) maxIdx = maxOf(maxIdx, r.u32(bucketsOff + i * 4))
            if (maxIdx < symoffset) return symoffset
            val chainsOff = bucketsOff + nbuckets * 4
            var idx = maxIdx
            while (src.contains(chainsOff + (idx - symoffset) * 4, 4)) {
                if (r.u32(chainsOff + (idx - symoffset) * 4) and 1L != 0L) return idx + 1
                idx++
                if (idx - maxIdx > limits.maxSymbolsPerTable) return null
            }
            return null
        }

        // ------------------------------------------------------------------ relocations
        fun readRelocations(h: ElfHeader, sections: List<SectionHeader>, dynSyms: List<ElfSymbol>, staticSyms: List<ElfSymbol>): List<Relocation> {
            val out = ArrayList<Relocation>()
            for (s in sections) {
                if (out.size >= limits.maxRelocations) { warn("Relocation limit reached; remaining relocations not listed"); break }
                when (s.type) {
                    ElfConstants.SHT_REL, ElfConstants.SHT_RELA -> {
                        val isRela = s.type == ElfConstants.SHT_RELA
                        val ent = if (is64) (if (isRela) 24L else 16L) else (if (isRela) 12L else 8L)
                        if (!src.contains(s.offset, s.size)) { error("${s.name} extends beyond end of file"); continue }
                        val linked = sections.getOrNull(s.link.toInt())
                        val syms = if (linked?.type == ElfConstants.SHT_SYMTAB) staticSyms else dynSyms
                        val n = minOf(s.size / ent, (limits.maxRelocations - out.size).toLong()).toInt()
                        for (i in 0 until n) {
                            if (i % 65536 == 0) checkCancelled()
                            val o = s.offset + i * ent
                            val offset = r.word(o, is64); val info = r.word(o + ent / (if (isRela) 3 else 2), is64)
                            val addend = if (isRela) (if (is64) r.u64(o + 16) else r.s32(o + 8).toLong()) else null
                            val sym = if (is64) info ushr 32 else info ushr 8
                            val type = if (is64) info and 0xffffffffL else info and 0xff
                            out += Relocation(s.name, offset, type, ElfConstants.relocationTypeName(h.machine, type), sym,
                                syms.getOrNull(sym.toInt())?.name?.takeIf { sym != 0L && it.isNotEmpty() }, addend)
                        }
                    }
                    ElfConstants.SHT_RELR, ElfConstants.SHT_ANDROID_RELR -> decodeRelr(h, s, out)
                    ElfConstants.SHT_ANDROID_REL, ElfConstants.SHT_ANDROID_RELA ->
                        info("${s.name}: Android packed relocations (APS2, ${s.size} bytes) are present; decoding them is not yet supported, so they are not listed.")
                }
            }
            return out
        }

        fun decodeRelr(h: ElfHeader, s: SectionHeader, out: MutableList<Relocation>) {
            if (!src.contains(s.offset, s.size)) { error("${s.name} extends beyond end of file"); return }
            val w = if (is64) 8L else 4L
            val bits = if (is64) 63 else 31
            val relType = when (h.machine) { ElfConstants.EM_AARCH64 -> 1027L; ElfConstants.EM_ARM -> 23L; ElfConstants.EM_X86_64 -> 8L; ElfConstants.EM_386 -> 8L; ElfConstants.EM_RISCV -> 3L; else -> 0L }
            val name = ElfConstants.relocationTypeName(h.machine, relType)
            var where = 0L
            var i = 0L
            while (i < s.size / w) {
                if (out.size >= limits.maxRelocations) { warn("Relocation limit reached while decoding ${s.name}"); return }
                val entry = r.word(s.offset + i * w, is64)
                if (entry and 1L == 0L) {
                    out += Relocation(s.name, entry, relType, name, 0, null, null)
                    where = entry + w
                } else {
                    var bitmap = entry ushr 1
                    var k = 0
                    while (bitmap != 0L && k < bits) {
                        if (bitmap and 1L != 0L) out += Relocation(s.name, where + k * w, relType, name, 0, null, null)
                        bitmap = bitmap ushr 1; k++
                    }
                    where += bits * w
                }
                i++
            }
        }

        // ------------------------------------------------------------------ notes
        fun readNotes(sections: List<SectionHeader>, phdrs: List<ProgramHeader>): List<ElfNote> {
            val out = ArrayList<ElfNote>()
            val noteSecs = sections.filter { it.type == ElfConstants.SHT_NOTE }
            if (noteSecs.isNotEmpty()) noteSecs.forEach { parseNotes(it.name, it.offset, it.size, it.addrAlign, out) }
            else phdrs.filter { it.type == ElfConstants.PT_NOTE }.forEach { parseNotes("PT_NOTE[${it.index}]", it.offset, it.fileSize, it.align, out) }
            return out
        }

        fun parseNotes(label: String, off: Long, size: Long, align: Long, out: MutableList<ElfNote>) {
            if (!src.contains(off, size)) { error("Note $label extends beyond end of file"); return }
            val a = if (align == 8L) 8L else 4L
            fun pad(x: Long) = (x + a - 1) / a * a
            var o = off; val end = off + size
            while (o + 12 <= end && out.size < limits.maxNotes) {
                val namesz = r.u32(o); val descsz = r.u32(o + 4); val type = r.u32(o + 8)
                val nameOff = o + 12; val descOff = nameOff + pad(namesz)
                if (namesz > size || descsz > size || descOff + descsz > end) { warn("Malformed note in $label"); return }
                val owner = String(src.readBytes(nameOff, namesz.toInt()), Charsets.UTF_8).trimEnd('\u0000')
                out += ElfNote(label, owner, type, src.readBytes(descOff, descsz.toInt()))
                o = descOff + pad(descsz)
            }
        }

        // ------------------------------------------------------------------ security facts
        fun computeSecurity(h: ElfHeader, phdrs: List<ProgramHeader>, dyn: List<Pair<Long, Long>>): SecurityProperties {
            fun v(tag: Long) = dyn.firstOrNull { it.first == tag }?.second
            val loads = phdrs.filter { it.type == ElfConstants.PT_LOAD }
            val stack = phdrs.firstOrNull { it.type == ElfConstants.PT_GNU_STACK }
            val bindNow = dyn.any { it.first == ElfConstants.DT_BIND_NOW } ||
                (v(ElfConstants.DT_FLAGS) ?: 0L) and ElfConstants.DF_BIND_NOW != 0L ||
                (v(ElfConstants.DT_FLAGS_1) ?: 0L) and ElfConstants.DF_1_NOW != 0L
            val hasRelro = phdrs.any { it.type == ElfConstants.PT_GNU_RELRO }
            val textRel = dyn.any { it.first == ElfConstants.DT_TEXTREL } || (v(ElfConstants.DT_FLAGS) ?: 0L) and ElfConstants.DF_TEXTREL != 0L
            val maxAlign = loads.maxOfOrNull { it.align } ?: 0L
            val minAlign = loads.minOfOrNull { it.align } ?: 0L
            return SecurityProperties(
                hasRwxSegment = loads.any { it.isWritable && it.isExecutable },
                nxStack = stack?.let { !it.isExecutable },
                relro = when { !hasRelro -> RelroLevel.NONE; bindNow -> RelroLevel.FULL; else -> RelroLevel.PARTIAL },
                bindNow = bindNow,
                hasTextRelocations = textRel,
                isPie = h.type == ElfConstants.ET_DYN,
                maxLoadAlignment = maxAlign,
                supports16KbPages = loads.isNotEmpty() && minAlign >= 16384,
            )
        }

        fun validateLayout(h: ElfHeader, phdrs: List<ProgramHeader>, sections: List<SectionHeader>) {
            for (p in phdrs) {
                if (p.fileSize > 0 && !src.contains(p.offset, p.fileSize)) error("Segment ${p.index} (${p.typeName}) extends beyond end of file")
                if (p.fileSize > p.memSize && p.type == ElfConstants.PT_LOAD) warn("Segment ${p.index}: p_filesz > p_memsz")
                if (p.type == ElfConstants.PT_LOAD && p.align > 1 && (p.align and (p.align - 1)) != 0L) warn("Segment ${p.index}: alignment ${p.align} is not a power of two")
                if (p.type == ElfConstants.PT_LOAD && p.align > 1 && (p.vaddr - p.offset) % p.align != 0L) warn("Segment ${p.index}: p_vaddr and p_offset are not congruent modulo p_align")
            }
            for (s in sections) {
                if (s.occupiesFile && s.size > 0 && !src.contains(s.offset, s.size)) error("Section ${s.index} '${s.name}' extends beyond end of file")
            }
            if (h.type == ElfConstants.ET_DYN && phdrs.none { it.type == ElfConstants.PT_LOAD }) warn("Shared object has no PT_LOAD segments")
        }
    }
}
