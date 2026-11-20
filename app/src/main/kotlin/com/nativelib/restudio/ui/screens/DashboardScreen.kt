package com.nativelib.restudio.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativelib.restudio.core.common.humanBytes
import com.nativelib.restudio.data.AnalysisRecord
import com.nativelib.restudio.ui.MainViewModel
import com.nativelib.restudio.ui.components.Pill
import java.text.DateFormat
import java.util.Date

private data class Tool(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    /** Phase in which the tool is implemented; null when available now. */
    val plannedPhase: Int? = null,
    val action: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: MainViewModel,
    snackbar: SnackbarHostState,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAnalysis: (Long) -> Unit,
) {
    val history by vm.history.collectAsStateWithLifecycle()

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importUris(uris) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.importFolder(it) } }
    val pickElf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importUris(listOf(it), openElfDirectly = true) } }
    val pickElfSymbols = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importUris(listOf(it), openElfDirectly = true, elfTab = ElfTabs.SYMBOLS) } }
    val any = arrayOf("*/*")

    val tools = listOf(
        Tool("Import File", "APK, XAPK, APKS, AAB, ZIP, JAR, DEX, SO, BIN — multiple selection", Icons.Filled.FileOpen) { pickFiles.launch(any) },
        Tool("Import Folder", "Analyse every supported file in a folder", Icons.Filled.FolderOpen) { pickFolder.launch(null) },
        Tool("Extract Native Libraries", "Detect ABIs, extract every .so, verify CRC-32 + SHA-256", Icons.Filled.Unarchive) { pickFiles.launch(any) },
        Tool("ELF Analyzer", "Headers, segments, sections, dynamic, relocations, notes", Icons.Filled.Memory) { pickElf.launch(any) },
        Tool("Symbol Explorer", "Exported / imported / dynamic / static symbols", Icons.Filled.DataObject) { pickElfSymbols.launch(any) },
        Tool("Strings Explorer", "ASCII / UTF-8 / UTF-16 strings", Icons.Filled.TextFields, plannedPhase = 4),
        Tool("Disassembler", "ARM32 / ARM64 / x86 / x86_64 via Capstone", Icons.Filled.Code, plannedPhase = 5),
        Tool("Decompiler", "Pseudocode (clearly labelled as reconstructed)", Icons.AutoMirrored.Filled.ViewList, plannedPhase = 6),
        Tool("Dependency Analyzer", "Dependency trees; NEEDED/RPATH already in ELF Analyzer", Icons.Filled.AccountTree, plannedPhase = 7),
        Tool("Hex Editor", "Hex / ASCII / binary views, edits saved as copies", Icons.Filled.GridOn, plannedPhase = 7),
        Tool("Compare Libraries", "Hash, section, symbol and binary diff", Icons.AutoMirrored.Filled.CompareArrows, plannedPhase = 7),
        Tool("Security Analyzer", "Static indicators (not malware verdicts)", Icons.Filled.Security, plannedPhase = 8),
        Tool("Analysis History", "${history.size} saved analyses", Icons.Filled.History) { onOpenHistory() },
        Tool("Settings", "Theme, workspace storage, legal notice", Icons.Filled.Settings) { onOpenSettings() },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Native Library RE Studio", fontWeight = FontWeight.SemiBold); Text("On-device static analysis", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                actions = { IconButton(onClick = onOpenHistory) { Icon(Icons.AutoMirrored.Filled.List, "History") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(tools, key = { it.title }) { ToolTile(it) }
            if (history.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(history.take(6), key = { "h" + it.id }, span = { GridItemSpan(maxLineSpan) }) { HistoryRow(it) { onOpenAnalysis(it.id) } }
            }
        }
    }
}

@Composable
private fun ToolTile(tool: Tool) {
    val enabled = tool.plannedPhase == null && tool.action != null
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 118.dp).then(if (enabled) Modifier.clickable { tool.action?.invoke() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tool.icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                if (!enabled && tool.plannedPhase != null) Pill("Phase ${tool.plannedPhase}", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(tool.title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (enabled) tool.subtitle else "Not implemented yet — ${tool.subtitle}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun HistoryRow(r: AnalysisRecord, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.containerType} · ${humanBytes(r.sizeBytes)} · ${r.libraryCount} libs · ${r.abis.ifEmpty { "no ABIs" }}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.createdAt)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (r.errorCount > 0) Pill("${r.errorCount} errors", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            else if (r.issueCount > 0) Pill("${r.issueCount} issues", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            trailing?.invoke()
        }
    }
}
