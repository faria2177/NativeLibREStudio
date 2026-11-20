package com.nativelib.restudio.core.elf

import com.nativelib.restudio.core.common.ByteArraySource
import com.nativelib.restudio.core.common.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests against real shared objects built with clang 18 / lld for four Android ABIs
 * (see TEST_SAMPLES.md). Expected values were cross-checked with llvm-readelf.
 */
class ElfParserTest {

    private fun bytes(name: String): ByteArray =
        // Samples are stored base64-encoded so the repository stays text-only.
        java.util.Base64.getMimeDecoder().decode(
            requireNotNull(javaClass.getResourceAsStream("/elf/$name.b64")) { "missing test resource $name" }.use { it.readBytes() })

    private fun parse(name: String) = ElfParser().parse(ByteArraySource(bytes(name)))

    private val exported = setOf("weak_fn", "Java_com_example_Native_add", "g_counter", "exported_fn", "api_url")
    private val imported = setOf("puts", "dlopen")

    private data class Expect(val file: String, val machine: Int, val is64: Boolean, val abi: String, val buildId: String, val sha: String)

    private val samples = listOf(
        Expect("libsample_arm64.so", ElfConstants.EM_AARCH64, true, "arm64-v8a", "b2abba2cd21105cca0c8e0cad6475161f5cef41b", "c2a8a280d9cd2c9db86d66572d2bc17fdbf210c1544fbe679cd421cd44ee4d95"),
        Expect("libsample_armv7.so", ElfConstants.EM_ARM, false, "armeabi-v7a", "c884f9c70f83c554062639fbcaf78474ba88e6e6", "9cf90bc4ba050322c4a1ae0e3ff2fd75e202d9bca9cd9e7a77fba9e16e866bef"),
        Expect("libsample_x86.so", ElfConstants.EM_386, false, "x86", "14b10f0827aaaaf6994ebc08fe7de6935597c9a1", "110ce0d56c900dd71a7ce571b50c8bb6bceec42f5fdbc726a2c60bc84ed95b82"),
        Expect("libsample_x86_64.so", ElfConstants.EM_X86_64, true, "x86_64", "e2094d20738cfb09b81b3281a028d1e56f3697c8", "b9f46bc0abe2bc90b94a328719abcd3db9a8116f914b370d7db150f870bc7ca5"),
    )

    @Test fun samplesAreUnchanged() {
        samples.forEach { assertEquals(it.file, it.sha, Hashing.sha256(bytes(it.file))) }
    }

    @Test fun headerFieldsMatchReadelf() {
        for (s in samples) {
            val elf = parse(s.file)
            val h = elf.header
            assertEquals(s.file, s.machine, h.machine)
            assertEquals(s.file, s.is64, h.is64)
            assertEquals(s.file, Endianness.LITTLE, h.ident.endianness)
            assertEquals(s.file, ElfConstants.ET_DYN, h.type)
            assertEquals(s.file, s.abi, ElfConstants.androidAbiFor(h.machine, h.is64))
            assertEquals(s.file, 10, h.phNum)
            assertEquals(s.file, 22, h.shNum)
            assertEquals(s.file, 10, elf.programHeaders.size)
            assertEquals(s.file, 22, elf.sections.size)
            assertEquals(s.file, s.buildId, elf.buildId)
            assertTrue(s.file + " warnings: " + elf.warnings, elf.warnings.none { it.severity != WarningSeverity.INFO })
        }
    }

    @Test fun sectionNamesAreResolved() {
        val names = parse("libsample_arm64.so").sections.map { it.name }
        listOf(".dynsym", ".dynstr", ".text", ".dynamic", ".symtab", ".strtab", ".shstrtab", ".note.gnu.build-id").forEach {
            assertTrue("missing $it in $names", it in names)
        }
        val text = parse("libsample_arm64.so").sectionByName(".text")!!
        assertEquals(0x457cL, text.addr); assertEquals(0x57cL, text.offset); assertEquals(0x68L, text.size)
        assertEquals("AX", text.flagsString)
    }

