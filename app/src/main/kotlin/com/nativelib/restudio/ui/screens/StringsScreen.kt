package com.nativelib.restudio.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nativelib.restudio.NativeLibApp
import com.nativelib.restudio.core.common.MappedFileSource
import com.nativelib.restudio.core.common.hexDump
import com.nativelib.restudio.core.common.toHexAddr
import com.nativelib.restudio.core.elf.ElfFormatException
import com.nativelib.restudio.core.elf.ElfParser
import com.nativelib.restudio.core.strings.ExtractedString
import com.nativelib.restudio.core.strings.StringCategory
import com.nativelib.restudio.core.strings.StringEncoding
import com.nativelib.restudio.core.strings.StringExtractor
import com.nativelib.restudio.core.strings.StringOptions
import com.nativelib.restudio.core.strings.StringReport
import com.nativelib.restudio.ui.components.Level
import com.nativelib.restudio.ui.components.MessageRow
import com.nativelib.restudio.ui.components.Pill
import com.nativelib.restudio.ui.theme.Mono
import com.nativelib.restudio.util.Exporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class StringQuery(
    val text: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val category: StringCategory? = null,
    val encoding: StringEncoding? = null,
)

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val scanned: Long, val total: Long) : ScanState
    data class Done(val strings: List<ExtractedString>, val truncated: Boolean, val options: StringOptions) : ScanState
    data class Failed(val message: String) : ScanState
}

data class FilterResult(val shown: List<ExtractedString>, val error: String? = null)

