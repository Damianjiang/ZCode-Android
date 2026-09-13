package app.zemote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zemote.R
import app.zemote.ui.logger.ZemoteLogger
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entries by ZemoteLogger.entriesFlow.collectAsState(initial = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val hasEntries = entries.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val clearText = stringResource(R.string.logs_cleared)
                    IconButton(
                        onClick = { scope.launch { ZemoteLogger.clear(); snackbarHostState.showSnackbar(clearText) } },
                        enabled = hasEntries,
                    ) {
                        Icon(Icons.Rounded.ClearAll, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val copiedText = stringResource(R.string.logs_copied)
                    FilledTonalIconButton(
                        onClick = {
                            val text = entries.joinToString("\n") { it.formatted }
                            clipboard.setText(AnnotatedString(text))
                            scope.launch { snackbarHostState.showSnackbar(copiedText) }
                        },
                        enabled = hasEntries,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = if (hasEntries) stringResource(R.string.logs_count, entries.size) else stringResource(R.string.logs_empty), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasEntries && entries.size >= 2) { Text(text = entries.last().timestamp + " ~ " + entries.first().timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
            }
            if (!hasEntries) {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.logs_empty_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                    items(items = entries, key = { entry -> entry.id }) { entry -> LogEntryRow(entry) }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: ZemoteLogger.LogEntry) {
    val color = when (entry.level) { ZemoteLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f); ZemoteLogger.Level.INFO -> MaterialTheme.colorScheme.onPrimaryContainer; ZemoteLogger.Level.WARN -> MaterialTheme.colorScheme.onTertiaryContainer; ZemoteLogger.Level.ERROR -> MaterialTheme.colorScheme.onErrorContainer }
    val tagBg = when (entry.level) { ZemoteLogger.Level.DEBUG -> MaterialTheme.colorScheme.primaryContainer; ZemoteLogger.Level.INFO -> MaterialTheme.colorScheme.secondaryContainer; ZemoteLogger.Level.WARN -> MaterialTheme.colorScheme.tertiaryContainer; ZemoteLogger.Level.ERROR -> MaterialTheme.colorScheme.errorContainer }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(entry.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.width(52.dp))
        Text(entry.level.symbol, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.background(tagBg, MaterialTheme.shapes.small).padding(horizontal = 4.dp, vertical = 1.dp).size(22.dp), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.width(6.dp))
        Text(entry.message, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
    }
}
