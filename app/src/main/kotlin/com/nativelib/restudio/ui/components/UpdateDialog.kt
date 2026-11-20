package com.nativelib.restudio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nativelib.restudio.core.common.humanBytes
import com.nativelib.restudio.ui.MainViewModel
import com.nativelib.restudio.update.UpdateState

/** Shows update prompts/progress. UpToDate and Failed states are only reached from a manual check. */
@Composable
fun UpdateDialog(state: UpdateState, vm: MainViewModel) {
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = { vm.updates.dismiss() },
            title = { Text("নতুন আপডেট: ${state.info.title}") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Installed build ${vm.updates.installedBuild} → build ${state.info.buildNumber}")
                    Text("${state.info.apkName} · ${humanBytes(state.info.apkSize)}", style = MaterialTheme.typography.bodySmall)
                    if (state.info.notes.isNotBlank()) Text(state.info.notes.trim(), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.downloadUpdate(state.info) }) { Text("ডাউনলোড ও ইনস্টল") } },
            dismissButton = { TextButton(onClick = { vm.updates.dismiss() }) { Text("পরে") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("আপডেট ডাউনলোড হচ্ছে") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.total > 0) LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(humanBytes(state.done) + if (state.total > 0) " / " + humanBytes(state.total) else "")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.cancelUpdate() }) { Text("বাতিল") } },
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { vm.updates.dismiss() },
            title = { Text("আপডেট প্রস্তুত") },
            text = { Text("SHA-256 যাচাই সম্পন্ন। ইনস্টল করতে চাপুন। প্রথমবার \"Install unknown apps\" অনুমতি দিতে হতে পারে — অনুমতি দিয়ে ফিরে এসে আবার চাপুন।") },
            confirmButton = { TextButton(onClick = { vm.updates.install(state.file) }) { Text("ইনস্টল") } },
            dismissButton = { TextButton(onClick = { vm.updates.dismiss() }) { Text("বন্ধ") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = { vm.updates.dismiss() },
            title = { Text("আপডেট") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = { vm.updates.dismiss() }) { Text("ঠিক আছে") } },
        )
        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { vm.updates.dismiss() },
            title = { Text("আপডেট") },
            text = { Text("আপনি সর্বশেষ ভার্সন (build ${state.installedBuild}) ব্যবহার করছেন।") },
            confirmButton = { TextButton(onClick = { vm.updates.dismiss() }) { Text("ঠিক আছে") } },
        )
        UpdateState.Idle, UpdateState.Checking -> Unit
    }
}