class StringsViewModel(app: Application, private val analysisId: Long, private val libIndex: Int) : AndroidViewModel(app) {
    private val repo = (app as NativeLibApp).repository
    var file: File? = null; private set
    var title: String = ""; private set

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan
    val query = MutableStateFlow(StringQuery())
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    val filtered: StateFlow<FilterResult> = combine(_scan, query.debounce(200)) { s, q -> s to q }
        .map { (s, q) -> if (s is ScanState.Done) applyFilter(s.strings, q) else FilterResult(emptyList()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilterResult(emptyList()))

    init { scan(StringOptions()) }

    fun scan(options: StringOptions) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                _scan.value = ScanState.Scanning(0, 0)
                val a = repo.load(analysisId) ?: throw IllegalStateException("Analysis not found")
                val f = if (libIndex < 0) a.inputFile else {
                    val lib = a.report.libraries.getOrNull(libIndex) ?: throw IllegalStateException("Library not found")
                    lib.outputRelativePath?.let { File(a.extractedRoot, it) } ?: a.inputFile
                }
                file = f
                title = if (libIndex < 0) a.record.displayName else a.report.libraries[libIndex].entry.displayPath
                val result = withContext(Dispatchers.IO) {
                    val ctx = coroutineContext
                    MappedFileSource(f).use { src ->
                        val elf = try { ElfParser().parse(src) } catch (_: ElfFormatException) { null } // raw/DEX files: no sections
                        StringExtractor(options).extract(src, elf, progress = { d, t -> _scan.value = ScanState.Scanning(d, t) }, checkCancelled = { ctx.ensureActive() })
                    }
                }
                _scan.value = ScanState.Done(result.strings, result.truncated, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _scan.value = ScanState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelScan() { job?.cancel(); if (_scan.value is ScanState.Scanning) _scan.value = ScanState.Failed("Scan cancelled") }

    /** Reads up to 128 bytes around a string for the hex view. */
    suspend fun hexAround(s: ExtractedString): String = withContext(Dispatchers.IO) {
        val f = file ?: return@withContext ""
        MappedFileSource(f).use { src ->
            val from = maxOf(0L, (s.offset - 32) and 0xF.inv().toLong())
            val to = minOf(src.size, s.offset + minOf(s.byteLength, 4096) + 32)
            val len = minOf(to - from, 512L).toInt()
            hexDump(src.readBytes(from, len), from, s.offset, s.offset + s.byteLength)
        }
    }

    private fun applyFilter(all: List<ExtractedString>, q: StringQuery): FilterResult {
        val base = all.asSequence()
            .filter { q.category == null || q.category in it.categories }
            .filter { q.encoding == null || it.encoding == q.encoding }
        if (q.text.isEmpty()) return FilterResult(base.toList())
        return if (q.regex) {
            val re = try { Regex(q.text, if (q.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) }
            catch (e: Exception) { return FilterResult(emptyList(), "Invalid regex: ${e.message?.lineSequence()?.firstOrNull()}") }
            FilterResult(base.filter { re.containsMatchIn(it.text) }.toList())
        } else {
            val hex = q.text.removePrefix("0x").lowercase()
            FilterResult(base.filter { it.text.contains(q.text, ignoreCase = !q.caseSensitive) || java.lang.Long.toHexString(it.offset).startsWith(hex) && hex.isNotEmpty() && q.text.startsWith("0x") }.toList())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StringsScreen(analysisId: Long, libIndex: Int, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: StringsViewModel = viewModel(key = "strings-$analysisId-$libIndex") { StringsViewModel(app, analysisId, libIndex) }
    val scan by vm.scan.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val filtered by vm.filtered.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExtractedString?>(null) }
    val done = scan as? ScanState.Done

    fun export(uri: android.net.Uri?, kind: String) {
        if (uri == null) return
        val list = filtered.shown
        scope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    when (kind) {
                        "csv" -> StringReport.toCsv(list)
                        "json" -> StringReport.toJson(vm.title, list, done?.truncated == true)
                        else -> StringReport.toText(vm.title, list, done?.truncated == true)
                    }
                }
                Exporter.writeText(context, uri, text); snackbar.showSnackbar("Exported ${list.size} strings")
            } catch (e: Exception) { snackbar.showSnackbar("Export failed: ${e.message}") }
        }
    }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export(it, "txt") }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, "csv") }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, "json") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Strings", maxLines = 1)
                        Text(vm.title, style = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showOptions = !showOptions }) { Icon(Icons.Filled.Tune, "Scan options") }
                    if (done != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.IosShare, "Export") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val base = vm.file?.name?.substringBeforeLast('.') ?: "strings"
                            Text("Exports the ${filtered.shown.size} strings currently shown", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            DropdownMenuItem(text = { Text("TXT") }, onClick = { menu = false; saveTxt.launch("${base}_strings.txt") })
                            DropdownMenuItem(text = { Text("CSV") }, onClick = { menu = false; saveCsv.launch("${base}_strings.csv") })
                            DropdownMenuItem(text = { Text("JSON") }, onClick = { menu = false; saveJson.launch("${base}_strings.json") })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showOptions) item { ScanOptionsCard(done?.options ?: StringOptions(), onScan = { showOptions = false; vm.scan(it) }) }
            when (val s = scan) {
                ScanState.Idle -> Unit
                is ScanState.Scanning -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Scanning… ${if (s.total > 0) "${s.scanned * 100 / s.total}%" else ""}")
                        if (s.total > 0) LinearProgressIndicator(progress = { s.scanned.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = { vm.cancelScan() }) { Text("Cancel") }
                    }
                }
                is ScanState.Failed -> item { MessageRow(Level.ERROR, s.message) }
                is ScanState.Done -> {
                    val filterError = filtered.error
                    item {
                        OutlinedTextField(
                            value = q.text, onValueChange = { vm.query.value = q.copy(text = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = { if (q.text.isNotEmpty()) IconButton(onClick = { vm.query.value = q.copy(text = "") }) { Icon(Icons.Filled.Clear, "Clear") } },
                            placeholder = { Text(if (q.regex) "Regular expression" else "Search text (or 0x offset)") },
                            isError = filtered.error != null,
                            supportingText = if (filterError != null) { { Text(filterError) } } else null,
                        )
                    }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = q.regex, onClick = { vm.query.value = q.copy(regex = !q.regex) }, label = { Text("Regex") })
                            FilterChip(selected = q.caseSensitive, onClick = { vm.query.value = q.copy(caseSensitive = !q.caseSensitive) }, label = { Text("Aa case") })
                            s.options.encodings.sortedBy { it.ordinal }.forEach { e ->
                                FilterChip(selected = q.encoding == e, onClick = { vm.query.value = q.copy(encoding = if (q.encoding == e) null else e) }, label = { Text(e.label) })
                            }
                        }
                    }
                    item {
                        val counts = remember(s) { s.strings.flatMap { it.categories }.groupingBy { it }.eachCount() }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = q.category == null, onClick = { vm.query.value = q.copy(category = null) }, label = { Text("All") })
                            StringCategory.entries.filter { (counts[it] ?: 0) > 0 }.forEach { c ->
                                FilterChip(selected = q.category == c, onClick = { vm.query.value = q.copy(category = if (q.category == c) null else c) }, label = { Text("${c.label} (${counts[c]})") })
                            }
                        }
                    }
                    item {
                        Text("${filtered.shown.size} of ${s.strings.size} strings · min length ${s.options.minLength}" +
                            if (s.truncated) " · result limit reached, scan stopped early" else "",
                            style = MaterialTheme.typography.bodySmall, color = if (s.truncated) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Categories are heuristics. URLs and commands are shown as text only and are never opened.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    items(filtered.shown, key = { it.offset.toString() + it.encoding.name }) { str -> StringRow(str) { selected = str } }
                }
            }
        }
    }

    selected?.let { s -> StringDetailDialog(s, vm) { selected = null } }
}

