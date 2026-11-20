package com.nativelib.restudio.data

import android.net.Uri
import com.nativelib.restudio.core.archive.ExtractionReport
import com.nativelib.restudio.core.archive.IssueSeverity
import com.nativelib.restudio.core.archive.NativeLibExtractor
import com.nativelib.restudio.core.archive.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Progress events emitted while importing and analysing a file. */
sealed interface Progress {
    val fileName: String
    data class Copying(override val fileName: String, val done: Long, val total: Long) : Progress
    data class Extracting(override val fileName: String, val done: Int, val total: Int, val current: String) : Progress
}

class LoadedAnalysis(val record: AnalysisRecord, val report: ExtractionReport) {
    val extractedRoot: File get() = File(record.workspaceDir, "extracted")
    val inputFile: File get() = File(File(record.workspaceDir, "input"), record.inputFileName)
}

class AnalysisRepository(private val dao: AnalysisDao, private val workspace: Workspace) {
    private val cache = ConcurrentHashMap<Long, LoadedAnalysis>()

    fun observeHistory(): Flow<List<AnalysisRecord>> = dao.observeAll()

    /** Copies [uri] into a new workspace, extracts and verifies native libraries, stores a history record. */
    suspend fun importAndAnalyze(uri: Uri, onProgress: (Progress) -> Unit): LoadedAnalysis = withContext(Dispatchers.IO) {
        val name = workspace.displayName(uri)
        val dir = workspace.newAnalysisDir()
        try {
            val input = File(workspace.inputDir(dir), name)
            workspace.copyIn(uri, input) { d, t -> onProgress(Progress.Copying(name, d, t)) }
            val report = runExtraction(input, workspace.extractedDir(dir), name, onProgress)
            val record = AnalysisRecord(
                displayName = name, containerType = report.containerType.label, sizeBytes = report.sourceSize,
                sha256 = report.sourceSha256, abis = report.abis.joinToString(", "), libraryCount = report.libraries.size,
                verifiedCount = report.verifiedCount, issueCount = report.issues.size,
                errorCount = report.issues.count { it.severity == IssueSeverity.ERROR }, createdAt = System.currentTimeMillis(),
                workspaceDir = dir.absolutePath, inputFileName = name,
            )
            val id = dao.insert(record)
            LoadedAnalysis(record.copy(id = id), report).also { cache[id] = it }
        } catch (t: Throwable) {
            workspace.delete(dir.absolutePath) // never leave half-written workspaces behind
            throw t
        }
    }

    /** Returns a cached analysis, or re-analyses the stored input copy (results are deterministic). */
    suspend fun load(id: Long, onProgress: (Progress) -> Unit = {}): LoadedAnalysis? = withContext(Dispatchers.IO) {
        cache[id]?.let { return@withContext it }
        val record = dao.get(id) ?: return@withContext null
        val input = File(File(record.workspaceDir, "input"), record.inputFileName)
        if (!input.exists()) throw IllegalStateException("Stored copy of ${record.displayName} is missing; re-import the file.")
        val out = File(record.workspaceDir, "extracted")
        out.walkBottomUp().forEach { it.setWritable(true) }
        out.deleteRecursively()
        val report = runExtraction(input, out, record.displayName, onProgress)
        LoadedAnalysis(record, report).also { cache[id] = it }
    }

    suspend fun delete(record: AnalysisRecord) = withContext(Dispatchers.IO) {
        cache.remove(record.id)
        dao.delete(record.id)
        workspace.delete(record.workspaceDir)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cache.clear()
        dao.deleteAll()
        workspace.clearAll()
    }

    private suspend fun runExtraction(input: File, out: File, name: String, onProgress: (Progress) -> Unit): ExtractionReport {
        val ctx = coroutineContext
        return NativeLibExtractor().extract(
            input, out,
            progress = ProgressListener { d, t, cur -> onProgress(Progress.Extracting(name, d, t, cur)) },
            checkCancelled = { ctx.ensureActive() },
        )
    }
}
