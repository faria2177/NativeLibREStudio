package com.nativelib.restudio.core.strings

import com.nativelib.restudio.core.common.ByteSource
import com.nativelib.restudio.core.elf.ElfConstants
import com.nativelib.restudio.core.elf.ElfFile

enum class StringEncoding(val label: String) { ASCII("ASCII"), UTF8("UTF-8"), UTF16LE("UTF-16LE"), UTF16BE("UTF-16BE") }

enum class StringCategory(val label: String) {
    URL("URL"), IP_ADDRESS("IP address"), EMAIL("Email"), FILE_PATH("File path"), CLASS_NAME("Class name"),
    FUNCTION_NAME("Function name"), FORMAT_STRING("Format string"), ERROR_MESSAGE("Error message"),
    DEBUG_MESSAGE("Debug / log"), CONFIG_VALUE("Config value"),
}

data class ExtractedString(
    /** File offset of the first byte. */
    val offset: Long,
    /** Length in bytes inside the file. */
    val byteLength: Int,
    val encoding: StringEncoding,
    val text: String,
    /** Containing ELF section (when the input is an ELF with section headers). */
    val section: String? = null,
    /** Virtual address when the offset is inside a mapped section/segment. */
    val vaddr: Long? = null,
    val categories: Set<StringCategory> = emptySet(),
)

data class StringOptions(
    val minLength: Int = 4,
    val maxLength: Int = 4096,
    /** UTF-16BE is off by default: Android is little-endian and BE scanning mostly yields noise. */
    val encodings: Set<StringEncoding> = setOf(StringEncoding.ASCII, StringEncoding.UTF8, StringEncoding.UTF16LE),
    val maxResults: Int = 200_000,
    val chunkSize: Int = 1 shl 20,
) {
    init {
        require(minLength in 1..1024) { "minLength must be 1..1024" }
        require(maxLength >= minLength) { "maxLength must be >= minLength" }
        require(chunkSize >= 16) { "chunkSize too small" }
    }
}

data class StringScanResult(
    val strings: List<ExtractedString>,
    /** True when [StringOptions.maxResults] was reached and scanning stopped early. */
    val truncated: Boolean,
    val bytesScanned: Long,
)

/**
 * Streaming string extractor. Data is read in chunks (never the whole file at once) and fed to
 * independent state machines: one narrow machine (ASCII / UTF-8) and four UTF-16 machines
 * (LE/BE × even/odd alignment), so strings crossing chunk boundaries are found exactly once.
 *
 * Nothing found is ever interpreted, opened or executed — strings are plain data.
 */
class StringExtractor(private val options: StringOptions = StringOptions()) {

    fun extract(
        source: ByteSource,
        elf: ElfFile? = null,
        progress: (scanned: Long, total: Long) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): StringScanResult {
        val out = ArrayList<ExtractedString>()
        var truncated = false
        val sink: (Long, Int, StringEncoding, String) -> Boolean = { off, len, enc, text ->
            if (out.size >= options.maxResults) { truncated = true; false }
            else { out += ExtractedString(off, len, enc, text); true }
        }
        val machines = buildList<Machine> {
            if (StringEncoding.ASCII in options.encodings || StringEncoding.UTF8 in options.encodings) add(NarrowMachine(options, sink))
            if (StringEncoding.UTF16LE in options.encodings) { add(WideMachine(options, true, 0, sink)); add(WideMachine(options, true, 1, sink)) }
            if (StringEncoding.UTF16BE in options.encodings) { add(WideMachine(options, false, 0, sink)); add(WideMachine(options, false, 1, sink)) }
        }
        val buf = ByteArray(options.chunkSize)
        var pos = 0L
        val total = source.size
        while (pos < total && !truncated) {
            checkCancelled()
            val n = minOf(buf.size.toLong(), total - pos).toInt()
            source.readFully(pos, buf, 0, n)
            for (m in machines) m.feed(buf, n, pos)
            pos += n
            progress(pos, total)
        }
        if (!truncated) for (m in machines) m.finish()
        val resolved = resolveUtf16Overlaps(out).toList()
        out.clear(); out.addAll(resolved)
        out.sortWith(compareBy({ it.offset }, { it.encoding.ordinal }))
        val mapper = elf?.let { SectionMapper(it) }
        return StringScanResult(
            out.map { s ->
                val loc = mapper?.locate(s.offset)
                s.copy(section = loc?.first, vaddr = loc?.second, categories = StringClassifier.classify(s.text))
            },
            truncated, pos,
        )
    }

