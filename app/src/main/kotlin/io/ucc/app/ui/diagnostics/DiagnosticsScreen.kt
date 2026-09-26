package io.ucc.app.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.ucc.app.ui.formatBytes
import io.ucc.app.ui.formatRate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, vm: DiagnosticsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
                actions = {
                    IconButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diagnostics", vm.exportText()))
                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.diagnostics_copy)) }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, vm.exportText()) }
                        context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_title)))
                    }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.logs_share)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.diagnostics_no_secrets), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Block(stringResource(R.string.diagnostics_core)) {
                Line(stringResource(R.string.diag_line_core), state.coreLabel)
                Line(stringResource(R.string.diag_line_state), state.connection.toString())
                Line(stringResource(R.string.diag_line_selected), state.selectedProfile ?: "—")
                Line(stringResource(R.string.diag_line_supported), state.selectedProfileSupported?.toString() ?: "—")
                state.capabilities?.let { Line(stringResource(R.string.diag_line_capabilities), "perApp=${it.perAppRouting} ruleSets=${it.ruleSets} reality=${it.reality} hotReload=${it.hotReload}") }
            }
            Block(stringResource(R.string.diagnostics_tun)) {
                val t = state.tun
                if (t == null) Line(stringResource(R.string.diag_line_tun), stringResource(R.string.diag_value_closed)) else {
                    Line("fd / MTU", "${t.fd} / ${t.mtu}")
                    Line(stringResource(R.string.diag_line_addresses), t.addresses.joinToString())
                    Line(stringResource(R.string.diag_line_routes), t.routes.joinToString())
                    if (t.excludedRoutes.isNotEmpty()) Line(stringResource(R.string.diag_line_excluded), t.excludedRoutes.joinToString())
                    Line(stringResource(R.string.diag_line_dns_servers), t.dnsServers.joinToString())
                    Line(stringResource(R.string.diag_line_apps), "included=${t.includedPackages} excluded=${t.excludedPackages}")
                }
            }
            Block(stringResource(R.string.diagnostics_network)) {
                val n = state.network
                Line(stringResource(R.string.diag_line_underlying), n?.let { "${it.interfaceName} (#${it.interfaceIndex})${if (it.expensive) " · " + stringResource(R.string.diag_value_metered) else ""}" } ?: stringResource(R.string.diag_value_none))
                Line(stringResource(R.string.diag_line_lockdown), state.lockdown?.let { if (!it.supported) stringResource(R.string.diag_value_lockdown_unsupported) else "alwaysOn=${it.alwaysOn} block=${it.lockdown}" } ?: stringResource(R.string.diag_value_lockdown_unknown))
            }
            Block(stringResource(R.string.diagnostics_dns_routing)) {
                val s = state.settings
                Line(stringResource(R.string.diag_line_remote_dns), s.remoteDns)
                Line(stringResource(R.string.diag_line_direct_dns), s.directDns ?: stringResource(R.string.diag_value_system_dns))
                Line(stringResource(R.string.diag_line_flags), "${s.ipv6} / ${s.strictRoute} / ${s.bypassPrivate}")
                Line(stringResource(R.string.diag_line_rules), stringResource(R.string.diag_value_rules_enabled, s.rules.count { it.enabled }, s.rules.size))
                Line(stringResource(R.string.diag_line_per_app), "${s.perAppMode} (${s.perAppPackages.size})")
                Line(stringResource(R.string.diag_line_mtu_log), "${s.mtu} / ${s.logLevel}")
            }
            Block(stringResource(R.string.diagnostics_traffic)) {
                val st = state.statistics
                if (st == null) Line(stringResource(R.string.diag_line_stats), stringResource(R.string.diag_value_no_stats)) else {
                    Line(stringResource(R.string.diag_line_rate), "↑ ${formatRate(st.uplinkBytesPerSecond)}  ↓ ${formatRate(st.downlinkBytesPerSecond)}")
                    Line(stringResource(R.string.diag_line_total), "↑ ${formatBytes(st.uplinkTotalBytes)}  ↓ ${formatBytes(st.downlinkTotalBytes)}")
                    Line(stringResource(R.string.diag_line_connections), "in=${st.connectionsIn} out=${st.connectionsOut}")
                    Line(stringResource(R.string.diag_line_memory), "${formatBytes(st.memoryBytes)} / ${st.goroutines}")
                }
            }
            Block(stringResource(R.string.diagnostics_events)) {
                Line(stringResource(R.string.diag_line_reconnects), state.reconnectEvents.toString())
                Line(stringResource(R.string.diag_line_network_events), state.networkEvents.toString())
                Line(stringResource(R.string.diag_line_errors), state.errorEvents.toString())
                Line(stringResource(R.string.diag_line_last_error), state.lastError ?: stringResource(R.string.diag_value_none))
                Line(stringResource(R.string.diag_line_log_lines), state.logCount.toString())
            }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f).padding(end = 8.dp))
        // Technical values stay LTR even in RTL locales (addresses, flags, counters).
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.58f), textAlign = TextAlign.Start)
        }
    }
}