@Composable
private fun StringRow(s: ExtractedString, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(StringReport.escape(s.text), style = Mono.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${s.offset.toHexAddr(8)}${s.vaddr?.let { " va " + it.toHexAddr() } ?: ""} ${s.section ?: ""} ${s.encoding.label}",
                style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            s.categories.minByOrNull { it.ordinal }?.let { Pill(it.label) }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun StringDetailDialog(s: ExtractedString, vm: StringsViewModel, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var hex by remember(s) { mutableStateOf("Loading…") }
    LaunchedEffect(s) { hex = try { vm.hexAround(s) } catch (e: Exception) { "Cannot read bytes: ${e.message}" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${s.encoding.label} · ${s.byteLength} bytes") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { Text(s.text, style = Mono.copy(color = MaterialTheme.colorScheme.onSurface)) }
                Text("File offset ${s.offset.toHexAddr()} (${s.offset})", style = Mono)
                s.vaddr?.let { Text("Virtual address ${it.toHexAddr()}", style = Mono) }
                s.section?.let { Text("Section $it", style = Mono) }
                if (s.categories.isNotEmpty()) Text("Tags: " + s.categories.sortedBy { it.ordinal }.joinToString { it.label }, style = MaterialTheme.typography.bodySmall)
                Text("Hex (selected bytes marked with [):", style = MaterialTheme.typography.labelSmall)
                SelectionContainer {
                    Text(hex, style = Mono, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(s.text)); onDismiss() }) { Text("Copy text") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ScanOptionsCard(current: StringOptions, onScan: (StringOptions) -> Unit) {
    var min by remember(current) { mutableStateOf(current.minLength.toFloat()) }
    var encodings by remember(current) { mutableStateOf(current.encodings) }
    com.nativelib.restudio.ui.components.SectionCard("Scan options") {
        Text("Minimum length: ${min.toInt()} characters", style = MaterialTheme.typography.bodySmall)
        Slider(value = min, onValueChange = { min = it }, valueRange = 3f..32f, steps = 28)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StringEncoding.entries.forEach { e ->
                FilterChip(selected = e in encodings, onClick = { encodings = if (e in encodings) encodings - e else encodings + e }, label = { Text(e.label) })
            }
        }
        Text("UTF-16BE is off by default (Android is little-endian). UTF-16 detection skips CJK scripts to avoid noise.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(4.dp))
        Button(onClick = { onScan(current.copy(minLength = min.toInt(), encodings = encodings)) }, enabled = encodings.isNotEmpty()) { Text("Rescan") }
    }
}
