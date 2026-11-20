package com.nativelib.restudio.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.nativelib.restudio.core.common.Hashing
import com.nativelib.restudio.core.common.toHexAddr
import com.nativelib.restudio.core.elf.ElfConstants
import com.nativelib.restudio.core.elf.ElfFile
import com.nativelib.restudio.core.elf.ElfFormatException
import com.nativelib.restudio.core.elf.ElfParser
import com.nativelib.restudio.core.elf.ElfReport
import com.nativelib.restudio.core.elf.ElfSymbol
import com.nativelib.restudio.core.elf.RelroLevel
import com.nativelib.restudio.core.elf.SymbolReport
import com.nativelib.restudio.core.elf.WarningSeverity
import com.nativelib.restudio.ui.components.KeyValue
import com.nativelib.restudio.ui.components.Level
import com.nativelib.restudio.ui.components.MessageRow
import com.nativelib.restudio.ui.components.SectionCard
import com.nativelib.restudio.ui.theme.Mono
import com.nativelib.restudio.util.Exporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ElfTabs {
    const val OVERVIEW = 0; const val HEADER = 1; const val SEGMENTS = 2; const val SECTIONS = 3; const val DYNAMIC = 4
    const val SYMBOLS = 5; const val RELOCATIONS = 6; const val NOTES = 7; const val WARNINGS = 8
    val titles = listOf("Overview", "Header", "Segments", "Sections", "Dynamic", "Symbols", "Relocations", "Notes", "Warnings")
}

class ParsedElf(val title: String, val file: File, val sha256: String, val elf: ElfFile)

class ElfViewModel(app: Application, private val analysisId: Long, private val libIndex: Int) : AndroidViewModel(app) {
    private val repo = (app as NativeLibApp).repository
    private val _state = MutableStateFlow<LoadState<ParsedElf>>(LoadState.Loading("Parsing ELF…"))
    val state: StateFlow<LoadState<ParsedElf>> = _state