    private interface Machine {
        fun feed(buf: ByteArray, n: Int, base: Long)
        fun finish()
    }

    /** ASCII + UTF-8 runs. Pure-ASCII runs are labelled ASCII, runs with multibyte characters UTF-8. */
    private class NarrowMachine(val o: StringOptions, val sink: (Long, Int, StringEncoding, String) -> Boolean) : Machine {
        private val allowUtf8 = StringEncoding.UTF8 in o.encodings
        private val allowAscii = StringEncoding.ASCII in o.encodings
        private val sb = StringBuilder()
        private var start = -1L
        private var bytes = 0
        private var chars = 0
        private var nonAscii = false
        // pending UTF-8 multibyte sequence
        private var need = 0
        private var cp = 0
        private var seqStart = -1L
        private var seqLen = 0
        private var minCp = 0

        private fun emit() {
            if (chars >= o.minLength && start >= 0) {
                val text = sb.toString()
                if (!nonAscii) { if (allowAscii) sink(start, bytes, StringEncoding.ASCII, text) }
                else if (allowUtf8 && plausibleUtf8(text)) sink(start, bytes, StringEncoding.UTF8, text)
                else if (allowAscii) emitAsciiParts(text)
            }
            sb.setLength(0); start = -1; bytes = 0; chars = 0; nonAscii = false
        }

        /** Random bytes often form one valid 2-byte UTF-8 char; such runs are split back into their ASCII parts. */
        private fun emitAsciiParts(text: String) {
            var off = start; var partStart = -1L; val part = StringBuilder()
            var i = 0
            while (i <= text.length) {
                val cp = if (i < text.length) text.codePointAt(i) else -1
                if (cp in 0..0x7F) { if (partStart < 0) partStart = off; part.appendCodePoint(cp) }
                else {
                    if (part.length >= o.minLength) sink(partStart, part.length, StringEncoding.ASCII, part.toString())
                    part.setLength(0); partStart = -1
                }
                if (cp < 0) break
                off += when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
                i += Character.charCount(cp)
            }
        }

        private fun appendCodePoint(c: Int, at: Long, len: Int) {
            if (start < 0) start = at
            sb.appendCodePoint(c); bytes += len; chars++
            if (c >= 0x80) nonAscii = true
            if (chars >= o.maxLength) emit()
        }

        override fun feed(buf: ByteArray, n: Int, base: Long) {
            var i = 0
            while (i < n) {
                val b = buf[i].toInt() and 0xFF
                val off = base + i
                if (need > 0) {
                    if (b and 0xC0 == 0x80) {
                        cp = (cp shl 6) or (b and 0x3F); need--; seqLen++
                        if (need == 0) {
                            if (cp >= minCp && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF) && isPrintableCodePoint(cp)) appendCodePoint(cp, seqStart, seqLen)
                            else emit()
                        }
                        i++; continue
                    }
                    // broken sequence: end the run and re-process this byte
                    need = 0; emit()
                }
                when {
                    b == 0x09 || b in 0x20..0x7E -> appendCodePoint(b, off, 1)
                    allowUtf8 && b in 0xC2..0xDF -> { need = 1; cp = b and 0x1F; seqStart = off; seqLen = 1; minCp = 0x80 }
                    allowUtf8 && b in 0xE0..0xEF -> { need = 2; cp = b and 0x0F; seqStart = off; seqLen = 1; minCp = 0x800 }
                    allowUtf8 && b in 0xF0..0xF4 -> { need = 3; cp = b and 0x07; seqStart = off; seqLen = 1; minCp = 0x10000 }
                    else -> emit()
                }
                i++
            }
        }

