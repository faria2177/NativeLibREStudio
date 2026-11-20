package com.nativelib.restudio.core.archive

import com.nativelib.restudio.core.common.Hashing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeLibExtractorTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("nlre_test").toFile() }
    @After fun tearDown() { dir.walkBottomUp().forEach { it.setWritable(true); it.delete() } }

    private fun res(name: String): ByteArray =
        // Samples are stored base64-encoded so the repository stays text-only.
        java.util.Base64.getMimeDecoder().decode(
            requireNotNull(javaClass.getResourceAsStream("/elf/$name.b64")) { "missing test resource $name" }.use { it.readBytes() })

    private val arm64 by lazy { res("libsample_arm64.so") }
    private val armv7 by lazy { res("libsample_armv7.so") }
    private val x86 by lazy { res("libsample_x86.so") }
    private val x8664 by lazy { res("libsample_x86_64.so") }

    /** Builds a ZIP; STORED entries are used when [stored] is true, as Android does for page-aligned libs. */
    private fun zipBytes(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) { e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = CRC32().apply { update(data) }.value }
                zos.putNextEntry(e); zos.write(data); zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }
    private val manifest = "AndroidManifest.xml" to ByteArray(16)

    @Test fun extractsAllAbisFromApkAndVerifiesHashes() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "classes.dex" to "dex\n035\u0000".toByteArray(),
            "lib/arm64-v8a/libsample.so" to arm64, "lib/armeabi-v7a/libsample.so" to armv7,
            "lib/x86_64/libsample.so" to x8664, "lib/x86/libsample.so" to x86)))
        val out = File(dir, "out")
        val r = NativeLibExtractor().extract(apk, out)
        assertEquals(ContainerType.APK, r.containerType)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), r.abis)
        assertEquals(4, r.libraries.size)
        assertEquals(4, r.extractedCount)
        assertEquals(4, r.verifiedCount)
        assertTrue("unexpected issues: ${r.issues}", r.issues.none { it.severity != IssueSeverity.INFO })
        val expected = mapOf("arm64-v8a" to arm64, "armeabi-v7a" to armv7, "x86" to x86, "x86_64" to x8664)
        for (l in r.libraries) {
            val f = File(out, l.outputRelativePath!!)
            assertTrue(f.exists())
            assertEquals("lib/${l.entry.abi}/libsample.so", l.outputRelativePath) // original structure preserved
            assertEquals(Hashing.sha256(expected.getValue(l.entry.abi!!)), l.sha256)
            assertEquals(Hashing.sha256(f), l.sha256)
            assertEquals(l.entry.abi, l.elf!!.elfAbi)
            assertEquals("libsample.so", l.elf!!.soname)
            assertEquals(listOf("libc.so"), l.elf!!.needed)
        }
        assertEquals(Hashing.sha256(apk), r.sourceSha256)
    }

    @Test fun originalFileIsNeverModified() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64)))
        val before = Hashing.sha256(apk); val mtime = apk.lastModified()
        NativeLibExtractor().extract(apk, File(dir, "out"))
        assertEquals(before, Hashing.sha256(apk)); assertEquals(mtime, apk.lastModified())
    }

    @Test fun detectsMissingLibrariesAcrossAbis() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64, "lib/arm64-v8a/libextra.so" to arm64.copyOf().also { it[it.size - 1] = 1 },
            "lib/armeabi-v7a/libsample.so" to armv7)))
        val r = NativeLibExtractor().extract(apk, File(dir, "out"))
        val missing = r.issues.filter { it.type == IssueType.MISSING_IN_ABI }
        assertEquals(1, missing.size)
        assertTrue(missing[0].message.contains("libextra.so") && missing[0].message.contains("armeabi-v7a"))
    }

    @Test fun detectsAbiMismatchAndIdenticalCopies() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64, "lib/x86_64/libsample.so" to arm64)))
        val r = NativeLibExtractor().extract(apk, File(dir, "out"))
        val mismatch = r.issues.single { it.type == IssueType.ABI_MISMATCH }
        assertEquals("lib/x86_64/libsample.so", mismatch.path)
        assertEquals(1, r.issues.count { it.type == IssueType.IDENTICAL_ACROSS_ABIS })
    }

    @Test fun detectsCorruptedAndNonElfLibraries() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64, "lib/arm64-v8a/libfake.so" to "not an elf".toByteArray()), stored = true))
        // Flip one byte inside the stored data of libsample.so so the CRC no longer matches.
        val bytes = apk.readBytes()
        val idx = indexOf(bytes, arm64.copyOfRange(0, 64)) + 2000
        bytes[idx] = (bytes[idx].toInt() xor 0xFF).toByte()
        apk.writeBytes(bytes)
        val r = NativeLibExtractor().extract(apk, File(dir, "out"))
        val sample = r.libraries.first { it.entry.fileName == "libsample.so" }
        assertFalse(sample.crcVerified)
        assertTrue(r.issues.any { it.type == IssueType.CORRUPTED && it.message.contains("CRC-32") })
        assertTrue(r.issues.any { it.type == IssueType.NOT_ELF && it.path!!.endsWith("libfake.so") })
        assertNull(r.libraries.first { it.entry.fileName == "libfake.so" }.elf)
    }

    @Test fun extractsFromXapkNestedSplits() {
        val base = zipBytes(listOf(manifest, "classes.dex" to ByteArray(8)))
        val split = zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64))
        val xapk = file("bundle.xapk", zipBytes(listOf("manifest.json" to "{}".toByteArray(), "com.example.apk" to base, "config.arm64_v8a.apk" to split)))
        val out = File(dir, "out")
        val r = NativeLibExtractor().extract(xapk, out)
        assertEquals(ContainerType.XAPK, r.containerType)
        assertEquals(listOf("com.example.apk", "config.arm64_v8a.apk"), r.nestedContainers)
        val lib = r.libraries.single()
        assertEquals("config.arm64_v8a.apk", lib.entry.container)
        assertEquals("config.arm64_v8a.apk/lib/arm64-v8a/libsample.so", lib.outputRelativePath)
        assertTrue(File(out, lib.outputRelativePath!!).exists())
        assertTrue(lib.hashVerified && lib.crcVerified)
        assertFalse("temp files must be cleaned", out.listFiles()!!.any { it.name.startsWith(".nlre_tmp") })
    }

    @Test fun extractsFromApksAndReportsDuplicates() {
        val base = zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64))
        val split = zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64))
        val apks = file("set.apks", zipBytes(listOf("toc.pb" to ByteArray(4), "splits/base-master.apk" to base, "splits/base-arm64_v8a.apk" to split)))
        val r = NativeLibExtractor().extract(apks, File(dir, "out"))
        assertEquals(ContainerType.APKS, r.containerType)
        assertEquals(2, r.libraries.size)
        val dup = r.issues.single { it.type == IssueType.DUPLICATE }
        assertTrue(dup.message.contains("identical content"))
        assertEquals(2, r.libraries.map { it.outputRelativePath }.distinct().size)
    }

    @Test fun extractsFromAppBundleModules() {
        val aab = file("app.aab", zipBytes(listOf("BundleConfig.pb" to ByteArray(4), "base/manifest/AndroidManifest.xml" to ByteArray(4),
            "base/lib/arm64-v8a/libsample.so" to arm64, "feature/lib/x86_64/libsample.so" to x8664)))
        val r = NativeLibExtractor().extract(aab, File(dir, "out"))
        assertEquals(ContainerType.AAB, r.containerType)
        assertEquals(listOf("arm64-v8a", "x86_64"), r.abis)
        assertEquals(setOf("base/lib/arm64-v8a/libsample.so", "feature/lib/x86_64/libsample.so"), r.libraries.map { it.outputRelativePath }.toSet())
    }

    @Test fun flagsNonStandardLocationsAndUnknownAbis() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "assets/hidden.so" to arm64, "lib/mips/libold.so" to arm64)))
        val r = NativeLibExtractor().extract(apk, File(dir, "out"))
        assertTrue(r.issues.any { it.type == IssueType.NON_STANDARD_LOCATION && it.path == "assets/hidden.so" })
        assertTrue(r.issues.any { it.type == IssueType.UNSUPPORTED_ABI && it.path == "lib/mips/libold.so" })
    }

    @Test fun rejectsPathTraversal() {
        val apk = file("evil.apk", zipBytes(listOf(manifest, "lib/../../../escaped.so" to arm64)))
        val out = File(dir, "deep/out")
        try {
            val r = NativeLibExtractor().extract(apk, out)
            assertTrue(r.issues.any { it.type == IssueType.UNSAFE_PATH })
            assertNull(r.libraries.single().outputRelativePath)
        } catch (e: ArchiveException) {
            // Some platforms (Android 14+) refuse to open such archives at all — also safe.
        }
        assertFalse(File(dir, "escaped.so").exists())
        assertFalse(File(dir, "deep/escaped.so").exists())
        assertTrue(NativeLibExtractor.isSafeEntryName("lib/arm64-v8a/libx.so"))
        listOf("/abs.so", "../x.so", "a/../../x.so", "C:/x.so", "a\\..\\x.so").forEach { assertFalse(it, NativeLibExtractor.isSafeEntryName(it)) }
    }

    @Test fun invalidArchiveFailsWithClearError() {
        val bad = file("broken.apk", "PK\u0003\u0004 this is not really a zip".toByteArray())
        try { NativeLibExtractor().extract(bad, File(dir, "out")); fail("expected ArchiveException") }
        catch (e: ArchiveException) { assertTrue(e.message!!.contains("ZIP") || e.message!!.contains("archive")) }
        val truncated = zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64)).let { it.copyOf(it.size / 2) }
        try { NativeLibExtractor().extract(file("trunc.apk", truncated), File(dir, "out2")); fail("expected ArchiveException") }
        catch (_: ArchiveException) { }
    }

    @Test fun directElfInputIsAnalyzedWithoutExtraction() {
        val so = file("libsample.so", arm64)
        val r = NativeLibExtractor().extract(so, File(dir, "out"))
        assertEquals(ContainerType.ELF, r.containerType)
        assertEquals(1, r.libraries.size)
        assertNotNull(r.libraries[0].elf)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun scanListsWithoutWriting() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64, "lib/x86/libsample.so" to x86)))
        val list = NativeLibExtractor().scan(apk)
        assertEquals(listOf("lib/arm64-v8a/libsample.so", "lib/x86/libsample.so"), list.map { it.entryPath })
        assertEquals(arm64.size.toLong(), list[0].size)
    }

    @Test fun formatDetection() {
        assertEquals(ContainerType.ELF, FormatDetector.detect(file("a.bin", arm64)))
        assertEquals(ContainerType.DEX, FormatDetector.detect(file("c.dex", "dex\n035\u0000".toByteArray())))
        assertEquals(ContainerType.UNKNOWN, FormatDetector.detect(file("r.bin", ByteArray(32) { 7 })))
        assertEquals(ContainerType.JAR, FormatDetector.detect(file("x.jar", zipBytes(listOf("META-INF/MANIFEST.MF" to ByteArray(1), "a/B.class" to ByteArray(1))))))
        assertEquals(ContainerType.ZIP, FormatDetector.detect(file("x.zip", zipBytes(listOf("readme.txt" to ByteArray(1))))))
        assertEquals(ContainerType.APK, FormatDetector.detect(file("x.zip2", zipBytes(listOf(manifest)))))
    }

    @Test fun reportsAreReproducible() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64)))
        val a = NativeLibExtractor().extract(apk, File(dir, "o1"))
        val b = NativeLibExtractor().extract(apk, File(dir, "o2"))
        assertEquals(ExtractionReportWriter.toJson(a), ExtractionReportWriter.toJson(b))
        assertEquals(ExtractionReportWriter.toText(a), ExtractionReportWriter.toText(b))
        val csv = ExtractionReportWriter.toCsv(a).lines()
        assertTrue(csv[0].startsWith("path,abi,size"))
        assertTrue(csv[1].startsWith("lib/arm64-v8a/libsample.so,arm64-v8a,"))
    }

    @Test fun cancellationStopsAndPropagates() {
        val apk = file("app.apk", zipBytes(listOf(manifest, "lib/arm64-v8a/libsample.so" to arm64)))
        try {
            NativeLibExtractor().extract(apk, File(dir, "out"), checkCancelled = { throw java.util.concurrent.CancellationException("stop") })
            fail("expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        error("pattern not found")
    }
}
