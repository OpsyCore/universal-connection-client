package io.ucc.app.ui.logs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.app.data.LogBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(state: LogsUiState, vm: LogsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.exportText()) }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.logs_share)))
                    }, enabled = state.total > 0) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_share)) }
                    IconButton(onClick = vm::clear, enabled = state.total > 0) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.logs_clear)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LogBuffer.Level.entries.forEach { lvl ->
                    FilterChip(selected = state.minLevel == lvl, onClick = { vm.setMinLevel(lvl) }, label = { Text(lvl.name.lowercase()) })
                }
            }
            if (state.entries.isEmpty()) {
                Text(stringResource(R.string.logs_empty), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    items(state.entries) { e ->
                        val color = when (e.level) {
                            LogBuffer.Level.ERROR -> MaterialTheme.colorScheme.error
                            LogBuffer.Level.WARN -> MaterialTheme.colorScheme.tertiary
                            LogBuffer.Level.INFO -> MaterialTheme.colorScheme.onSurface
                            LogBuffer.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            "${fmt.format(Date(e.epochMs))} ${if (e.source == LogBuffer.Source.CORE) "core" else "app "} ${e.message}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = color,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
