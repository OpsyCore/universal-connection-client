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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.LayoutDirection
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
import io.ucc.applogic.ConnectionSettings
import io.ucc.applogic.ConnectionSettings.PerAppMode
import io.ucc.applogic.ConnectionSettings.Problem
import io.ucc.app.data.ThemeMode
import io.ucc.applogic.AppLanguage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ucc.core.engine.RouteAction
import io.ucc.core.vpn.LockdownStatus
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, vm: SettingsViewModel, onBack: () -> Unit, onOpenLogs: () -> Unit, onOpenDiagnostics: () -> Unit = {}, onOpenLicenses: () -> Unit = {}) {
    val s = state.settings
    val context = LocalContext.current
    var showApps by remember { mutableStateOf(false) }
    var ruleEditor by remember { mutableStateOf<ConnectionSettings.Rule?>(null) }
    var newRule by remember { mutableStateOf(false) }
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

            // ---------------------------------------------------------------- Connection
            Section(stringResource(R.string.settings_section_connection))
            SwitchRow(stringResource(R.string.settings_ipv6), stringResource(R.string.settings_ipv6_help), s.ipv6, vm::setIpv6)
            OutlinedTextField(
                value = s.mtu.toString(), onValueChange = vm::setMtu, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_mtu)) },
                isError = Problem.MtuOutOfRange in state.problems,
                supportingText = { Text(stringResource(R.string.settings_mtu_help, ConnectionSettings.MTU_RANGE.first, ConnectionSettings.MTU_RANGE.last)) },
            )

            // ---------------------------------------------------------------- Routing
            Section(stringResource(R.string.settings_section_routing))
            SwitchRow(stringResource(R.string.settings_bypass_private), stringResource(R.string.settings_bypass_private_help), s.bypassPrivate, vm::setBypassPrivate)
            Text(stringResource(R.string.settings_rules_title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_rules_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.rules.forEachIndexed { index, rule ->
                val invalid = state.problems.filterIsInstance<Problem.RuleItemInvalid>().filter { it.ruleId == rule.id }.map { it.item }
                RuleCard(rule, index, s.rules.size, invalid, vm, onEdit = { ruleEditor = rule })
            }
            OutlinedButton(onClick = { newRule = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_rule_add)) }
            Text(stringResource(R.string.settings_rules_final), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.capabilities?.perAppRouting == true) {
                Text(stringResource(R.string.settings_section_per_app), style = MaterialTheme.typography.bodyLarge)
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

            // ---------------------------------------------------------------- DNS
            Section(stringResource(R.string.settings_section_dns))
            val remoteProblem = when {
                Problem.RemoteDnsInvalid in state.problems -> R.string.settings_dns_invalid
                Problem.RemoteDnsPrivate in state.problems -> R.string.settings_dns_remote_private
                else -> null
            }
            OutlinedTextField(
                value = s.remoteDns, onValueChange = vm::setRemoteDns, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_remote_dns)) },
                isError = remoteProblem != null,
                supportingText = { Text(stringResource(remoteProblem ?: R.string.settings_remote_dns_help)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ConnectionSettings.REMOTE_DNS_PRESETS.forEach { (label, spec) ->
                    FilterChip(selected = s.remoteDns == spec, onClick = { vm.setRemoteDns(spec) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) })
                }
            }
            val directProblem = when {
                Problem.DirectDnsInvalid in state.problems -> R.string.settings_dns_invalid
                Problem.RemoteEqualsDirect in state.problems -> R.string.settings_dns_same_warning
                else -> null
            }
            OutlinedTextField(
                value = s.directDns.orEmpty(), onValueChange = vm::setDirectDns, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.settings_direct_dns)) },
                placeholder = { Text(stringResource(R.string.settings_direct_dns_placeholder)) },
                isError = Problem.DirectDnsInvalid in state.problems,
                supportingText = { Text(stringResource(directProblem ?: R.string.settings_direct_dns_help)) },
            )
            Text(stringResource(R.string.settings_dns_hijack_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_dns_no_leak_claim), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---------------------------------------------------------------- VPN
            Section(stringResource(R.string.settings_section_vpn))
            SwitchRow(stringResource(R.string.settings_strict_route), stringResource(R.string.settings_strict_route_help), s.strictRoute, vm::setStrictRoute)
            KillSwitchCard(state.lockdown) {
                runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }

            // ---------------------------------------------------------------- Diagnostics
            Section(stringResource(R.string.settings_section_diagnostics))
            Text(stringResource(R.string.settings_log_level), style = MaterialTheme.typography.bodyMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ConnectionSettings.LogLevel.entries.forEachIndexed { i, lvl ->
                    SegmentedButton(selected = s.logLevel == lvl, onClick = { vm.setLogLevel(lvl) }, shape = SegmentedButtonDefaults.itemShape(i, ConnectionSettings.LogLevel.entries.size)) { Text(lvl.name.lowercase()) }
                }
            }
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_open_logs) + " (${state.logCount})") }
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diagnostics_title)) }

            // ---------------------------------------------------------------- Appearance
            Section(stringResource(R.string.settings_section_appearance))
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(selected = state.theme == mode, onClick = { vm.setTheme(mode) }, shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                        Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.settings_theme_system; ThemeMode.LIGHT -> R.string.settings_theme_light; ThemeMode.DARK -> R.string.settings_theme_dark }), maxLines = 1)
                    }
                }
            }
            val language by vm.language.collectAsStateWithLifecycle()
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEachIndexed { i, lang ->
                    SegmentedButton(selected = language == lang, onClick = { vm.setLanguage(lang) }, shape = SegmentedButtonDefaults.itemShape(i, AppLanguage.entries.size)) {
                        Text(stringResource(when (lang) { AppLanguage.SYSTEM -> R.string.settings_language_system; AppLanguage.ENGLISH -> R.string.settings_language_en; AppLanguage.PERSIAN -> R.string.settings_language_fa }), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // ---------------------------------------------------------------- Data
            Section(stringResource(R.string.settings_section_data))
            Text(stringResource(R.string.settings_data_storage_info), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = vm::clearLogs, enabled = state.logCount > 0, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logs_clear)) }

            // ---------------------------------------------------------------- About
            Section(stringResource(R.string.settings_section_about))
            AboutCard(state, onOpenLicenses)
        }
    }
    if (showApps) AppPickerDialog(selected = s.perAppPackages, onToggle = vm::togglePackage, onClose = { showApps = false })
    if (newRule) RuleDialog(null, onDismiss = { newRule = false }) { action, text -> vm.addRule(action, text); newRule = false }
    ruleEditor?.let { r -> RuleDialog(r, onDismiss = { ruleEditor = null }) { action, text -> vm.editRule(r.id, action, text); ruleEditor = null } }
}

