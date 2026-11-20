package com.nativelib.restudio.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nativelib.restudio.NativeLibApp
import com.nativelib.restudio.core.archive.ArchiveException
import com.nativelib.restudio.core.archive.ContainerType
import com.nativelib.restudio.data.AnalysisRecord
import com.nativelib.restudio.data.LoadedAnalysis
import com.nativelib.restudio.data.Progress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface JobState {
    data object Idle : JobState
    data class Running(val progress: Progress?, val index: Int, val count: Int) : JobState
}

sealed interface NavEvent {
    data class OpenAnalysis(val id: Long) : NavEvent
    data class OpenElf(val id: Long, val libIndex: Int, val tab: Int = 0) : NavEvent
    /** libIndex -1 = the imported file itself (DEX, raw binaries). */
    data class OpenStrings(val id: Long, val libIndex: Int) : NavEvent
    data object OpenHistory : NavEvent
}

/** Where a single imported file should open once analysed (null = extraction report). */
enum class DirectTarget { ELF, SYMBOLS, STRINGS }

/** Activity-scoped state: import queue, progress, cancellation and history. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app as NativeLibApp
    private val repo = container.repository
    val workspace = container.workspace
    val settings = container.settings
    val updates = container.updates
    private var updateJob: Job? = null

    /** Automatic update check on launch (rate-limited inside UpdateManager). */
    fun autoCheckUpdates() { viewModelScope.launch { updates.autoCheckIfDue() } }
    fun checkUpdates() { viewModelScope.launch { updates.check(manual = true) } }
    fun downloadUpdate(info: com.nativelib.restudio.update.UpdateInfo) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch { updates.download(info) }
    }
    fun cancelUpdate() { updateJob?.cancel() }

    private val _job = MutableStateFlow<JobState>(JobState.Idle)
    val job: StateFlow<JobState> = _job
    private var running: Job? = null

    private val _nav = MutableSharedFlow<NavEvent>(extraBufferCapacity = 4)
    val nav = _nav.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val history: StateFlow<List<AnalysisRecord>> =
        repo.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Imports and analyses each URI in order. With a [target], a single raw ELF (or, for STRINGS, any
     * non-archive file) opens that tool directly; archives open their extraction report; several inputs open History.
     */
    fun importUris(uris: List<Uri>, target: DirectTarget? = null) {
        if (uris.isEmpty()) return
        if (running?.isActive == true) { _messages.tryEmit("An analysis is already running"); return }
        running = viewModelScope.launch {
            var last: LoadedAnalysis? = null
            var failures = 0
            try {
                uris.forEachIndexed { i, uri ->
                    _job.value = JobState.Running(null, i, uris.size)
                    try {
                        last = repo.importAndAnalyze(uri) { p -> _job.value = JobState.Running(p, i, uris.size) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ArchiveException) {
                        failures++; _messages.tryEmit("${workspace.displayName(uri)}: ${e.message}")
                    } catch (e: Exception) {
                        failures++; _messages.tryEmit("${workspace.displayName(uri)}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                val done = last
                when {
                    done == null -> Unit
                    uris.size > 1 -> { _messages.tryEmit("Analysed ${uris.size - failures} of ${uris.size} files"); _nav.tryEmit(NavEvent.OpenHistory) }
                    target == DirectTarget.STRINGS && !done.report.containerType.isArchive ->
                        _nav.tryEmit(NavEvent.OpenStrings(done.record.id, if (done.report.containerType == ContainerType.ELF) 0 else -1))
                    target != null && target != DirectTarget.STRINGS && done.report.containerType == ContainerType.ELF ->
                        _nav.tryEmit(NavEvent.OpenElf(done.record.id, 0, if (target == DirectTarget.SYMBOLS) com.nativelib.restudio.ui.screens.ElfTabs.SYMBOLS else 0))
                    else -> _nav.tryEmit(NavEvent.OpenAnalysis(done.record.id))
                }
            } catch (e: CancellationException) {
                _messages.tryEmit("Cancelled")
            } finally {
                _job.value = JobState.Idle
            }
        }
    }

    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { workspace.listImportable(treeUri) }
            if (files.isEmpty()) _messages.tryEmit("No supported files (APK, XAPK, APKS, AAB, ZIP, JAR, DEX, SO, ELF, BIN) found in that folder")
            else importUris(files)
        }
    }

    fun cancel() { running?.cancel() }

    fun delete(record: AnalysisRecord) = viewModelScope.launch { repo.delete(record) }

    fun clearAll() = viewModelScope.launch { repo.clearAll(); _messages.tryEmit("Workspace cleared") }

    fun message(text: String) { _messages.tryEmit(text) }
}
