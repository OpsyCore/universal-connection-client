package io.ucc.app.ui.smart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.core.smart.Selection
import io.ucc.core.smart.ServerHealth
import java.text.DateFormat
import java.util.Date

/**
 * Smart selection details: the toggle, what Smart would choose right now and
 * *why* (real evidence per server), and a bounded "Test all". No scores or
 * stars — the ranking is explained with the measured values themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartScreen(state: SmartUiState, vm: SmartViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.smart_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    if (state.rows.isNotEmpty()) {
                        IconButton(onClick = vm::testAll, modifier = Modifier.semantics { contentDescription = context.getString(if (state.testingAll) R.string.servers_test_all_cancel else R.string.smart_test_all) }) {
                            if (state.testingAll) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "toggle") {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.smart_toggle_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.smart_toggle_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.smartMode, onCheckedChange = vm::setSmartMode, modifier = Modifier.semantics { contentDescription = context.getString(R.string.smart_toggle_title) })
                    }
                }
            }
            item(key = "verdict") { VerdictCard(state) }
            if (state.rows.isNotEmpty()) {
                item(key = "header") {
                    Text(stringResource(R.string.smart_ranking).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                items(state.rows, key = { it.candidate.profile.id }) { row -> EvidenceRow(row, state.testingAll) }
            }
            if (state.unsupportedCount > 0) {
                item(key = "unsupported") {
                    Text(pluralStringResource(R.plurals.smart_unsupported_excluded, state.unsupportedCount, state.unsupportedCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "how") {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.smart_how_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.smart_how_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.smart_how_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(state: SmartUiState) {
    val sel = state.selection
    val (title, body) = when (sel) {
        is Selection.Chosen -> {
            val p = sel.candidate.profile
            val h = sel.candidate.health
            stringResource(R.string.smart_verdict_recommended, p.name.ifBlank { p.address }) to
                listOfNotNull(healthLabel(sel.status), h.rollingLatencyMs?.let { "$it ms" }, h.availability?.let { stringResource(R.string.smart_availability, (it * 100).toInt()) }).joinToString(" · ")
        }
        is Selection.AllUnhealthy -> stringResource(R.string.smart_verdict_none) to stringResource(R.string.smart_verdict_none_body)
        is Selection.NeedsMeasurement -> stringResource(R.string.smart_verdict_no_data) to stringResource(R.string.smart_verdict_no_data_body, sel.toTest.size)
        Selection.NoCandidates, null -> stringResource(R.string.smart_verdict_no_servers) to stringResource(R.string.smart_verdict_no_servers_body)
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.transport?.let { Text(stringResource(R.string.smart_network, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EvidenceRow(row: SmartRow, testing: Boolean) {
    val p = row.candidate.profile
    val h = row.candidate.health
    val context = LocalContext.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name.ifBlank { p.address }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (row.recommended) Text(stringResource(R.string.health_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                val label = if (testing && h.lastCheckedAtEpochMs == null) stringResource(R.string.health_testing) else healthLabel(row.status)
                Text(label, style = MaterialTheme.typography.labelMedium, color = HealthColors.forStatus(row.status), modifier = Modifier.semantics { contentDescription = context.getString(R.string.health_badge_cd, label) })
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text("${p.protocol.name} · ${p.address}:${p.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
            }
            Spacer(Modifier.height(2.dp))
            if (h.lastCheckedAtEpochMs == null) {
                Text(stringResource(R.string.smart_not_enough_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                EvidenceGrid(h)
            }
        }
    }
}

@Composable
private fun EvidenceGrid(h: ServerHealth) {
    val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    val items = buildList {
        h.rollingLatencyMs?.let { add(stringResource(R.string.smart_ev_latency) to "$it ms" + (h.latencyMs?.takeIf { l -> l != it }?.let { l -> " ($l)" } ?: "")) }
        add(stringResource(R.string.smart_ev_samples) to "${h.successCount + h.failureCount}")
        h.availability?.let { add(stringResource(R.string.smart_ev_availability) to "${(it * 100).toInt()} %") }
        if (h.consecutiveFailures > 0) add(stringResource(R.string.smart_ev_streak) to "${h.consecutiveFailures}")
        h.lastSuccessAtEpochMs?.let { add(stringResource(R.string.smart_ev_last_ok) to fmt.format(Date(it))) }
        h.lastFailureAtEpochMs?.let { add(stringResource(R.string.smart_ev_last_fail) to fmt.format(Date(it)) + (h.lastFailure?.let { f -> " · " + failureLabel(f) } ?: "")) }
        h.lastNetworkTransport?.let { add(stringResource(R.string.smart_ev_network) to it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (k, v) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(v, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
