package io.ucc.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.app.data.ConnectionSettings
import io.ucc.app.data.ConnectionSettings.PerAppMode
import io.ucc.app.data.ConnectionSettings.Problem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, vm: SettingsViewModel, onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val s = state.settings
    var showApps by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
                actions = { TextButton(onClick = vm::resetDefaults) { Text(stringResource(R.string.settings_reset)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.tunnelActive) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(stringResource(R.string.settings_applies_on_reconnect), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            Section(stringResource(R.string.settings_section_dns))
            OutlinedTextField(
                value = s.remoteDns, onValueChange = vm::setRemoteDns, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_remote_dns)) },
                isError = Problem.RemoteDnsInvalid in state.problems,
                supportingText = { Text(stringResource(if (Problem.RemoteDnsInvalid in state.problems) R.string.settings_dns_invalid else R.string.settings_remote_dns_help)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ConnectionSettings.REMOTE_DNS_PRESETS.forEach { (label, spec) ->
                    FilterChip(selected = s.remoteDns == spec, onClick = { vm.setRemoteDns(spec) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) })
                }
            }
            OutlinedTextField(
                value = s.directDns.orEmpty(), onValueChange = vm::setDirectDns, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_direct_dns)) },
                placeholder = { Text(stringResource(R.string.settings_direct_dns_placeholder)) },
                isError = Problem.DirectDnsInvalid in state.problems,
                supportingText = { Text(stringResource(if (Problem.DirectDnsInvalid in state.problems) R.string.settings_dns_invalid else R.string.settings_direct_dns_help)) },
            )
            Text(stringResource(R.string.settings_dns_no_leak_claim), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section(stringResource(R.string.settings_section_routing))
            SwitchRow(stringResource(R.string.settings_bypass_private), stringResource(R.string.settings_bypass_private_help), s.bypassPrivate, vm::setBypassPrivate)
            SwitchRow(stringResource(R.string.settings_strict_route), stringResource(R.string.settings_strict_route_help), s.strictRoute, vm::setStrictRoute)
            SwitchRow(stringResource(R.string.settings_ipv6), stringResource(R.string.settings_ipv6_help), s.ipv6, vm::setIpv6)
            OutlinedTextField(
                value = s.mtu.toString(), onValueChange = vm::setMtu, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_mtu)) },
                isError = Problem.MtuOutOfRange in state.problems,
                supportingText = { Text(stringResource(R.string.settings_mtu_help, ConnectionSettings.MTU_RANGE.first, ConnectionSettings.MTU_RANGE.last)) },
            )

            if (state.capabilities?.perAppRouting == true) {
                Section(stringResource(R.string.settings_section_per_app))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PerAppMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(selected = s.perAppMode == mode, onClick = { vm.setPerAppMode(mode) }, shape = SegmentedButtonDefaults.itemShape(i, PerAppMode.entries.size)) {
                            Text(stringResource(when (mode) { PerAppMode.OFF -> R.string.settings_per_app_off; PerAppMode.INCLUDE -> R.string.settings_per_app_include; PerAppMode.EXCLUDE -> R.string.settings_per_app_exclude }))
                        }
                    }
                }
                if (s.perAppMode != PerAppMode.OFF) {
                    OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_per_app_choose, s.perAppPackages.size))
                    }
                    if (s.perAppPackages.isEmpty()) Text(stringResource(R.string.settings_per_app_none_selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Section(stringResource(R.string.settings_section_diagnostics))
            Text(stringResource(R.string.settings_log_level), style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ConnectionSettings.LogLevel.entries.forEachIndexed { i, lvl ->
                    SegmentedButton(selected = s.logLevel == lvl, onClick = { vm.setLogLevel(lvl) }, shape = SegmentedButtonDefaults.itemShape(i, ConnectionSettings.LogLevel.entries.size)) { Text(lvl.name.lowercase()) }
                }
            }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_open_logs)) }
            Text(stringResource(R.string.home_core) + ": " + state.coreLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showApps) AppPickerDialog(selected = s.perAppPackages, onToggle = vm::togglePackage, onClose = { showApps = false })
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AppPickerDialog(selected: Set<String>, onToggle: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var includeSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(includeSystem) { apps = InstalledApps.load(context, includeSystem) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.settings_per_app_title)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.done_action_close)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.servers_search)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSystem, onCheckedChange = { includeSystem = it })
                    Text(stringResource(R.string.settings_per_app_show_system), style = MaterialTheme.typography.bodySmall)
                }
                val list = apps
                if (list == null) CircularProgressIndicator()
                else LazyColumn(Modifier.fillMaxWidth()) {
                    val q = query.trim().lowercase()
                    items(list.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = app.packageName in selected, onCheckedChange = { onToggle(app.packageName) })
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
    )
}