    @Test fun dynamicSectionAndDependencies() {
        for (s in samples) {
            val elf = parse(s.file)
            assertEquals(listOf("libc.so"), elf.neededLibraries)
            assertEquals("libsample.so", elf.soname)
            assertNull(elf.rpath); assertNull(elf.runpath)
        }
    }

    @Test fun symbolsAreClassified() {
        for (s in samples) {
            val elf = parse(s.file)
            assertEquals(s.file, exported, elf.exportedSymbols.map { it.name }.toSet())
            assertEquals(s.file, imported, elf.importedSymbols.map { it.name }.toSet())
            assertTrue(elf.dynamicSymbols.first { it.name == "weak_fn" }.isWeak)
            assertTrue(elf.dynamicSymbols.first { it.name == "exported_fn" }.isFunction)
            assertTrue(elf.dynamicSymbols.first { it.name == "g_counter" }.isObject)
            assertFalse(elf.isStripped)
            assertTrue(elf.staticSymbols.any { it.name == "sample.c" && it.type == ElfConstants.STT_FILE })
        }
        val arm64 = parse("libsample_arm64.so")
        val fn = arm64.dynamicSymbols.first { it.name == "exported_fn" }
        assertEquals(0x45a4L, fn.value); assertEquals(64L, fn.size)
    }

    @Test fun relocationsMatchReadelf() {
        val arm64 = parse("libsample_arm64.so")
        assertEquals(5, arm64.relocations.size)
        assertEquals(listOf("weak_fn", "puts", "dlopen"), arm64.relocations.filter { it.typeName == "R_AARCH64_JUMP_SLOT" }.map { it.symbolName })
        assertEquals(1, arm64.relocations.count { it.typeName == "R_AARCH64_RELATIVE" })
        val arm = parse("libsample_armv7.so")
        assertEquals(5, arm.relocations.size)
        assertEquals(0x84d8L, arm.relocations.first { it.symbolName == "weak_fn" }.offset)
        assertTrue(arm.relocations.all { it.addend == null }) // REL, not RELA
        assertEquals(3, parse("libsample_x86_64.so").relocations.count { it.typeName == "R_X86_64_JUMP_SLOT" })
        assertEquals(3, parse("libsample_x86.so").relocations.count { it.typeName == "R_386_JMP_SLOT" })
    }

    @Test fun hardeningFacts() {
        val sec = parse("libsample_arm64.so").security
        assertFalse(sec.hasRwxSegment)
        assertEquals(true, sec.nxStack)
        assertEquals(RelroLevel.FULL, sec.relro)
        assertTrue(sec.bindNow)
        assertTrue(sec.supports16KbPages)
        assertEquals(16384L, sec.maxLoadAlignment)
    }

    @Test fun strippedBinaryReportsMissingInformation() {
        val elf = parse("libstripped_arm64.so")
        assertTrue(elf.isStripped)
        assertTrue(elf.staticSymbols.isEmpty())
        assertEquals(exported, elf.exportedSymbols.map { it.name }.toSet())
        assertTrue(elf.symbolAvailabilityNotes().any { it.contains("stripped") })
    }

    @Test fun gnuSymbolVersions() {
        val elf = parse("libversioned_arm64.so")
        assertEquals(listOf("libversioned.so", "LIBSAMPLE_1.0"), elf.versionDefinitions)
        val add = elf.dynamicSymbols.first { it.name == "Java_com_example_Native_add" }
        assertEquals("LIBSAMPLE_1.0", add.version)
        assertEquals("Java_com_example_Native_add@@LIBSAMPLE_1.0", add.displayName)
        assertNull(elf.dynamicSymbols.first { it.name == "puts" }.version)
        assertEquals(setOf("Java_com_example_Native_add", "g_counter", "exported_fn"), elf.exportedSymbols.map { it.name }.toSet())
    }

