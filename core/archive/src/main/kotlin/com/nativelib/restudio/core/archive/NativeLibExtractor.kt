package com.nativelib.restudio.core.archive

import com.nativelib.restudio.core.common.Hashing
import com.nativelib.restudio.core.common.MappedFileSource
import com.nativelib.restudio.core.common.toHex
import com.nativelib.restudio.core.elf.ElfConstants
import com.nativelib.restudio.core.elf.ElfFormatException
import com.nativelib.restudio.core.elf.ElfParser
import com.nativelib.restudio.core.elf.WarningSeverity
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Finds and extracts native libraries from APK / XAPK / APKS / AAB / ZIP / JAR containers.
 *
 * Safety:
 *  - The source file is only ever opened read-only.
 *  - Entry names are validated against path traversal ("zip slip") before writing.
 *  - Per-entry size limits guard against decompression bombs.
 *  - Extracted libraries are never loaded or executed.
 */
class NativeLibExtractor(private val limits: Limits = Limits()) {

    data class Limits(
        val maxEntryBytes: Long = 2L * 1024 * 1024 * 1024,
        val maxNestedContainerBytes: Long = 4L * 1024 * 1024 * 1024,
        val maxCompressionRatio: Long = 2000,
    )

    /** Lists native libraries without extracting anything. */
    fun scan(file: File, checkCancelled: () -> Unit = {}): List<NativeLibEntry> {
        val type = FormatDetector.detect(file)
        if (!type.isArchive) return emptyList()
        val tmp = createTempDir(file)
        try {
            val out = ArrayList<NativeLibEntry>()
            openZip(file).use { zip ->
                out += findLibs(zip, "", type)
                if (type == ContainerType.XAPK || type == ContainerType.APKS) {
                    for (nested in nestedApks(zip)) {
                        checkCancelled()
                        val nf = copyNested(zip, nested, tmp, checkCancelled)
                        try { openZip(nf).use { out += findLibs(it, nested.name, ContainerType.APK) } }
                        catch (_: ArchiveException) { } finally { nf.delete() }
                    }
                }
            }
            return out
        } finally { tmp.deleteRecursively() }
    }

    /**
     * Extracts every native library into [outDir], preserving the original directory structure
     * (nested containers get their own sub-directory), verifies each with CRC-32 and SHA-256,
     * parses each with the ELF parser, and returns a full report.
     */
    fun extract(
        file: File,
        outDir: File,
        progress: ProgressListener = ProgressListener { _, _, _ -> },
        checkCancelled: () -> Unit = {},
    ): ExtractionReport {
        val start = System.currentTimeMillis()
        val type = FormatDetector.detect(file)
        val sourceSha = Hashing.sha256(file, checkCancelled)
        val issues = ArrayList<Issue>()
        val libs = ArrayList<ExtractedLib>()
        val nestedNames = ArrayList<String>()
        var totalEntries = 0
        outDir.mkdirs()
        val canonicalOut = outDir.canonicalFile

        when (type) {
            ContainerType.ELF -> {
                val entry = NativeLibEntry("", file.name, null, file.name, file.length(), file.length(), -1, CompressionMethod.STORED)
                val elf = summarize(file, issues, entry.displayPath)
                libs += ExtractedLib(entry, null, sourceSha, true, true, elf)
                totalEntries = 1
            }
            ContainerType.DEX, ContainerType.UNKNOWN -> {
                issues += Issue(IssueType.NOT_ELF, IssueSeverity.INFO, "${type.label}: this input contains no native libraries to extract.")
            }
            else -> {
                val tmp = createTempDir(outDir)
                try {
                    openZip(file).use { zip ->
                        totalEntries += zip.size()
                        val top = findLibs(zip, "", type)
                        val nestedEntries = if (type == ContainerType.XAPK || type == ContainerType.APKS) nestedApks(zip) else emptyList()
                        var done = 0
                        extractAll(zip, top, canonicalOut, libs, issues, checkCancelled) { cur -> progress.onProgress(++done, top.size, cur) }
                        for (n in nestedEntries) {
                            checkCancelled()
                            nestedNames += n.name
                            if (!isSafeEntryName(n.name)) { issues += Issue(IssueType.UNSAFE_PATH, IssueSeverity.ERROR, "Nested container has unsafe path; skipped", n.name); continue }
                            val nf = try { copyNested(zip, n, tmp, checkCancelled) } catch (e: ArchiveException) {
                                issues += Issue(IssueType.NESTED_CONTAINER_ERROR, IssueSeverity.ERROR, e.message ?: "copy failed", n.name); continue
                            }
                            try {
                                openZip(nf).use { nz ->
                                    totalEntries += nz.size()
                                    val nl = findLibs(nz, n.name, ContainerType.APK)
                                    var nd = 0
                                    extractAll(nz, nl, canonicalOut, libs, issues, checkCancelled) { cur -> progress.onProgress(++nd, nl.size, cur) }
                                }
                            } catch (e: ArchiveException) {
                                issues += Issue(IssueType.NESTED_CONTAINER_ERROR, IssueSeverity.ERROR, "Nested APK is not a valid archive: ${e.message}", n.name)
                            } finally { nf.delete() }
                        }
                    }
                } finally { tmp.deleteRecursively() }
            }
        }

        analyzeSet(libs, issues)
        val abis = libs.mapNotNull { it.entry.abi }.distinct().sortedBy { a -> Abis.SUPPORTED.indexOf(a).let { if (it < 0) 99 else it } }
        return ExtractionReport(file.name, file.length(), sourceSha, type, nestedNames, abis, libs, issues, totalEntries,
            System.currentTimeMillis() - start)
    }