@Composable
private fun AboutCard(state: SettingsUiState, onOpenLicenses: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var dialog by remember { mutableStateOf<Int?>(null) } // string id of body to show
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The official product name is not translated.
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            AboutLine(stringResource(R.string.about_version), state.appVersion)
            AboutLine(stringResource(R.string.about_build), state.appBuild)
            AboutLine(stringResource(R.string.about_core), state.coreLabel)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            AboutAction(stringResource(R.string.settings_licences), onOpenLicenses)
            AboutAction(stringResource(R.string.about_source)) { runCatching { uriHandler.openUri(state.sourceUrl) } }
            AboutAction(stringResource(R.string.about_privacy)) { dialog = R.string.about_privacy_body }
            AboutAction(stringResource(R.string.about_legal)) { dialog = R.string.about_legal_body }
        }
    }
    dialog?.let { body ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (body == R.string.about_privacy_body) R.string.about_privacy else R.string.about_legal)) },
            text = { Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.done_action_close)) } },
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AboutAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun KillSwitchCard(status: LockdownStatus?, onOpenSystemSettings: () -> Unit) {
    val (title, body, container) = when {
        status == null -> Triple(R.string.settings_killswitch_unknown_title, R.string.settings_killswitch_unknown_body, MaterialTheme.colorScheme.surfaceVariant)
        !status.supported -> Triple(R.string.settings_killswitch_unsupported_title, R.string.settings_killswitch_unsupported_body, MaterialTheme.colorScheme.surfaceVariant)
        status.lockdown -> Triple(R.string.settings_killswitch_on_title, R.string.settings_killswitch_on_body, MaterialTheme.colorScheme.primaryContainer)
        status.alwaysOn -> Triple(R.string.settings_killswitch_alwayson_title, R.string.settings_killswitch_alwayson_body, MaterialTheme.colorScheme.tertiaryContainer)
        else -> Triple(R.string.settings_killswitch_off_title, R.string.settings_killswitch_off_body, MaterialTheme.colorScheme.errorContainer)
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpenSystemSettings) { Text(stringResource(R.string.settings_killswitch_open_system)) }
        }
    }
}

