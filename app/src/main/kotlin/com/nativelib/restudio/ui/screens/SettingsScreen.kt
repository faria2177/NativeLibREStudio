package com.nativelib.restudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativelib.restudio.BuildConfigInfo
import com.nativelib.restudio.ThemeMode
import com.nativelib.restudio.core.common.humanBytes
import com.nativelib.restudio.ui.MainViewModel
import com.nativelib.restudio.ui.components.KeyValue
import com.nativelib.restudio.ui.components.SectionCard
import com.nativelib.restudio.update.UpdateLogic
import com.nativelib.restudio.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val LEGAL_NOTICE =
    "Native Library RE Studio is intended for authorized reverse engineering, interoperability research, security " +
        "auditing, education and analysis of software you own or are authorized to inspect.\n\n" +
        "It performs static analysis only: imported files are treated as untrusted, are never loaded into the app " +
        "process and are never executed. It contains no functionality for bypassing licensing or DRM, stealing " +
        "credentials or modifying third-party applications.\n\n" +
        "You are responsible for complying with applicable laws and licence terms."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val theme by vm.settings.theme.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val autoUpdate by vm.updates.autoCheck.collectAsStateWithLifecycle()
    val updateState by vm.updates.state.collectAsStateWithLifecycle()
    var used by remember { mutableLongStateOf(-1L) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(history.size) { used = withContext(Dispatchers.IO) { vm.workspace.usedBytes() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = theme == m, onClick = { vm.settings.setTheme(m) }, label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            SectionCard("Workspace") {
                KeyValue("Analyses", history.size.toString())
                KeyValue("Storage used", if (used < 0) "calculating…" else humanBytes(used))
                Text("Imported files are copied into private app storage and analysed there; originals are never modified.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Clear all analyses") }
            }
            SectionCard("Updates") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GitHub Release থেকে স্বয়ংক্রিয় আপডেট চেক", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = autoUpdate, onCheckedChange = { vm.updates.setAutoCheck(it) })
                }
                KeyValue("Installed", BuildConfigInfo.versionName(context))
                KeyValue("Source", "github.com/${UpdateLogic.REPO}/releases")
                Text("শুধু আপডেট চেক ও ডাউনলোডের জন্য ইন্টারনেট ব্যবহার হয়; আপনার ফাইল কোথাও আপলোড হয় না। ডাউনলোড করা APK SHA-256 দিয়ে যাচাই করা হয়।",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.checkUpdates() }, enabled = updateState !is UpdateState.Checking, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (updateState is UpdateState.Checking) "চেক করা হচ্ছে…" else "এখনই আপডেট চেক করুন")
                }
            }
            SectionCard("About") {
                KeyValue("Version", BuildConfigInfo.versionName(context))
                KeyValue("Engines", "ELF parser & archive extractor: in-house Kotlin (no native code executed)", mono = false)
            }
            SectionCard("Legal & safety") { Text(LEGAL_NOTICE, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all analyses?") },
        text = { Text("Deletes every stored copy, extracted library and history entry from app storage.") },
        confirmButton = { TextButton(onClick = { vm.clearAll(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