    init {
        viewModelScope.launch {
            try {
                val a = repo.load(analysisId) ?: throw IllegalStateException("Analysis not found")
                val lib = a.report.libraries.getOrNull(libIndex) ?: throw IllegalStateException("Library not found")
                val f = lib.outputRelativePath?.let { File(a.extractedRoot, it) } ?: a.inputFile
                val parsed = withContext(Dispatchers.IO) {
                    val ctx = coroutineContext
                    val elf = ElfParser().parse(f) { ctx.ensureActive() }
                    ParsedElf(lib.entry.displayPath, f, lib.sha256 ?: Hashing.sha256(f), elf)
                }
                _state.value = LoadState.Loaded(parsed)
            } catch (e: CancellationException) { throw e } catch (e: ElfFormatException) {
                _state.value = LoadState.Failed("Not a valid ELF file: ${e.message}")
            } catch (e: Exception) {
                _state.value = LoadState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElfAnalyzerScreen(analysisId: Long, libIndex: Int, initialTab: Int, snackbar: SnackbarHostState, onBack: () -> Unit, onOpenStrings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ElfViewModel = viewModel(key = "elf-$analysisId-$libIndex") { ElfViewModel(app, analysisId, libIndex) }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, ElfTabs.titles.lastIndex)) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val parsed = (state as? LoadState.Loaded<ParsedElf>)?.value

    fun export(uri: android.net.Uri?, kind: String) {
        val p = parsed ?: return
        if (uri == null) return
        scope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    val syms = p.elf.allSymbols.filter { it.name.isNotEmpty() && it.type != ElfConstants.STT_SECTION }
                    when (kind) {
                        "json" -> ElfReport.toJson(p.elf, p.title, p.sha256)
                        "sym-csv" -> SymbolReport.toCsv(syms)
                        "sym-json" -> SymbolReport.toJson(p.title, syms, p.elf.symbolAvailabilityNotes())
                        "sym-txt" -> SymbolReport.toText(p.title, syms, p.elf.symbolAvailabilityNotes(), p.elf.header.is64)
                        else -> ElfReport.toText(p.elf, p.title, p.sha256)
                    }
                }
                Exporter.writeText(context, uri, text); snackbar.showSnackbar("Exported")
            } catch (e: Exception) { snackbar.showSnackbar("Export failed: ${e.message}") }
        }
    }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export(it, "txt") }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, "json") }
    val saveSymCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, "sym-csv") }
    val saveSymJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, "sym-json") }
    val saveSymTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { export(it, "sym-txt") }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(parsed?.file?.name ?: "ELF Analyzer", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            parsed?.let { Text(it.title, style = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        if (parsed != null) IconButton(onClick = onOpenStrings) { Icon(Icons.Filled.TextFields, "Strings") }
                        if (parsed != null) Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.IosShare, "Export") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                val base = parsed.file.name.substringBeforeLast('.')
                                DropdownMenuItem(text = { Text("ELF report (TXT)") }, onClick = { menu = false; saveTxt.launch("${base}_elf.txt") })
                                DropdownMenuItem(text = { Text("ELF report (JSON)") }, onClick = { menu = false; saveJson.launch("${base}_elf.json") })
                                DropdownMenuItem(text = { Text("All symbols (CSV)") }, onClick = { menu = false; saveSymCsv.launch("${base}_symbols.csv") })
                                DropdownMenuItem(text = { Text("All symbols (JSON)") }, onClick = { menu = false; saveSymJson.launch("${base}_symbols.json") })
                                DropdownMenuItem(text = { Text("All symbols (TXT)") }, onClick = { menu = false; saveSymTxt.launch("${base}_symbols.txt") })
                            }
                        }
                    },
                )
                if (parsed != null) PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                    ElfTabs.titles.forEachIndexed { i, t ->
                        val badge = when (i) {
                            ElfTabs.SEGMENTS -> parsed.elf.programHeaders.size; ElfTabs.SECTIONS -> parsed.elf.sections.size
                            ElfTabs.SYMBOLS -> parsed.elf.allSymbols.count { it.name.isNotEmpty() }; ElfTabs.RELOCATIONS -> parsed.elf.relocations.size
                            ElfTabs.WARNINGS -> parsed.elf.warnings.size; else -> null
                        }
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(if (badge != null) "$t ($badge)" else t) })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        when (val s = state) {
            is LoadState.Loading -> Column(Modifier.fillMaxSize().padding(pad), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(s.message)
            }
            is LoadState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(pad).padding(24.dp))
            is LoadState.Loaded -> {
                val p = s.value
                if (tab == ElfTabs.SYMBOLS) SymbolsTab(p.elf, pad)
                else LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tab) {
                        ElfTabs.OVERVIEW -> overview(p)
                        ElfTabs.HEADER -> header(p.elf)
                        ElfTabs.SEGMENTS -> segments(p.elf)
                        ElfTabs.SECTIONS -> sections(p.elf)
                        ElfTabs.DYNAMIC -> dynamic(p.elf)
                        ElfTabs.RELOCATIONS -> relocations(p.elf)
                        ElfTabs.NOTES -> notes(p.elf)
                        ElfTabs.WARNINGS -> warnings(p.elf)
                    }
                }
            }
        }
    }
}

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private fun LazyListScope.overview(p: ParsedElf) {
    val e = p.elf; val h = e.header
    item {
        SectionCard("Identity") {
            KeyValue("File", p.file.name, mono = false)
            KeyValue("Size", "${e.fileSize} bytes")
            KeyValue("SHA-256", p.sha256)
            KeyValue("Format", "ELF${h.ident.elfClass.bits} ${h.ident.endianness.name.lowercase()}-endian, ${h.typeName}", mono = false)
            KeyValue("Machine", h.machineName, mono = false)
            KeyValue("Android ABI", ElfConstants.androidAbiFor(h.machine, h.is64) ?: "none (not an Android ABI)", mono = false)
            KeyValue("SONAME", e.soname ?: "—")
            KeyValue("Build ID", e.buildId ?: "—")
        }
    }
    item {
        SectionCard("Contents") {
            KeyValue("Segments", e.programHeaders.size.toString())
            KeyValue("Sections", e.sections.size.toString())
            KeyValue("Dynamic symbols", "${e.dynamicSymbols.size} (${e.exportedSymbols.size} exported, ${e.importedSymbols.size} imported)")
            KeyValue("Static symbols", if (e.isStripped) "none — stripped" else e.staticSymbols.size.toString())
            KeyValue("Relocations", e.relocations.size.toString())
            KeyValue("Debug info", if (e.hasDebugInfo) "DWARF sections present" else "none", mono = false)
            KeyValue("Dependencies", e.neededLibraries.ifEmpty { listOf("none") }.joinToString("\n"))
        }
    }
    item {
        val sec = e.security
        SectionCard("Hardening facts (from headers)") {
            KeyValue("RWX segment", yesNo(sec.hasRwxSegment), mono = false)
            KeyValue("Non-exec stack", sec.nxStack?.let { yesNo(it) } ?: "unknown (no PT_GNU_STACK)", mono = false)
            KeyValue("RELRO", when (sec.relro) { RelroLevel.FULL -> "full"; RelroLevel.PARTIAL -> "partial"; RelroLevel.NONE -> "none" }, mono = false)
            KeyValue("BIND_NOW", yesNo(sec.bindNow), mono = false)
            KeyValue("Text relocations", yesNo(sec.hasTextRelocations), mono = false)
            KeyValue("16 KiB pages", if (sec.supports16KbPages) "compatible (max align ${sec.maxLoadAlignment})" else "NOT compatible (max align ${sec.maxLoadAlignment})", mono = false)
        }
    }
    val notes = e.symbolAvailabilityNotes()
    if (notes.isNotEmpty()) item { SectionCard("What is not available") { notes.forEach { MessageRow(Level.INFO, it) } } }
}

