package com.nativelib.restudio.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nativelib.restudio.NativeLibApp
import com.nativelib.restudio.core.archive.ExtractedLib
import com.nativelib.restudio.core.archive.ExtractionReportWriter
import com.nativelib.restudio.core.archive.IssueSeverity
import com.nativelib.restudio.core.common.humanBytes
import com.nativelib.restudio.data.LoadedAnalysis
import com.nativelib.restudio.data.Progress
import com.nativelib.restudio.ui.components.KeyValue
import com.nativelib.restudio.ui.components.Level
import com.nativelib.restudio.ui.components.MessageRow
import com.nativelib.restudio.ui.components.Pill
import com.nativelib.restudio.ui.components.SectionCard
import com.nativelib.restudio.ui.theme.Mono
import com.nativelib.restudio.util.Exporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface LoadState<out T> {
    data class Loading(val message: String) : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

class ExtractionViewModel(app: Application, private val id: Long) : AndroidViewModel(app) {
    private val repo = (app as NativeLibApp).repository
    private val _state = MutableStateFlow<LoadState<LoadedAnalysis>>(LoadState.Loading("Loading analysis…"))
    val state: StateFlow<LoadState<LoadedAnalysis>> = _state

    init {
        viewModelScope.launch {
            try {
                val a = repo.load(id) { p ->
                    _state.value = LoadState.Loading(when (p) {
                        is Progress.Copying -> "Copying…"
                        is Progress.Extracting -> "Re-verifying stored copy: ${p.done}/${p.total}"
                    })
                }
                _state.value = if (a == null) LoadState.Failed("Analysis not found") else LoadState.Loaded(a)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.value = LoadState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionScreen(id: Long, snackbar: SnackbarHostState, onBack: () -> Unit, onOpenElf: (Int) -> Unit, onOpenStrings: (Int) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ExtractionViewModel = viewModel(key = "extraction-$id") { ExtractionViewModel(app, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var pendingLib by remember { mutableStateOf<ExtractedLib?>(null) }
    val loaded = (state as? LoadState.Loaded<LoadedAnalysis>)?.value

    fun doExport(label: String, block: suspend () -> Unit) = scope.launch {
        try { block(); snackbar.showSnackbar("$label exported") } catch (e: Exception) { snackbar.showSnackbar("Export failed: ${e.message}") }
    }

    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && loaded != null) doExport("TXT report") { Exporter.writeText(context, uri, ExtractionReportWriter.toText(loaded.report)) }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && loaded != null) doExport("JSON report") { Exporter.writeText(context, uri, ExtractionReportWriter.toJson(loaded.report)) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && loaded != null) doExport("CSV") { Exporter.writeText(context, uri, ExtractionReportWriter.toCsv(loaded.report)) }
    }
    val saveAllLibs = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && loaded != null) scope.launch {
            try {
                val rels = loaded.report.libraries.mapNotNull { it.outputRelativePath }
                val n = Exporter.exportTree(context, uri, loaded.record.displayName.substringBeforeLast('.') + "_native_libs", loaded.extractedRoot, rels)
                snackbar.showSnackbar("Exported $n libraries (original structure preserved)")
            } catch (e: Exception) { snackbar.showSnackbar("Export failed: ${e.message}") }
        }
    }
    val saveOneLib = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val lib = pendingLib
        if (uri != null && loaded != null && lib?.outputRelativePath != null) doExport(lib.entry.fileName) { Exporter.copyFile(context, File(loaded.extractedRoot, lib.outputRelativePath!!), uri) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.record?.displayName ?: "Extraction", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (loaded != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.IosShare, "Export") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val base = loaded.record.displayName.substringBeforeLast('.')
                            DropdownMenuItem(text = { Text("Report (TXT)") }, onClick = { menu = false; saveTxt.launch("${base}_extraction.txt") })
                            DropdownMenuItem(text = { Text("Report (JSON)") }, onClick = { menu = false; saveJson.launch("${base}_extraction.json") })
                            DropdownMenuItem(text = { Text("Library list (CSV)") }, onClick = { menu = false; saveCsv.launch("${base}_libraries.csv") })
                            if (loaded.report.extractedCount > 0)
                                DropdownMenuItem(text = { Text("All extracted .so files → folder") }, onClick = { menu = false; saveAllLibs.launch(null) })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        when (val s = state) {
            is LoadState.Loading -> Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(s.message)
            }
            is LoadState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(pad).padding(24.dp))
            is LoadState.Loaded -> ExtractionContent(s.value, pad, onOpenElf, onOpenStrings) { lib -> pendingLib = lib; saveOneLib.launch(lib.entry.fileName) }
        }
    }
}

