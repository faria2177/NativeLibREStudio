package com.nativelib.restudio.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    @Test fun tagParsing() {
        assertEquals(12, UpdateLogic.buildNumberFromTag("build-12"))
        assertNull(UpdateLogic.buildNumberFromTag("v1.0"))
        assertNull(UpdateLogic.buildNumberFromTag("build-"))
    }

    @Test fun newerOnlyWhenBuildNumberIsHigher() {
        assertTrue(UpdateLogic.isNewer("build-5", 4))
        assertFalse(UpdateLogic.isNewer("build-5", 5))
        assertFalse(UpdateLogic.isNewer("build-3", 5))
        assertFalse(UpdateLogic.isNewer("latest", 1))
    }

    @Test fun assetMatchesInstalledVariant() {
        val assets = listOf("NativeLibREStudio-7-debug.apk", "NativeLibREStudio-7-release.apk", "SHA256SUMS.txt")
        assertEquals("NativeLibREStudio-7-debug.apk", UpdateLogic.chooseApk(assets, "com.nativelib.restudio.debug"))
        assertEquals("NativeLibREStudio-7-release.apk", UpdateLogic.chooseApk(assets, "com.nativelib.restudio"))
        assertNull(UpdateLogic.chooseApk(listOf("SHA256SUMS.txt"), "com.nativelib.restudio"))
    }

    @Test fun sha256SumsParsing() {
        val h1 = "a".repeat(64); val h2 = "B".repeat(64)
        val m = UpdateLogic.parseSha256Sums("$h1  dist/App-1-debug.apk\n$h2 *dist/App-1-release.apk\ngarbage\n")
        assertEquals(h1, m["App-1-debug.apk"])
        assertEquals("b".repeat(64), m["App-1-release.apk"])
        assertEquals(2, m.size)
    }
}
