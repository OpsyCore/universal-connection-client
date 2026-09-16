package io.ucc.app.ui.import

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.app.ui.formatBytes
import io.ucc.core.config.ConfigError
import io.ucc.core.config.ImportItem
import io.ucc.core.config.UnsupportedReason
import io.ucc.core.config.subscription.SubscriptionFetchError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConfigScreen(
    state: AddConfigUiState,
    vm: AddConfigViewModel,
    onScanQr: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state is AddConfigUiState.Preview) R.string.preview_title else R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (state is AddConfigUiState.Preview) vm.cancelPreview() else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            is AddConfigUiState.Input -> InputPhase(state, vm, onScanQr, Modifier.padding(padding))
            is AddConfigUiState.Preview -> PreviewPhase(state, vm, Modifier.padding(padding))
            is AddConfigUiState.Done -> DonePhase(state, onClose, onMore = vm::cancelPreview, Modifier.padding(padding))
        }
    }
}

@Composable
private fun InputPhase(state: AddConfigUiState.Input, vm: AddConfigViewModel, onScanQr: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = PlatformInputs.readDocument(context, uri)) {
                is PlatformInputs.FileRead.Ok -> vm.importFile(r.name, r.content)
                is PlatformInputs.FileRead.TooLarge -> vm.fileError(AddConfigError.FileTooLarge(r.limit))
                is PlatformInputs.FileRead.Failed -> vm.fileError(AddConfigError.FileUnreadable(r.reason))
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.add_tab_paste)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.add_tab_subscription)) })
        }
        if (tab == 0) {
            Text(stringResource(R.string.add_paste_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = state.text,
                onValueChange = vm::onTextChanged,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                label = { Text(stringResource(R.string.add_paste_label)) },
                enabled = !state.busy,
                minLines = 6,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = { vm.importClipboard(PlatformInputs.clipboardText(context)) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.add_action_clipboard)) }
                FilledTonalButton(onClick = onScanQr, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.add_action_qr)) }
            }
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_action_file)) }
            Button(onClick = { vm.submitText() }, enabled = !state.busy && state.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_action_parse)) }
        } else {
            OutlinedTextField(
                value = state.subscriptionUrl,
                onValueChange = vm::onSubscriptionUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.add_subscription_label)) },
                enabled = !state.busy,
                singleLine = true,
            )
            Text(stringResource(R.string.add_subscription_no_auto_update), style = MaterialTheme.typography.bodySmall)
            Button(onClick = vm::submitSubscription, enabled = !state.busy && state.subscriptionUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_subscription_fetch)) }
        }
        if (state.busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        state.error?.let { Text(it.message(context), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun PreviewPhase(state: AddConfigUiState.Preview, vm: AddConfigViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val plan = state.plan
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(state.sourceLabel.res()), style = MaterialTheme.typography.labelMedium)
        Text(
            stringResource(R.string.preview_summary, plan.newCount, plan.duplicateCount, plan.unsupportedCount, plan.invalidCount, plan.failures.size),
            style = MaterialTheme.typography.bodySmall,
        )
        state.subscription?.fetched?.info?.let { info ->
            val used = info.usedBytes; val total = info.totalBytes
            if (used != null && total != null && total > 0) Text(stringResource(R.string.preview_subscription_info, formatBytes(used), formatBytes(total)), style = MaterialTheme.typography.bodySmall)
            if (info.isExpired(System.currentTimeMillis())) Text(stringResource(R.string.preview_subscription_expired), color = MaterialTheme.colorScheme.error)
            else if (info.isQuotaExhausted()) Text(stringResource(R.string.preview_subscription_exhausted), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.selectAllSavable(true) }) { Text(stringResource(R.string.preview_select_all)) }
            TextButton(onClick = { vm.selectAllSavable(false) }) { Text(stringResource(R.string.preview_select_none)) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(plan.items, key = { it.profile.id }) { item ->
                val preview = state.previews.first { it.id == item.profile.id }
                PreviewCard(item, preview, checked = item.profile.id in state.selected, enabled = item.status.isSavable && !state.saving) { vm.toggle(item.profile.id) }
            }
            if (plan.failures.isNotEmpty()) {
                item { Text(stringResource(R.string.preview_failures_title), style = MaterialTheme.typography.titleSmall) }
                items(plan.failures) { f ->
                    Text("• ${f.error.label(context)} — ${f.snippet}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val count = plan.items.count { it.profile.id in state.selected && it.status.isSavable }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = vm::cancelPreview, enabled = !state.saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.preview_action_cancel)) }
            Button(onClick = vm::confirm, enabled = state.canSave, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.preview_action_save, count)) }
        }
    }
}

@Composable
private fun PreviewCard(item: ImportItem, p: ProfilePreview, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text("${p.protocol} · ${p.server}:${p.port}", style = MaterialTheme.typography.bodyMedium)
                Text("${stringResource(R.string.preview_label_transport)}: ${p.transport}", style = MaterialTheme.typography.bodySmall)
                Text("${stringResource(R.string.preview_label_security)}: ${p.security}", style = MaterialTheme.typography.bodySmall)
                p.details.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (p.hasCredentials) Text(stringResource(R.string.preview_credentials_set), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.insecureTls) Text(stringResource(R.string.preview_insecure_tls), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(2.dp))
                val (text, isProblem) = item.status.label(context)
                Text(text, style = MaterialTheme.typography.labelMedium, color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun DonePhase(state: AddConfigUiState.Done, onClose: () -> Unit, onMore: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.done_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.done_body, state.savedCount))
        if (state.skippedCount > 0) Text(stringResource(R.string.done_skipped, state.skippedCount), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done_action_close)) }
        OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done_action_more)) }
    }
}