@Composable
private fun RuleCard(rule: ConnectionSettings.Rule, index: Int, count: Int, invalidItems: List<String>, vm: SettingsViewModel, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(when (rule.action) { RouteAction.DIRECT -> R.string.settings_rule_direct; RouteAction.PROXY -> R.string.settings_rule_proxy; RouteAction.BLOCK -> R.string.settings_rule_block }),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (rule.action) { RouteAction.BLOCK -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.primary },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.moveRule(rule.id, up = true) }, enabled = index > 0) { Icon(Icons.Filled.KeyboardArrowUp, null) }
                IconButton(onClick = { vm.moveRule(rule.id, up = false) }, enabled = index < count - 1) { Icon(Icons.Filled.KeyboardArrowDown, null) }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, stringResource(R.string.servers_action_rename)) }
                IconButton(onClick = { vm.removeRule(rule.id) }) { Icon(Icons.Filled.Delete, stringResource(R.string.servers_action_delete)) }
                Switch(checked = rule.enabled, onCheckedChange = { vm.toggleRule(rule.id) })
            }
            Text(rule.items.joinToString(", "), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (invalidItems.isNotEmpty()) Text(stringResource(R.string.settings_rule_invalid_items, invalidItems.joinToString(", ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RuleDialog(existing: ConnectionSettings.Rule?, onDismiss: () -> Unit, onSave: (RouteAction, String) -> Unit) {
    var action by remember { mutableStateOf(existing?.action ?: RouteAction.DIRECT) }
    var text by remember { mutableStateOf(existing?.items?.joinToString("\n").orEmpty()) }
    val parsed = SettingsViewModel.parseItems(text)
    val invalid = parsed.filter { ConnectionSettings.Rule.classify(it) == ConnectionSettings.Rule.Item.Invalid }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.settings_rule_add else R.string.settings_rule_edit)) },
        confirmButton = { TextButton(onClick = { onSave(action, text) }, enabled = parsed.isNotEmpty() && invalid.isEmpty()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RouteAction.entries.forEachIndexed { i, a ->
                        SegmentedButton(selected = action == a, onClick = { action = a }, shape = SegmentedButtonDefaults.itemShape(i, RouteAction.entries.size)) {
                            Text(stringResource(when (a) { RouteAction.DIRECT -> R.string.settings_rule_direct; RouteAction.PROXY -> R.string.settings_rule_proxy; RouteAction.BLOCK -> R.string.settings_rule_block }))
                        }
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    label = { Text(stringResource(R.string.settings_rule_items)) },
                    isError = invalid.isNotEmpty(),
                    supportingText = { Text(if (invalid.isEmpty()) stringResource(R.string.settings_rule_items_help) else stringResource(R.string.settings_rule_invalid_items, invalid.joinToString(", "))) },
                )
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 12.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
    }
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
