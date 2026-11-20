package com.nativelib.restudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nativelib.restudio.data.AnalysisRecord
import com.nativelib.restudio.ui.MainViewModel
import com.nativelib.restudio.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: MainViewModel, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<AnalysisRecord?>(null) }
    val shown = remember(history, query) {
        val q = query.trim()
        if (q.isEmpty()) history else history.filter { it.displayName.contains(q, true) || it.sha256.startsWith(q.lowercase()) || it.abis.contains(q, true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analysis History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Filter by name, SHA-256 prefix or ABI") },
                )
            }
            if (shown.isEmpty()) item { EmptyState(if (history.isEmpty()) "No analyses yet. Import a file from the dashboard." else "No matches.") }
            items(shown, key = { it.id }) { r ->
                HistoryRow(r, trailing = { IconButton(onClick = { confirm = r }) { Icon(Icons.Filled.Delete, "Delete") } }) { onOpen(r.id) }
            }
        }
    }

    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete analysis?") },
            text = { Text("Removes the stored copy of ${r.displayName} and its extracted libraries from app storage. Your original file is not affected.") },
            confirmButton = { TextButton(onClick = { vm.delete(r); confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            modifier = Modifier.padding(8.dp),
        )
    }
}