@Composable
private fun ExtractionContent(a: LoadedAnalysis, pad: PaddingValues, onOpenElf: (Int) -> Unit, onOpenStrings: (Int) -> Unit, onExportLib: (ExtractedLib) -> Unit) {
    val r = a.report
    var abiFilter by remember { mutableStateOf<String?>(null) }
    val other = "other"
    val filters = r.abis + if (r.libraries.any { it.entry.abi == null }) listOf(other) else emptyList()
    val visible = r.libraries.withIndex().filter { (_, l) ->
        when (abiFilter) { null -> true; other -> l.entry.abi == null; else -> l.entry.abi == abiFilter }
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard("Summary") {
                KeyValue("Source", r.sourceName, mono = false)
                KeyValue("Type", r.containerType.label, mono = false)
                KeyValue("Size", "${r.sourceSize} bytes (${humanBytes(r.sourceSize)})")
                KeyValue("SHA-256", r.sourceSha256)
                KeyValue("ZIP entries", r.totalEntries.toString())
                if (r.nestedContainers.isNotEmpty()) KeyValue("Nested APKs", r.nestedContainers.joinToString("\n"))
                KeyValue("ABIs", r.abis.ifEmpty { listOf("none") }.joinToString(), mono = false)
                KeyValue("Libraries", "${r.libraries.size} found · ${r.extractedCount} extracted · ${r.verifiedCount} verified", mono = false)
            }
        }
        if (r.issues.isNotEmpty()) item {
            SectionCard("Issues (${r.issues.size})") {
                r.issues.forEach { i ->
                    MessageRow(when (i.severity) { IssueSeverity.ERROR -> Level.ERROR; IssueSeverity.WARNING -> Level.WARNING; IssueSeverity.INFO -> Level.INFO },
                        "${i.type.label}: ${i.message}", i.path)
                }
            }
        }
        if (filters.size > 1) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = abiFilter == null, onClick = { abiFilter = null }, label = { Text("All (${r.libraries.size})") }) }
                items(filters) { f ->
                    val n = if (f == other) r.libraries.count { it.entry.abi == null } else r.librariesFor(f).size
                    FilterChip(selected = abiFilter == f, onClick = { abiFilter = f }, label = { Text("$f ($n)") })
                }
            }
        }
        if (r.libraries.isEmpty()) item {
            Column {
                Text("No native libraries (.so) were found in this file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!r.containerType.isArchive) TextButton(onClick = { onOpenStrings(-1) }) { Text("Extract strings from the whole file") }
            }
        }
        items(visible, key = { it.index }) { (idx, lib) -> LibCard(lib, onClick = { onOpenElf(idx) }, onStrings = { onOpenStrings(idx) }, onExport = { onExportLib(lib) }) }
    }
}

@Composable
private fun LibCard(lib: ExtractedLib, onClick: () -> Unit, onStrings: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val ok = lib.crcVerified && lib.hashVerified
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = lib.elf != null, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, if (ok) "Verified" else "Not verified",
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(lib.entry.fileName, style = MaterialTheme.typography.titleSmall)
                    Text(lib.entry.displayPath, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (lib.outputRelativePath != null) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Export .so file") }, onClick = { menu = false; onExport() })
                        if (lib.elf != null) DropdownMenuItem(text = { Text("Open in ELF Analyzer") }, onClick = { menu = false; onClick() })
                        DropdownMenuItem(text = { Text("Strings") }, onClick = { menu = false; onStrings() })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                lib.entry.abi?.let { Pill(it) }
                lib.elf?.let { Pill("ELF${it.bits}") }
                Pill(humanBytes(lib.entry.size), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                lib.elf?.let { if (it.stripped) Pill("stripped", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(6.dp))
            Text("sha256 ${lib.sha256 ?: "—"}", style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lib.elf?.let { e ->
                Text("${e.machine} · ${e.exportedCount} exports · ${e.importedCount} imports" + (if (e.needed.isNotEmpty()) " · needs ${e.needed.joinToString()}" else ""),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } ?: Text("Not a parsable ELF file — see issues", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