    // ------------------------------------------------------------------------------------

    private fun openZip(file: File): ZipFile = try {
        ZipFile(file)
    } catch (e: ZipException) {
        throw ArchiveException("Invalid or corrupted ZIP archive: ${e.message}", e)
    } catch (e: java.io.IOException) {
        throw ArchiveException("Cannot open archive: ${e.message}", e)
    }

    private fun createTempDir(near: File): File {
        val base = if (near.isDirectory) near else near.absoluteFile.parentFile ?: File(System.getProperty("java.io.tmpdir"))
        val d = File(base, ".nlre_tmp_" + System.nanoTime())
        d.mkdirs()
        return d
    }

    private fun nestedApks(zip: ZipFile): List<ZipEntry> =
        zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }.sortedBy { it.name }.toList()

    private fun copyNested(zip: ZipFile, e: ZipEntry, tmp: File, checkCancelled: () -> Unit): File {
        val out = File(tmp, "nested_" + Hashing.sha256(e.name.toByteArray()).take(16) + ".apk")
        var total = 0L
        zip.getInputStream(e).use { input ->
            FileOutputStream(out).use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > limits.maxNestedContainerBytes) throw ArchiveException("Nested container ${e.name} exceeds size limit")
                    os.write(buf, 0, n)
                }
            }
        }
        return out
    }

    internal fun findLibs(zip: ZipFile, container: String, type: ContainerType): List<NativeLibEntry> {
        val out = ArrayList<NativeLibEntry>()
        for (e in zip.entries()) {
            if (e.isDirectory) continue
            val name = e.name
            val fileName = name.substringAfterLast('/')
            if (!isSharedObjectName(fileName)) continue
            val parts = name.split('/')
            val abi: String? = when {
                // lib/<abi>/<file>.so  — APK, nested split APKs, ZIP, JAR
                parts.size == 3 && parts[0] == "lib" -> parts[1]
                // <module>/lib/<abi>/<file>.so — AAB modules
                type == ContainerType.AAB && parts.size == 4 && parts[1] == "lib" -> parts[2]
                // generic archives: accept lib/<known-abi>/ anywhere
                (type == ContainerType.ZIP || type == ContainerType.JAR) && parts.size >= 3 && parts[parts.size - 3] == "lib" && Abis.isKnown(parts[parts.size - 2]) -> parts[parts.size - 2]
                else -> null
            }
            val method = when (e.method) { ZipEntry.STORED -> CompressionMethod.STORED; ZipEntry.DEFLATED -> CompressionMethod.DEFLATED; else -> CompressionMethod.OTHER }
            out += NativeLibEntry(container, name, abi, fileName, e.size, e.compressedSize, e.crc, method)
        }
        return out.sortedWith(compareBy({ it.abi ?: "~" }, { it.entryPath }))
    }

    private fun extractAll(
        zip: ZipFile, entries: List<NativeLibEntry>, outRoot: File, libs: MutableList<ExtractedLib>,
        issues: MutableList<Issue>, checkCancelled: () -> Unit, onEach: (String) -> Unit,
    ) {
        for (lib in entries) {
            checkCancelled()
            onEach(lib.displayPath)
            libs += extractOne(zip, lib, outRoot, issues, checkCancelled)
        }
    }

    private fun extractOne(zip: ZipFile, lib: NativeLibEntry, outRoot: File, issues: MutableList<Issue>, checkCancelled: () -> Unit): ExtractedLib {
        val path = lib.displayPath
        if (!isSafeEntryName(lib.entryPath)) {
            issues += Issue(IssueType.UNSAFE_PATH, IssueSeverity.ERROR, "Entry name contains path traversal or absolute path; not extracted", path)
            return ExtractedLib(lib, null, null, false, false, null)
        }
        if (lib.size > limits.maxEntryBytes) {
            issues += Issue(IssueType.TOO_LARGE, IssueSeverity.ERROR, "Declared size ${lib.size} exceeds limit", path)
            return ExtractedLib(lib, null, null, false, false, null)
        }
        val rel = (if (lib.container.isEmpty()) "" else containerDirName(lib.container) + "/") + lib.entryPath
        val outFile = File(outRoot, rel).canonicalFile
        if (!outFile.path.startsWith(outRoot.path + File.separator)) {
            issues += Issue(IssueType.UNSAFE_PATH, IssueSeverity.ERROR, "Resolved output path escapes extraction directory; not extracted", path)
            return ExtractedLib(lib, null, null, false, false, null)
        }
        outFile.parentFile?.mkdirs()
        val entry = zip.getEntry(lib.entryPath) ?: run {
            issues += Issue(IssueType.CORRUPTED, IssueSeverity.ERROR, "Entry disappeared from archive directory", path)
            return ExtractedLib(lib, null, null, false, false, null)
        }
        val md = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var written = 0L
        try {
            zip.getInputStream(entry).use { input ->
                FileOutputStream(outFile).use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buf); if (n < 0) break
                        written += n
                        if (written > limits.maxEntryBytes || (lib.compressedSize > 0 && written / lib.compressedSize > limits.maxCompressionRatio && written > 64L * 1024 * 1024))
                            throw ArchiveException("Decompressed size exceeds safety limits (possible decompression bomb)")
                        md.update(buf, 0, n); crc.update(buf, 0, n); os.write(buf, 0, n)
                    }
                    os.fd.sync()
                }
            }
        } catch (e: java.util.concurrent.CancellationException) {
            outFile.delete(); throw e
        } catch (e: Exception) {
            outFile.delete()
            val type = if (e is ArchiveException && e.message?.contains("bomb") == true) IssueType.TOO_LARGE else IssueType.CORRUPTED
            issues += Issue(type, IssueSeverity.ERROR, "Extraction failed: ${e.message ?: e.javaClass.simpleName}", path)
            return ExtractedLib(lib, null, null, false, false, null)
        }
        val sha = md.digest().toHex()
        val crcOk = lib.crc == -1L || crc.value == lib.crc
        if (!crcOk) issues += Issue(IssueType.CORRUPTED, IssueSeverity.ERROR, "CRC-32 mismatch: archive says %08x, data is %08x".format(lib.crc, crc.value), path)
        if (lib.size >= 0 && written != lib.size) issues += Issue(IssueType.CORRUPTED, IssueSeverity.ERROR, "Size mismatch: archive says ${lib.size}, extracted $written bytes", path)
        // Independent verification: re-read the written file from disk.
        val verifiedSha = Hashing.sha256(outFile, checkCancelled)
        val hashOk = verifiedSha == sha
        if (!hashOk) issues += Issue(IssueType.CORRUPTED, IssueSeverity.ERROR, "SHA-256 of written file differs from streamed data (storage error)", path)
        outFile.setReadOnly()
        val elf = summarize(outFile, issues, path)
        return ExtractedLib(lib, rel, sha, hashOk, crcOk, elf)
    }

    private fun summarize(file: File, issues: MutableList<Issue>, path: String): ElfSummary? = try {
        MappedFileSource(file).use { src ->
            if (src.size == 0L) throw ElfFormatException("empty file")
            val elf = ElfParser().parse(src)
            ElfSummary(
                machine = elf.header.machineName, bits = elf.header.ident.elfClass.bits,
                elfAbi = ElfConstants.androidAbiFor(elf.header.machine, elf.header.is64),
                soname = elf.soname, needed = elf.neededLibraries,
                exportedCount = elf.exportedSymbols.size, importedCount = elf.importedSymbols.size,
                stripped = elf.isStripped, buildId = elf.buildId, supports16KbPages = elf.security.supports16KbPages,
                parserErrors = elf.warnings.count { it.severity == WarningSeverity.ERROR },
            ).also { if (it.parserErrors > 0) issues += Issue(IssueType.CORRUPTED, IssueSeverity.WARNING, "ELF structure has ${it.parserErrors} error(s); see ELF Analyzer", path) }
        }
    } catch (e: ElfFormatException) {
        issues += Issue(IssueType.NOT_ELF, IssueSeverity.ERROR, e.message ?: "Not an ELF file", path); null
    } catch (e: java.io.IOException) {
        issues += Issue(IssueType.CORRUPTED, IssueSeverity.ERROR, "Cannot read extracted file: ${e.message}", path); null
    }

    /** Cross-library checks: ABI consistency, missing, duplicate, identical binaries. */
    private fun analyzeSet(libs: List<ExtractedLib>, issues: MutableList<Issue>) {
        for (l in libs) {
            val abi = l.entry.abi
            when {
                abi == null && l.entry.container.isEmpty() && l.entry.entryPath == l.entry.fileName && libs.size == 1 -> Unit // direct .so import
                abi == null -> issues += Issue(IssueType.NON_STANDARD_LOCATION, IssueSeverity.WARNING, "Shared object outside lib/<abi>/; the Android package manager will not install it as a native library", l.entry.displayPath)
                abi in Abis.DEPRECATED -> issues += Issue(IssueType.UNSUPPORTED_ABI, IssueSeverity.WARNING, "ABI '$abi' is no longer supported by Android", l.entry.displayPath)
                !Abis.isKnown(abi) -> issues += Issue(IssueType.UNSUPPORTED_ABI, IssueSeverity.WARNING, "Unknown ABI directory '$abi'", l.entry.displayPath)
            }
            if (abi != null && l.elf != null && Abis.isKnown(abi) && !Abis.matches(abi, l.elf.elfAbi)) {
                issues += Issue(IssueType.ABI_MISMATCH, IssueSeverity.ERROR, "Folder is '$abi' but ELF is ${l.elf.machine} ${l.elf.bits}-bit (${l.elf.elfAbi ?: "no Android ABI"})", l.entry.displayPath)
            }
            if (abi != null && l.elf != null && !l.elf.supports16KbPages && (abi == "arm64-v8a" || abi == "x86_64")) {
                issues += Issue(IssueType.NOT_16KB_ALIGNED, IssueSeverity.INFO, "LOAD segments are not 16 KiB aligned; will not load on 16 KiB page-size devices", l.entry.displayPath)
            }
        }
        val standard = libs.filter { it.entry.abi != null && it.entry.abi in Abis.SUPPORTED }
        val byAbi = standard.groupBy { it.entry.abi!! }
        // missing in some ABI
        if (byAbi.size > 1) {
            val allNames = standard.map { it.entry.fileName }.toSortedSet()
            for ((abi, list) in byAbi.toSortedMap()) {
                val have = list.map { it.entry.fileName }.toSet()
                for (n in allNames) if (n !in have) {
                    val where = byAbi.filter { (_, l) -> l.any { it.entry.fileName == n } }.keys.sorted().joinToString()
                    issues += Issue(IssueType.MISSING_IN_ABI, IssueSeverity.WARNING, "$n is present in [$where] but missing in $abi", "lib/$abi/$n")
                }
            }
        }
        // duplicates (same ABI + name from several containers)
        standard.groupBy { it.entry.abi + "/" + it.entry.fileName }.filter { it.value.size > 1 }.toSortedMap().forEach { (k, v) ->
            val same = v.mapNotNull { it.sha256 }.distinct().size == 1
            issues += Issue(IssueType.DUPLICATE, IssueSeverity.WARNING,
                "$k appears ${v.size} times (${v.joinToString { it.entry.container.ifEmpty { "base" } }})${if (same) ", identical content" else ", DIFFERENT content"}", k)
        }
        // identical bytes under different ABIs
        standard.filter { it.sha256 != null }.groupBy { it.sha256!! }.values.filter { g -> g.map { it.entry.abi }.distinct().size > 1 }.forEach { g ->
            issues += Issue(IssueType.IDENTICAL_ACROSS_ABIS, IssueSeverity.WARNING,
                "Byte-identical file under several ABIs: ${g.joinToString { it.entry.displayPath }} — at most one can be the correct architecture", g.first().entry.fileName)
        }
    }

    companion object {
        fun isSharedObjectName(fileName: String): Boolean =
            fileName.endsWith(".so") || Regex(""".+\.so(\.\d+)+$""").matches(fileName)

        /** Rejects absolute paths, drive letters, backslashes and ".." segments. */
        fun isSafeEntryName(name: String): Boolean {
            if (name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains('\u0000')) return false
            if (name.length >= 2 && name[1] == ':') return false
            return name.split('/').none { it == ".." }
        }

        fun containerDirName(container: String): String = container.replace('/', '_').replace("..", "_")
    }
}
