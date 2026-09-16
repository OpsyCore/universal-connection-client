package io.ucc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.core.engine.ConnectionState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: HomeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectProfile: (String) -> Unit,
) {
    val context = LocalContext.current
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state, onConnect, onDisconnect)

            if (state.profiles.isEmpty()) {
                EmptyProfiles()
            } else {
                Text(stringResource(R.string.home_selected_profile), style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.profiles, key = { it.id }) { p ->
                        val selected = p.id == state.selectedProfile?.id
                        val button: @Composable () -> Unit = {
                            Text(p.name.ifBlank { p.address }, maxLines = 1)
                        }
                        if (selected) Button(onClick = { onSelectProfile(p.id) }, modifier = Modifier.fillMaxWidth()) { button() }
                        else FilledTonalButton(onClick = { onSelectProfile(p.id) }, modifier = Modifier.fillMaxWidth()) { button() }
                    }
                }
            }

            EventsList(state)

            Text(
                text = "${stringResource(R.string.home_core)}: ${state.coreName} ${state.coreVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(state: HomeUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val conn = state.connection
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (conn.isTransitioning) CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Text(conn.label(context), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)

            if (conn is ConnectionState.Error) {
                Text(
                    conn.error.userMessage(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            val profileName = state.selectedProfile?.name ?: stringResource(R.string.home_none_selected)
            Text(profileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)

            if (conn is ConnectionState.Connected) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(conn.sinceEpochMs) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
                val stats = state.statistics
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Metric(stringResource(R.string.home_duration), formatDuration(now - conn.sinceEpochMs))
                    Metric(stringResource(R.string.home_upload), stats?.let { formatRate(it.uplinkBytesPerSecond) } ?: "—")
                    Metric(stringResource(R.string.home_download), stats?.let { formatRate(it.downlinkBytesPerSecond) } ?: "—")
                }
                if (stats != null) {
                    Text(
                        "↑ ${formatBytes(stats.uplinkTotalBytes)}   ↓ ${formatBytes(stats.downlinkTotalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            val canConnect = state.selectedProfile != null && (conn is ConnectionState.Disconnected || conn is ConnectionState.Error)
            when {
                conn.isActive || conn is ConnectionState.Starting -> Button(
                    onClick = onDisconnect,
                    enabled = conn !is ConnectionState.Stopping,
                    modifier = Modifier.widthIn(min = 200.dp).height(48.dp),
                ) { Text(stringResource(R.string.action_disconnect)) }
                else -> Button(
                    onClick = onConnect,
                    enabled = canConnect,
                    modifier = Modifier.widthIn(min = 200.dp).height(48.dp),
                ) { Text(stringResource(if (conn is ConnectionState.Error) R.string.action_retry else R.string.action_connect)) }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyProfiles() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_no_profiles_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_no_profiles_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EventsList(state: HomeUiState) {
    if (state.events.isEmpty()) return
    Text(stringResource(R.string.home_events), style = MaterialTheme.typography.labelLarge)
    HorizontalDivider()
    LazyColumn(modifier = Modifier.fillMaxWidth().heightInMax()) {
        items(state.events.take(8)) { e ->
            Text(
                text = e.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (e.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private fun Modifier.heightInMax(): Modifier = this.then(Modifier.height(160.dp))
