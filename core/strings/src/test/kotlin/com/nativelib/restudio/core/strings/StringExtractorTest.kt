package com.nativelib.restudio.core.strings

import com.nativelib.restudio.core.common.ByteArraySource
import com.nativelib.restudio.core.common.hexDump
import com.nativelib.restudio.core.elf.ElfParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StringExtractorTest {
    private fun res(name: String): ByteArray = java.util.Base64.getMimeDecoder().decode(
        requireNotNull(javaClass.getResourceAsStream("/elf/$name.b64")) { "missing $name" }.use { it.readBytes() })

    private val asciiOnly = StringOptions(encodings = setOf(StringEncoding.ASCII))

    /** Independent reference: the classic `strings` algorithm (printable ASCII + tab, min length). */
    private fun reference(b: ByteArray, min: Int): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>(); val sb = StringBuilder(); var start = -1
        for (i in 0..b.size) {
            val c = if (i < b.size) b[i].toInt() and 0xFF else -1
            if (c == 9 || c in 0x20..0x7E) { if (start < 0) start = i; sb.append(c.toChar()) }
            else { if (sb.length >= min) out += start.toLong() to sb.toString(); sb.setLength(0); start = -1 }
        }
        return out
    }

    @Test fun asciiMatchesReferenceOnAllSamples() {
        for (f in listOf("libsample_arm64.so", "libsample_armv7.so", "libsample_x86.so", "libsample_x86_64.so", "libstripped_arm64.so")) {
            val b = res(f)
            for (min in listOf(4, 6, 10)) {
                val r = StringExtractor(asciiOnly.copy(minLength = min)).extract(ByteArraySource(b))
                assertEquals("$f min=$min", reference(b, min), r.strings.map { it.offset to it.text })
            }
        }
    }

    @Test fun knownStringsWithOffsetsSectionsAndAddresses() {
        val b = res("libsample_arm64.so")
        val elf = ElfParser().parse(ByteArraySource(b))
        val r = StringExtractor().extract(ByteArraySource(b), elf)
        fun find(t: String) = r.strings.single { it.text == t }
        find("https://api.example.com/v1/data").let {
            assertEquals(0x4c2L, it.offset); assertEquals(".rodata", it.section); assertEquals(0x4c2L, it.vaddr)
            assertTrue(StringCategory.URL in it.categories)
        }
        find("hello from native").let { assertEquals(0x4b0L, it.offset); assertEquals(".rodata", it.section) }
        find("libfoo.so").let { assertEquals(0x4e2L, it.offset); assertTrue(StringCategory.FILE_PATH in it.categories) }
        // present in both .dynstr and .strtab
        val jni = r.strings.filter { it.text == "Java_com_example_Native_add" }
        assertEquals(listOf(".dynstr", ".strtab"), jni.map { it.section })
        assertTrue(jni.all { StringCategory.FUNCTION_NAME in it.categories })
        // non-allocated section: no virtual address
        assertNull(r.strings.first { it.section == ".comment" }.vaddr)
        assertFalse(r.truncated)
    }

    @Test fun chunkBoundariesDoNotSplitOrDuplicate() {
        val b = res("libsample_x86_64.so")
        val whole = StringExtractor().extract(ByteArraySource(b)).strings
        for (chunk in listOf(16, 17, 31, 64, 333)) {
            val parts = StringExtractor(StringOptions(chunkSize = chunk)).extract(ByteArraySource(b)).strings
            assertEquals("chunk=$chunk", whole.size, parts.size)
            assertEquals("chunk=$chunk", whole, parts)
        }
    }

    @Test fun utf16LittleAndBigEndianAtBothAlignments() {
        val le = "Wide string LE".toByteArray(Charsets.UTF_16LE)
        val be = "Wide string BE".toByteArray(Charsets.UTF_16BE)
        // Realistic layout: 2-byte aligned, NUL-terminated wide strings.
        val data = byteArrayOf(0, 0) + le + byteArrayOf(0, 0, 0, 0) + be + byteArrayOf(0, 0)
        val r = StringExtractor(StringOptions(chunkSize = 16, encodings = StringEncoding.entries.toSet())).extract(ByteArraySource(data)).strings
        val l = r.single { it.encoding == StringEncoding.UTF16LE }
        assertEquals("Wide string LE", l.text); assertEquals(2L, l.offset); assertEquals(28, l.byteLength)
        val bb = r.single { it.encoding == StringEncoding.UTF16BE }
        assertEquals("Wide string BE", bb.text); assertEquals((2 + le.size + 4).toLong(), bb.offset)
        // Odd alignment and a non-zero prefix byte are still found.
        val odd = StringExtractor().extract(ByteArraySource(byteArrayOf(1) + le + byteArrayOf(0, 0))).strings
        assertEquals(listOf("Wide string LE" to 1L), odd.map { it.text to it.offset })
    }

    @Test fun utf8AndNonLatinUtf16() {
        val bn = "বাংলা ভাষা"
        val data = byteArrayOf(0) + "নমস্কার world".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0) + bn.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        val r = StringExtractor().extract(ByteArraySource(data)).strings
        assertEquals("নমস্কার world", r.single { it.encoding == StringEncoding.UTF8 }.text)
        assertEquals(bn, r.single { it.encoding == StringEncoding.UTF16LE }.text)
    }

    @Test fun asciiTextIsNotMisreadAsUtf16() {
        val data = "abcdefghijklmnopqrstuvwxyz0123456789".toByteArray()
        val r = StringExtractor().extract(ByteArraySource(data)).strings
        assertEquals(listOf(StringEncoding.ASCII), r.map { it.encoding })
    }

    @Test fun invalidUtf8IsNotAccepted() {
        // overlong encoding of '/', lone continuation bytes, surrogate encoded in UTF-8
        val data = "abcd".toByteArray() + byteArrayOf(0xC0.toByte(), 0xAF.toByte()) + "efgh".toByteArray() +
            byteArrayOf(0x80.toByte()) + byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) + "ijkl".toByteArray()
        val r = StringExtractor().extract(ByteArraySource(data)).strings
        assertEquals(listOf("abcd", "efgh", "ijkl"), r.map { it.text })
    }

    @Test fun minimumLength() {
        val data = ("ab\u0000abc\u0000abcd\u0000" + "x".repeat(25)).toByteArray()
        assertEquals(listOf("abcd", "x".repeat(25)), StringExtractor(StringOptions(minLength = 4)).extract(ByteArraySource(data)).strings.map { it.text })
        assertEquals(listOf("abc", "abcd", "x".repeat(25)), StringExtractor(StringOptions(minLength = 3)).extract(ByteArraySource(data)).strings.map { it.text })
    }

    @Test fun maxLengthSplitsLongRuns() {
        val data = "x".repeat(25).toByteArray()
        val r = StringExtractor(StringOptions(minLength = 4, maxLength = 10)).extract(ByteArraySource(data)).strings
        assertEquals(listOf(0L, 10L, 20L), r.map { it.offset })
        assertEquals(listOf(10, 10, 5), r.map { it.text.length })
    }

    @Test fun resultLimitTruncates() {
        val data = (0 until 100).joinToString("\u0000") { "str$it" }.toByteArray()
        val r = StringExtractor(StringOptions(maxResults = 10, chunkSize = 64)).extract(ByteArraySource(data))
        assertTrue(r.truncated)
        assertEquals(10, r.strings.size)
    }

    @Test fun cancellationPropagates() {
        try {
            StringExtractor().extract(ByteArraySource(ByteArray(100)), checkCancelled = { throw java.util.concurrent.CancellationException() })
            fail()
        } catch (_: java.util.concurrent.CancellationException) { }
    }

    @Test fun classification() {
        fun c(s: String) = StringClassifier.classify(s)
        assertTrue(StringCategory.URL in c("connect to https://example.org/api?x=1"))
        assertTrue(StringCategory.IP_ADDRESS in c("server 192.168.1.20:8080"))
        assertFalse(StringCategory.IP_ADDRESS in c("version 1.2.3.4.5"))
        assertTrue(StringCategory.EMAIL in c("contact dev@example.com"))
        assertTrue(StringCategory.FILE_PATH in c("/system/lib64/libc.so"))
        assertTrue(StringCategory.FILE_PATH in c("/data/local/tmp/x"))
        assertTrue(StringCategory.CLASS_NAME in c("com/example/app/MainActivity"))
        assertTrue(StringCategory.CLASS_NAME in c("Ljava/lang/String;"))
        assertTrue(StringCategory.CLASS_NAME in c("com.example.app.MainActivity"))
        assertTrue(StringCategory.FUNCTION_NAME in c("Java_com_example_Native_add"))
        assertTrue(StringCategory.FUNCTION_NAME in c("_ZN3foo3barEv"))
        assertTrue(StringCategory.FORMAT_STRING in c("value=%d name=%s"))
        assertTrue(StringCategory.ERROR_MESSAGE in c("Failed to open file"))
        assertTrue(StringCategory.DEBUG_MESSAGE in c("[debug] entering loop"))
        assertTrue(StringCategory.CONFIG_VALUE in c("ro.build.version.sdk"))
        assertTrue(StringCategory.CONFIG_VALUE in c("timeout=30"))
        assertTrue(c("hello from native").isEmpty())
    }

    @Test fun reportsAreDeterministicAndEscaped() {
        val data = "tab\there\u0000\"quoted\", value\u0000".toByteArray()
        val r = StringExtractor().extract(ByteArraySource(data))
        val txt = StringReport.toText("t", r.strings, r.truncated)
        assertTrue(txt.contains("tab\\there"))
        val csv = StringReport.toCsv(r.strings).lines()
        assertEquals("offset,vaddr,section,encoding,byte_length,categories,text", csv[0])
        assertTrue(csv[2].endsWith("\"\"\"quoted\"\", value\""))
        assertEquals(StringReport.toJson("t", r.strings, false), StringReport.toJson("t", StringExtractor().extract(ByteArraySource(data)).strings, false))
    }

    @Test fun hexDumpFormat() {
        val d = hexDump("ABCDEFGHIJKLMNOPQR".toByteArray(), 0x10)
        val lines = d.lines()
        assertTrue(lines[0].startsWith("00000010   41 42"))
        assertTrue(lines[0].endsWith("|ABCDEFGHIJKLMNOP|"))
        assertTrue(lines[1].startsWith("00000020   51 52"))
    }
}