        override fun finish() { need = 0; emit() }
    }

    /**
     * UTF-16 runs at one alignment.
     *
     * Accepts printable Latin-1 (like `strings -el/-eb`) plus letters/marks/digits of *one* other
     * 256-code-point block per run (e.g. Bengali U+0980–U+09FF, Cyrillic, Arabic, Devanagari).
     * CJK/Hangul/Yi blocks (U+3400 and above) are not accepted: they cover so much of the code space
     * that random binary data would produce endless false positives. Runs whose bytes are all printable
     * ASCII are rejected too: those are narrow strings misread as UTF-16.
     */
    private class WideMachine(val o: StringOptions, val little: Boolean, val alignment: Int, val sink: (Long, Int, StringEncoding, String) -> Boolean) : Machine {
        private val enc = if (little) StringEncoding.UTF16LE else StringEncoding.UTF16BE
        private val sb = StringBuilder()
        private var start = -1L
        private var half = -1
        private var halfOff = 0L
        private var allBytesAscii = true
        private var blockHi = -1

        private fun emit() {
            if (sb.length >= o.minLength && !allBytesAscii && plausibleWide(sb)) sink(start, sb.length * 2, enc, sb.toString())
            sb.setLength(0); start = -1; allBytesAscii = true; blockHi = -1
        }

        override fun feed(buf: ByteArray, n: Int, base: Long) {
            for (i in 0 until n) {
                val off = base + i
                val b = buf[i].toInt() and 0xFF
                if (((off and 1L).toInt()) == alignment) { half = b; halfOff = off; continue }
                if (half < 0) continue
                val unit = if (little) (b shl 8) or half else (half shl 8) or b
                val lo = half; half = -1
                val hi = unit ushr 8
                val accepted = isPrintableUnit(unit) && (hi == 0 || hi in 0x03..0x1C)
                if (accepted && hi != 0 && blockHi >= 0 && hi != blockHi) emit() // script changed: new run
                if (accepted && sb.isEmpty() && isMark(unit)) { emit(); continue } // a run never starts with a combining mark
                if (accepted) {
                    if (hi != 0) blockHi = hi
                    if (start < 0) start = halfOff
                    sb.append(unit.toChar())
                    if (!(isAsciiPrintable(lo) && isAsciiPrintable(b))) allBytesAscii = false
                    if (sb.length >= o.maxLength) emit()
                } else emit()
            }
        }

        override fun finish() { half = -1; emit() }

        private fun isAsciiPrintable(x: Int) = x == 0x09 || x in 0x20..0x7E
    }

    /** Maps file offsets to (section name, virtual address). */
    private class SectionMapper(elf: ElfFile) {
        private val secs = elf.sections.filter { it.occupiesFile && it.size > 0 }.sortedBy { it.offset }
        private val loads = elf.programHeaders.filter { it.type == ElfConstants.PT_LOAD }

        fun locate(off: Long): Pair<String?, Long?> {
            var lo = 0; var hi = secs.size - 1; var found: com.nativelib.restudio.core.elf.SectionHeader? = null
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val s = secs[mid]
                when {
                    off < s.offset -> hi = mid - 1
                    off >= s.offset + s.size -> lo = mid + 1
                    else -> { found = s; break }
                }
            }
            if (found != null) {
                val va = if (found.flags and ElfConstants.SHF_ALLOC != 0L) found.addr + (off - found.offset) else null
                return found.name to va
            }
            val p = loads.firstOrNull { off >= it.offset && off < it.offset + it.fileSize }
            return null to p?.let { it.vaddr + (off - it.offset) }
        }
    }

    companion object {
        private fun isMark(u: Int) = Character.getType(u).toByte().let {
            it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK || it == Character.ENCLOSING_MARK
        }

        /**
         * UTF-16 text is ambiguous: "00 57 00 69" is "Wi" in BE and, shifted by one byte, also in LE.
         * When an LE and a BE run overlap, keep the one with more plain-ASCII units; on a tie the
         * even-aligned one (compilers align wide strings), then LE (Android is little-endian).
         */
        internal fun resolveUtf16Overlaps(all: List<ExtractedString>): List<ExtractedString> {
            val wide = all.filter { it.encoding == StringEncoding.UTF16LE || it.encoding == StringEncoding.UTF16BE }.sortedBy { it.offset }
            if (wide.size < 2) return all
            fun score(s: ExtractedString) = s.text.count { it.code in 0x20..0x7E }
            fun better(a: ExtractedString, b: ExtractedString): Boolean {
                val sa = score(a); val sb = score(b)
                if (sa != sb) return sa > sb
                val ea = a.offset % 2 == 0L; val eb = b.offset % 2 == 0L
                if (ea != eb) return ea
                return a.encoding == StringEncoding.UTF16LE
            }
            val dropped = HashSet<ExtractedString>()
            for (i in wide.indices) {
                val a = wide[i]
                var j = i + 1
                while (j < wide.size && wide[j].offset < a.offset + a.byteLength) {
                    val b = wide[j]
                    if (a.encoding != b.encoding && a !in dropped && b !in dropped) dropped += if (better(a, b)) b else a
                    j++
                }
            }
            return if (dropped.isEmpty()) all else all.filter { it !in dropped }
        }

        fun isPrintableCodePoint(c: Int): Boolean {
            if (c == 0x09 || c in 0x20..0x7E) return true
            if (c < 0xA0 || c == 0xFEFF || c in 0xFFF0..0xFFFF) return false
            return when (Character.getType(c).toByte()) {
                Character.CONTROL, Character.FORMAT, Character.UNASSIGNED, Character.PRIVATE_USE, Character.SURROGATE -> false
                else -> true
            }
        }

        /**
         * UTF-8 quality filter. Random bytes regularly form valid 2-byte sequences, so a run counts as UTF-8 text
         * only if its non-ASCII characters are letters/marks of one script and either
         *  - contain a contiguous block of at least 3 such characters from a non-Latin script, or
         *  - are Latin letters (é, ü, ñ…) in a run that is at least 70% letters/spaces.
         */
        internal fun plausibleUtf8(text: String): Boolean {
            var script: Character.UnicodeScript? = null
            var block = 0; var bestBlock = 0; var letters = 0; var total = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i); i += Character.charCount(cp); total++
                if (Character.isLetter(cp) || cp == ' '.code || isMarkCp(cp)) letters++
                if (cp < 0x80) { block = 0; continue }
                if (!(Character.isLetter(cp) || isMarkCp(cp))) return false
                val sc = Character.UnicodeScript.of(cp)
                if (sc != Character.UnicodeScript.INHERITED && sc != Character.UnicodeScript.COMMON) {
                    if (script == null) script = sc else if (script != sc) return false
                }
                block++; if (block > bestBlock) bestBlock = block
            }
            if (script == null) return false
            if (script != Character.UnicodeScript.LATIN) return bestBlock >= 3 && !looksLikeTable(text)
            // Latin: accented letters from Latin-1/Latin Extended-A only, mostly letters, no digits or tabs.
            if (text.any { it.isDigit() || it == '\t' || (it.code >= 0x80 && it.code !in 0xC0..0x17F) }) return false
            return letters * 10 >= total * 8
        }

        /**
         * Wide-run quality filter (machine code contains many 00 bytes, so raw UTF-16 scanning is noisy):
         * at least 75% letters/digits/spaces, and non-ASCII characters must be letters or marks
         * (at least 3 of them, or none at all).
         */
        internal fun plausibleWide(sb: CharSequence): Boolean {
            var good = 0; var nonAscii = 0
            for (c in sb) {
                val code = c.code
                if (code >= 0x80) { if (!(Character.isLetter(c) || isMarkCp(code))) return false; nonAscii++ }
                if (Character.isLetterOrDigit(c) || c == ' ' || isMarkCp(code)) good++
            }
            if (nonAscii in 1..2) return false
            if (nonAscii > 0) { // tables of 16-bit integers look like one repeated character or a counting sequence
                val maxSame = sb.groupingBy { it }.eachCount().values.max()
                if (maxSame * 2 > sb.length) return false
                if (looksLikeTable(sb)) return false
            }
            return good * 4 >= sb.length * 3
        }

        private fun isMarkCp(u: Int) = isMark(u)

        /** Unicode lookup tables: most neighbouring characters differ by at most 3 code points. */
        internal fun looksLikeTable(t: CharSequence): Boolean {
            if (t.length < 4) return false
            var close = 0
            for (i in 1 until t.length) if (kotlin.math.abs(t[i].code - t[i - 1].code) <= 3) close++
            return close * 10 >= (t.length - 1) * 7
        }

        fun isPrintableUnit(u: Int): Boolean {
            if (u == 0x09 || u in 0x20..0x7E) return true
            if (u in 0x80..0xFF) return false // Latin-1 supplement: indistinguishable from binary data in UTF-16
            if (u in 0xD800..0xDFFF) return false
            if (!isPrintableCodePoint(u)) return false
            return Character.isLetterOrDigit(u) || Character.isSpaceChar(u) ||
                Character.getType(u).toByte().let { it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK }
        }
    }
}
