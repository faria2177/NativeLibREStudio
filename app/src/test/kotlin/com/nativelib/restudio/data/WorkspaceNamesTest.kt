package com.nativelib.restudio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNamesTest {
    @Test fun importableNames() {
        listOf("a.apk", "B.XAPK", "x.apks", "y.aab", "z.zip", "lib.jar", "classes.dex", "libfoo.so", "libc.so.6", "blob.bin", "core.elf")
            .forEach { assertTrue(it, Workspace.isImportableName(it)) }
        listOf("notes.txt", "image.png", "so", "archive.tar").forEach { assertFalse(it, Workspace.isImportableName(it)) }
    }

    @Test fun sanitizeRemovesPathAndReservedCharacters() {
        assertEquals("_.._etc_passwd", Workspace.sanitize("/../etc/passwd"))
        assertEquals("a_b_c.apk", Workspace.sanitize("a/b\\c.apk"))
        assertEquals("imported.bin", Workspace.sanitize("..."))
        assertEquals("hidden.so", Workspace.sanitize(".hidden.so"))
    }
}
