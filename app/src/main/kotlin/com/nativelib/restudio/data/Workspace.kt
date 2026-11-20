package com.nativelib.restudio.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * App-private storage for analyses:
 *
 *   files/workspace/<timestamp>_<n>/input/<original name>   – read-only copy of the imported file
 *   files/workspace/<timestamp>_<n>/extracted/...           – extracted libraries (original structure)
 *
 * The user's original file is only ever read through ContentResolver; it is never modified.
 */
class Workspace(private val context: Context) {
    val root: File get() = File(context.filesDir, "workspace").apply { mkdirs() }

    fun newAnalysisDir(): File {
        var n = 0
        while (true) {
            val d = File(root, "${System.currentTimeMillis()}_${n++}")
            if (d.mkdirs()) return d
        }
    }

    fun inputDir(analysisDir: File) = File(analysisDir, "input").apply { mkdirs() }
    fun extractedDir(analysisDir: File) = File(analysisDir, "extracted")

    fun displayName(uri: Uri): String {
        val r: ContentResolver = context.contentResolver
        var name: String? = null
        runCatching {
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        return sanitize(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported.bin")
    }

    fun sizeOf(uri: Uri): Long {
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        }
        return size
    }

    /** Streams [uri] into [dest] (never loads it fully into memory), reporting progress in bytes. */
    suspend fun copyIn(uri: Uri, dest: File, onProgress: (Long, Long) -> Unit) {
        val total = sizeOf(uri)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { ins ->
            FileOutputStream(dest).use { os ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = ins.read(buf); if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 4 * 1024 * 1024) { onProgress(done, total); lastReport = done }
                }
                os.fd.sync()
                onProgress(done, total)
            }
        }
        dest.setReadOnly()
    }

    /** Lists importable files in a SAF tree (one level deep plus sub-folders, bounded). */
    fun listImportable(treeUri: Uri, maxFiles: Int = 500): List<Uri> {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = ArrayList<Uri>()
        fun walk(d: DocumentFile, depth: Int) {
            for (f in d.listFiles()) {
                if (out.size >= maxFiles) return
                if (f.isDirectory && depth < 4) walk(f, depth + 1)
                else if (f.isFile && isImportableName(f.name ?: "")) out += f.uri
            }
        }
        walk(rootDoc, 0)
        return out
    }

    fun delete(dir: String) { File(dir).let { d -> d.walkBottomUp().forEach { it.setWritable(true) }; d.deleteRecursively() } }

    fun clearAll() { root.walkBottomUp().forEach { it.setWritable(true) }; root.deleteRecursively(); root.mkdirs() }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        val IMPORTABLE_EXTENSIONS = setOf("apk", "xapk", "apks", "apkm", "aab", "zip", "jar", "dex", "elf", "so", "bin", "o")

        fun isImportableName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.substringAfterLast('.', "") in IMPORTABLE_EXTENSIONS || Regex(""".+\.so(\.\d+)+$""").matches(lower)
        }

        fun sanitize(name: String): String {
            val cleaned = name.replace(Regex("""[/\\\u0000:*?"<>|]"""), "_").trim().trimStart('.')
            return cleaned.ifEmpty { "imported.bin" }.take(180)
        }
    }
}
