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
import androidx.compose.material3.Card
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
                Line("core", state.coreLabel)
                Line("state", state.connection.toString())
                Line("selected", state.selectedProfile ?: "—")
                Line("supported by core", state.selectedProfileSupported?.toString() ?: "—")
                state.capabilities?.let { Line("capabilities", "perApp=${it.perAppRouting} ruleSets=${it.ruleSets} reality=${it.reality} hotReload=${it.hotReload}") }
            }
            Block(stringResource(R.string.diagnostics_tun)) {
                val t = state.tun
                if (t == null) Line("tun", "closed") else {
                    Line("fd / mtu", "${t.fd} / ${t.mtu}")
                    Line("addresses", t.addresses.joinToString())
                    Line("routes", t.routes.joinToString())
                    if (t.excludedRoutes.isNotEmpty()) Line("excluded", t.excludedRoutes.joinToString())
                    Line("dns servers", t.dnsServers.joinToString())
                    Line("apps", "included=${t.includedPackages} excluded=${t.excludedPackages}")
                }
            }
            Block(stringResource(R.string.diagnostics_network)) {
                val n = state.network
                Line("underlying", n?.let { "${it.interfaceName} (#${it.interfaceIndex})${if (it.expensive) " metered" else ""}" } ?: "none")
                Line("lockdown", state.lockdown?.let { if (!it.supported) "unsupported (<API 29)" else "alwaysOn=${it.alwaysOn} block=${it.lockdown}" } ?: "unknown (service not yet run)")
            }
            Block(stringResource(R.string.diagnostics_dns_routing)) {
                val s = state.settings
                Line("remote dns", s.remoteDns)
                Line("direct dns", s.directDns ?: "system (local)")
                Line("ipv6 / strict / lan bypass", "${s.ipv6} / ${s.strictRoute} / ${s.bypassPrivate}")
                Line("rules", "${s.rules.count { it.enabled }} enabled of ${s.rules.size}")
                Line("per-app", "${s.perAppMode} (${s.perAppPackages.size})")
                Line("mtu / core log", "${s.mtu} / ${s.logLevel}")
            }
            Block(stringResource(R.string.diagnostics_traffic)) {
                val st = state.statistics
                if (st == null) Line("stats", "none (core not running)") else {
                    Line("rate", "↑ ${formatRate(st.uplinkBytesPerSecond)}  ↓ ${formatRate(st.downlinkBytesPerSecond)}")
                    Line("total", "↑ ${formatBytes(st.uplinkTotalBytes)}  ↓ ${formatBytes(st.downlinkTotalBytes)}")
                    Line("connections", "in=${st.connectionsIn} out=${st.connectionsOut}")
                    Line("memory / goroutines", "${formatBytes(st.memoryBytes)} / ${st.goroutines}")
                }
            }
            Block(stringResource(R.string.diagnostics_events)) {
                Line("reconnect events", state.reconnectEvents.toString())
                Line("network events", state.networkEvents.toString())
                Line("errors", state.errorEvents.toString())
                Line("last error", state.lastError ?: "none")
                Line("log lines", state.logCount.toString())
            }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