private fun LazyListScope.header(e: ElfFile) {
    val h = e.header
    item {
        SectionCard("ELF header") {
            KeyValue("Class", "ELF${h.ident.elfClass.bits}")
            KeyValue("Data", h.ident.endianness.name)
            KeyValue("Version", "${h.ident.version} / ${h.version}")
            KeyValue("OS/ABI", "${h.ident.osAbiName} (${h.ident.osAbi})")
            KeyValue("ABI version", h.ident.abiVersion.toString())
            KeyValue("Type", "${h.typeName} (${h.type})")
            KeyValue("Machine", "${h.machineName} (${h.machine})")
            KeyValue("Entry", h.entry.toHexAddr())
            KeyValue("Flags", h.flags.toHexAddr())
            KeyValue("Header size", "${h.ehSize} bytes")
            KeyValue("Prog headers", "${h.phNum} × ${h.phEntSize} @ ${h.phOff.toHexAddr()}")
            KeyValue("Sect headers", "${h.shNum} × ${h.shEntSize} @ ${h.shOff.toHexAddr()}")
            KeyValue("shstrndx", h.shStrNdx.toString())
        }
    }
}

@Composable
private fun MonoRow(text: String) {
    SelectionContainer { Text(text, style = Mono, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState())) }
}

private fun LazyListScope.segments(e: ElfFile) {
    if (e.programHeaders.isEmpty()) { item { Text("No program headers.") }; return }
    val w = if (e.header.is64) 16 else 8
    item { MonoRow("%-3s %-13s %-4s %-10s %-${w + 2}s %-10s %-10s %s".format("#", "TYPE", "PERM", "OFFSET", "VADDR", "FILESZ", "MEMSZ", "ALIGN")) }
    items(e.programHeaders) { p ->
        MonoRow("%-3d %-13s %-4s %-10s %-${w + 2}s %-10s %-10s %s".format(p.index, p.typeName, p.permissions, p.offset.toHexAddr(), p.vaddr.toHexAddr(w), p.fileSize.toHexAddr(), p.memSize.toHexAddr(), p.align.toHexAddr()))
    }
}