    @Test fun dynamicSymbolsRecoveredWithoutSectionHeaders() {
        val b = bytes("libsample_arm64.so")
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(40, 0L)               // e_shoff = 0
        bb.putShort(60, 0); bb.putShort(62, 0) // e_shnum = 0, e_shstrndx = 0
        val elf = ElfParser().parse(ByteArraySource(b))
        assertTrue(elf.sections.isEmpty())
        assertEquals(listOf("libc.so"), elf.neededLibraries)
        assertEquals(exported, elf.exportedSymbols.map { it.name }.toSet())
        assertEquals(imported, elf.importedSymbols.map { it.name }.toSet())
        assertEquals("b2abba2cd21105cca0c8e0cad6475161f5cef41b", elf.buildId) // from PT_NOTE
    }

    @Test fun rejectsNonElf() {
        assertThrowsFormat(ByteArray(64) { 0x41 })
        assertThrowsFormat(ByteArray(3))
        assertThrowsFormat(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 9, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    }

    @Test fun truncatedFilesNeverCrash() {
        val full = bytes("libsample_arm64.so")
        for (len in listOf(16, 40, 63, 64, 100, 600, 1500, 2700, full.size - 1)) {
            try {
                val elf = ElfParser().parse(ByteArraySource(full.copyOf(len)))
                assertTrue("len=$len should report problems", elf.warnings.any { it.severity == WarningSeverity.ERROR } || len >= full.size - 1)
            } catch (e: ElfFormatException) {
                // acceptable: header itself unreadable
            }
        }
    }

    @Test fun corruptedHeaderValuesAreContained() {
        val b = bytes("libsample_x86_64.so")
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putLong(40, Long.MAX_VALUE / 2)  // absurd e_shoff
        bb.putShort(56, 0xfff0.toShort())   // absurd e_phnum
        val elf = ElfParser().parse(ByteArraySource(b))
        assertTrue(elf.sections.isEmpty())
        assertTrue(elf.programHeaders.isEmpty())
        assertTrue(elf.warnings.count { it.severity == WarningSeverity.ERROR } >= 2)
    }

    @Test fun randomMutationsNeverThrowUnexpectedExceptions() {
        val base = bytes("libsample_armv7.so")
        val rnd = java.util.Random(1234)
        repeat(500) {
            val b = base.copyOf()
            repeat(1 + rnd.nextInt(16)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            try { ElfParser().parse(ByteArraySource(b)) } catch (_: ElfFormatException) { }
        }
    }

    @Test fun reportsAreDeterministic() {
        val elf = parse("libsample_arm64.so")
        assertEquals(ElfReport.toText(elf, "a", "x"), ElfReport.toText(parse("libsample_arm64.so"), "a", "x"))
        val json = ElfReport.toJson(elf, "a", "x")
        assertTrue(json.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("\"machine\": \"AArch64 (ARM64)\""))
        assertTrue(json.contains("\"needed\": [\n    \"libc.so\"\n  ]"))
    }

    @Test fun bigEndianHeaderIsDecoded() {
        val b = ByteArray(64)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 2; b[5] = 2; b[6] = 1
        bb.putShort(16, 3); bb.putShort(18, 21); bb.putInt(20, 1); bb.putLong(24, 0x1234L); bb.putShort(52, 64)
        val elf = ElfParser().parse(ByteArraySource(b))
        assertEquals(Endianness.BIG, elf.header.ident.endianness)
        assertEquals(21, elf.header.machine)
        assertEquals(0x1234L, elf.header.entry)
        assertNotNull(elf.warnings.firstOrNull { it.message.contains("section header") })
    }

    private fun assertThrowsFormat(b: ByteArray) {
        try { ElfParser().parse(ByteArraySource(b)); fail("expected ElfFormatException") } catch (_: ElfFormatException) { }
    }
}