// ---- mappings (no secrets anywhere in these strings) ----

private fun SourceLabel.res(): Int = when (this) {
    SourceLabel.PASTE -> R.string.preview_source_paste
    SourceLabel.CLIPBOARD -> R.string.preview_source_clipboard
    SourceLabel.QR -> R.string.preview_source_qr
    SourceLabel.FILE -> R.string.preview_source_file
    SourceLabel.SUBSCRIPTION -> R.string.preview_source_subscription
}

internal fun AddConfigError.message(c: Context): String = when (this) {
    AddConfigError.EmptyInput -> c.getString(R.string.add_error_empty)
    is AddConfigError.NothingRecognised -> c.getString(R.string.add_error_nothing_recognised) + if (unreadable > 0) " (" + c.getString(R.string.preview_failures_title).lowercase() + ": $unreadable)" else ""
    AddConfigError.ClipboardEmpty -> c.getString(R.string.add_error_clipboard_empty)
    is AddConfigError.FileUnreadable -> c.getString(R.string.add_error_file_unreadable, reason)
    is AddConfigError.FileTooLarge -> c.getString(R.string.add_error_file_too_large, formatBytes(limitBytes))
    is AddConfigError.Subscription -> when (val e = error) {
        is SubscriptionFetchError.InvalidUrl -> c.getString(R.string.add_error_sub_invalid_url)
        is SubscriptionFetchError.Http -> c.getString(R.string.add_error_sub_http, e.code)
        is SubscriptionFetchError.TooLarge -> c.getString(R.string.add_error_sub_too_large)
        is SubscriptionFetchError.Network -> c.getString(R.string.add_error_sub_network)
    }
    is AddConfigError.Unexpected -> c.getString(R.string.add_error_unexpected, kind)
}

internal fun ConfigError.label(c: Context): String = when (this) {
    is ConfigError.UnknownScheme -> c.getString(R.string.config_error_unknown_scheme)
    is ConfigError.MalformedUri -> c.getString(R.string.config_error_malformed)
    is ConfigError.MissingField -> c.getString(R.string.config_error_missing_field) + " (" + detail.substringAfter(": ") + ")"
    is ConfigError.InvalidField -> c.getString(R.string.config_error_invalid_field) + " (" + detail.substringBefore(':') + ")"
    is ConfigError.InvalidJson -> c.getString(R.string.config_error_invalid_json)
    is ConfigError.Unsupported -> c.getString(R.string.config_error_unsupported)
}

/** Returns (text, isProblem). */
internal fun ImportItem.Status.label(c: Context): Pair<String, Boolean> = when (this) {
    ImportItem.Status.New -> c.getString(R.string.preview_status_new) to false
    is ImportItem.Status.Duplicate -> c.getString(R.string.preview_status_duplicate, existingName) to true
    ImportItem.Status.DuplicateInBatch -> c.getString(R.string.preview_status_duplicate_batch) to true
    is ImportItem.Status.Unsupported -> when (reason) {
        is UnsupportedReason.Protocol -> c.getString(R.string.preview_status_unsupported_protocol, reason.detail)
        is UnsupportedReason.Transport -> c.getString(R.string.preview_status_unsupported_transport, reason.detail)
        is UnsupportedReason.Feature -> c.getString(R.string.preview_status_unsupported_feature, reason.detail)
    } to true
    is ImportItem.Status.Invalid -> c.getString(R.string.preview_status_invalid, errors.joinToString { it.label(c) }) to true
}
