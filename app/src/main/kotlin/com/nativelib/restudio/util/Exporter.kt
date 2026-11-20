package com.nativelib.restudio.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Writes exports through the Storage Access Framework. Source files are only read. */
object Exporter {

    suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("Cannot write to $uri")
    }

    suspend fun copyFile(context: Context, src: File, uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { os -> src.inputStream().use { it.copyTo(os, 256 * 1024) } }
            ?: throw IOException("Cannot write to $uri")
    }

    /**
     * Copies [relativePaths] (relative to [root]) into the chosen SAF folder, recreating sub-directories,
     * inside a new folder named [folderName]. Returns the number of files written.
     */
    suspend fun exportTree(context: Context, treeUri: Uri, folderName: String, root: File, relativePaths: List<String>): Int =
        withContext(Dispatchers.IO) {
            val base = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Folder is not accessible")
            val top = base.findFile(folderName)?.takeIf { it.isDirectory } ?: base.createDirectory(folderName)
                ?: throw IOException("Cannot create folder $folderName")
            var count = 0
            for (rel in relativePaths) {
                val parts = rel.split('/').filter { it.isNotEmpty() }
                var dir = top
                for (p in parts.dropLast(1)) {
                    dir = dir.findFile(p)?.takeIf { it.isDirectory } ?: dir.createDirectory(p) ?: throw IOException("Cannot create folder $p")
                }
                val name = parts.last()
                dir.findFile(name)?.delete()
                val doc = dir.createFile("application/octet-stream", name) ?: throw IOException("Cannot create $name")
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use { os -> File(root, rel).inputStream().use { it.copyTo(os, 256 * 1024) } }
                count++
            }
            count
        }
}
