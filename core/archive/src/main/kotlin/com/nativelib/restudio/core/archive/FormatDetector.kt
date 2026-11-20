package com.nativelib.restudio.core.archive

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** Identifies input type from magic bytes and (for ZIP-based formats) the archive's directory. */
object FormatDetector {

    fun detect(file: File): ContainerType {
        val magic = ByteArray(8)
        val n = RandomAccessFile(file, "r").use { it.read(magic) }
        if (n >= 4 && magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte())
            return ContainerType.ELF
        if (n >= 4 && magic[0] == 'd'.code.toByte() && magic[1] == 'e'.code.toByte() && magic[2] == 'x'.code.toByte() && magic[3] == '\n'.code.toByte())
            return ContainerType.DEX
        if (n >= 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() && (magic[2].toInt() == 3 || magic[2].toInt() == 5 || magic[2].toInt() == 7))
            return try { ZipFile(file).use { classifyZip(it.entries().asSequence().map { e -> e.name }.toList(), file.name) } }
            catch (_: Exception) { ContainerType.ZIP } // let the extractor report the precise ZIP error
        return ContainerType.UNKNOWN
    }

    fun classifyZip(names: List<String>, fileName: String = ""): ContainerType {
        val set = names.toHashSet()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val hasApks = names.any { it.endsWith(".apk", ignoreCase = true) }
        return when {
            "BundleConfig.pb" in set || (ext == "aab" && names.any { it.endsWith("/manifest/AndroidManifest.xml") }) -> ContainerType.AAB
            "AndroidManifest.xml" in set -> ContainerType.APK
            "manifest.json" in set && hasApks -> ContainerType.XAPK
            "toc.pb" in set || (hasApks && (ext == "apks" || ext == "apkm" || ext == "xapk" || names.any { it.startsWith("splits/") })) -> ContainerType.APKS
            hasApks -> ContainerType.APKS
            "META-INF/MANIFEST.MF" in set && (ext == "jar" || names.any { it.endsWith(".class") }) -> ContainerType.JAR
            else -> ContainerType.ZIP
        }
    }
}