private fun LazyListScope.sections(e: ElfFile) {
    if (e.sections.isEmpty()) { item { MessageRow(Level.INFO, "No section header table: sections were stripped. Segment and dynamic information is still available.") }; return }
    val w = if (e.header.is64) 16 else 8
    item { MonoRow("%-4s %-22s %-13s %-5s %-${w + 2}s %-10s %s".format("[#]", "NAME", "TYPE", "FLG", "ADDR", "OFFSET", "SIZE")) }
    items(e.sections) { s ->
        MonoRow("%-4s %-22s %-13s %-5s %-${w + 2}s %-10s %s".format("[${s.index}]", s.name.take(22), s.typeName, s.flagsString, s.addr.toHexAddr(w), s.offset.toHexAddr(), s.size.toHexAddr()))
    }
    item { Text("Flags: W write, A alloc, X exec, M merge, S strings, I info, L link-order, G group, T TLS, C compressed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun LazyListScope.dynamic(e: ElfFile) {
    item {
        SectionCard("Dependencies") {
            KeyValue("DT_NEEDED", e.neededLibraries.ifEmpty { listOf("—") }.joinToString("\n"))
            KeyValue("DT_SONAME", e.soname ?: "—")
            KeyValue("DT_RPATH", e.rpath ?: "—")
            KeyValue("DT_RUNPATH", e.runpath ?: "—")
            e.versionNeeds.forEach { KeyValue("Version need", "${it.file}: ${it.versions.joinToString()}") }
            if (e.versionDefinitions.isNotEmpty()) KeyValue("Version defs", e.versionDefinitions.joinToString("\n"))
        }
    }
    if (e.dynamic.isEmpty()) { item { Text("No dynamic section (statically linked or not a dynamic object).") }; return }
    items(e.dynamic) { d -> MonoRow("%-16s %s".format(d.tagName, d.stringValue?.let { "[$it]" } ?: d.value.toHexAddr())) }
}

private fun LazyListScope.relocations(e: ElfFile) {
    if (e.relocations.isEmpty()) { item { Text("No relocations decoded.") } }
    val w = if (e.header.is64) 16 else 8
    items(e.relocations) { r ->
        MonoRow("%-12s %-${w + 2}s %-24s %s%s".format(r.sectionName, r.offset.toHexAddr(w), r.typeName, r.symbolName ?: "", r.addend?.let { " + ${it.toHexAddr()}" } ?: ""))
    }
}

private fun LazyListScope.notes(e: ElfFile) {
    if (e.notes.isEmpty()) { item { Text("No ELF notes.") }; return }
    items(e.notes) { n ->
        SectionCard(n.sectionOrSegment) {
            KeyValue("Owner", n.owner)
            KeyValue("Type", n.typeName)
            KeyValue("Descriptor", n.descriptor.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(512))
        }
    }
}

private fun LazyListScope.warnings(e: ElfFile) {
    if (e.warnings.isEmpty()) { item { MessageRow(Level.INFO, "The parser found no structural problems.") }; return }
    items(e.warnings) { w ->
        MessageRow(when (w.severity) { WarningSeverity.ERROR -> Level.ERROR; WarningSeverity.WARNING -> Level.WARNING; WarningSeverity.INFO -> Level.INFO }, w.message)
    }
}

private enum class SymFilter(val label: String, val test: (ElfSymbol) -> Boolean) {
    ALL("All", { true }), EXPORTED("Exported", { it.isExported }), IMPORTED("Imported", { it.isImported }),
    FUNCTIONS("Functions", { it.isFunction }), OBJECTS("Objects", { it.isObject }), WEAK("Weak", { it.isWeak }),
    GLOBAL("Global", { it.isGlobal }), LOCAL("Local", { it.isLocal }), UNDEFINED("Undefined", { it.isUndefined }),
    DYNSYM(".dynsym", { it.table.name == "DYNSYM" }), SYMTAB(".symtab", { it.table.name == "SYMTAB" }),
}

private enum class SymSort(val label: String) { NAME("Name"), ADDRESS("Address"), SIZE("Size") }

@Composable
private fun SymbolsTab(e: ElfFile, pad: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SymFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(SymSort.NAME) }
    val all = remember(e) { e.allSymbols.filter { it.name.isNotEmpty() && it.type != ElfConstants.STT_SECTION } }
    val shown = remember(all, query, filter, sort) {
        val q = query.trim()
        all.asSequence().filter(filter.test).filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.value.toHexAddr().contains(q, ignoreCase = true) }
            .let { s ->
                when (sort) {
                    SymSort.NAME -> s.sortedBy { it.name }
                    SymSort.ADDRESS -> s.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.value, b.value) }
                    SymSort.SIZE -> s.sortedByDescending { it.size }
                }
            }.toList()
    }
    val w = if (e.header.is64) 16 else 8
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        e.symbolAvailabilityNotes().take(1).forEach { n -> item { MessageRow(Level.INFO, n) } }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                placeholder = { Text("Search name or address") },
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SymFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sort:", style = MaterialTheme.typography.bodySmall)
                SymSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) }) }
                Spacer(Modifier.width(8.dp))
                Text("${shown.size} of ${all.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(shown, key = { it.table.name + it.index }) { s ->
            Column(Modifier.padding(vertical = 4.dp)) {
                SelectionContainer { Text(s.displayName, style = Mono.copy(color = MaterialTheme.colorScheme.onSurface)) }
                Text("%s  %-7s %-6s %-9s size=%d  sec=%s  %s".format(s.value.toHexAddr(w), s.typeName, s.bindName, s.visibilityName, s.size, s.sectionIndexName, s.table.name.lowercase()),
                    style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
