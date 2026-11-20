package com.nativelib.restudio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nativelib.restudio.core.common.humanBytes
import com.nativelib.restudio.data.Progress
import com.nativelib.restudio.ui.JobState
import com.nativelib.restudio.ui.theme.Mono

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Label/value row; values are selectable and monospace when [mono] is set. */
@Composable
fun KeyValue(label: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 120.dp, max = 140.dp))
        SelectionContainer { Text(value, style = if (mono) Mono else MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, onColor: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = onColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

enum class Level { INFO, WARNING, ERROR }

@Composable
fun LevelIcon(level: Level) {
    val (icon, tint) = when (level) {
        Level.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.secondary
        Level.WARNING -> Icons.Filled.Warning to MaterialTheme.colorScheme.tertiary
        Level.ERROR -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = level.name, tint = tint, modifier = Modifier.size(18.dp))
}

@Composable
fun MessageRow(level: Level, text: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        LevelIcon(level)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodySmall)
            detail?.let { Text(it, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Blocking progress dialog for import/extraction with a working Cancel button. */
@Composable
fun ProgressDialog(state: JobState.Running, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text(if (state.count > 1) "Analysing ${state.index + 1} of ${state.count}" else "Analysing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val p = state.progress) {
                    null -> { Text("Preparing…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is Progress.Copying -> {
                        Text("Importing ${p.fileName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(humanBytes(p.done) + if (p.total > 0) " / " + humanBytes(p.total) else "", style = Mono)
                    }
                    is Progress.Extracting -> {
                        Text("Extracting & verifying native libraries")
                        if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${p.done} / ${p.total}", style = Mono)
                        Text(p.current, style = Mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(24.dp))
}
