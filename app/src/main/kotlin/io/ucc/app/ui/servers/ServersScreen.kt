package io.ucc.app.ui.servers

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.app.data.diagnostics.ReachabilityTester
import io.ucc.app.data.SubscriptionRefresher
import io.ucc.app.ui.formatBytes
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionFetchError
import io.ucc.core.model.ConnectionProfile
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    state: ServersUiState,
    vm: ServersViewModel,
    onBack: () -> Unit,
    onAddConfig: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is ServersEffect.Deleted -> snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.servers_deleted, e.count, e.count))
                ServersEffect.DeleteBlockedActive -> snackbar.showSnackbar(context.getString(R.string.servers_delete_blocked_active))
                is ServersEffect.Share -> shareLinks(context, e.text)
                is ServersEffect.Refreshed -> snackbar.showSnackbar(context.getString(R.string.servers_refreshed, e.name, e.added, e.updated, e.removed))
                is ServersEffect.RefreshFailed -> snackbar.showSnackbar(context.getString(R.string.servers_refresh_failed, e.name, e.outcome.label(context)))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.servers_selected_count, state.checked.size)) },
                    navigationIcon = { IconButton(onClick = vm::exitSelection) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) } },
                    actions = {
                        IconButton(onClick = vm::checkAllVisible) { Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.preview_select_all)) }
                        IconButton(onClick = { vm.share(state.checked) }) { Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.servers_action_share)) }
                        IconButton(onClick = { vm.requestDelete(state.checked) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.servers_action_delete)) }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.servers_title)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
                    actions = {
                        if (!state.isEmpty) {
                            IconButton(onClick = vm::testVisibleReachability) {
                                if (state.testingAll) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Filled.NetworkCheck, contentDescription = stringResource(R.string.servers_test_all))
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) FloatingActionButton(onClick = onAddConfig) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_config)) }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!state.isEmpty) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.query, onValueChange = vm::onQueryChanged, modifier = Modifier.weight(1f),
                        singleLine = true, label = { Text(stringResource(R.string.servers_search)) },
                        trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { vm.onQueryChanged("") }) { Icon(Icons.Filled.Close, contentDescription = null) } },
                    )
                    FilterChip(
                        selected = state.favoritesOnly, onClick = vm::toggleFavoritesOnly,
                        label = { Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.servers_filter_favorites)) },
                    )
                }
            }
            when {
                state.isEmpty -> CenteredText(stringResource(R.string.servers_empty))
                state.nothingMatches -> CenteredText(stringResource(R.string.servers_no_match))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.groups.forEach { group ->
                        item(key = "hdr-" + (group.subscription?.id ?: "manual")) { GroupHeader(group, vm) }
                        items(group.rows, key = { it.profile.id }) { row -> ServerCard(row, state, vm) }
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    state.renaming?.let { p -> RenameDialog(p, onCancel = vm::cancelRename, onConfirm = vm::confirmRename) }
    state.confirmDelete?.let { pending -> DeleteDialog(pending, onCancel = vm::cancelDelete, onConfirm = vm::confirmDelete) }
}

@Composable
private fun CenteredText(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupHeader(group: ServerGroup, vm: ServersViewModel) {
    val sub = group.subscription
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    sub?.name ?: stringResource(R.string.servers_group_manual),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (sub != null) Text(subscriptionSubtitle(sub, LocalContext.current), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sub != null) {
                if (group.refreshing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.refresh(sub) }) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.servers_action_refresh)) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(R.string.servers_sub_auto_update)); Switch(checked = sub.autoUpdate, onCheckedChange = null) } },
                        onClick = { vm.setAutoUpdate(sub, !sub.autoUpdate) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.servers_sub_delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; vm.requestDeleteSubscription(sub) })
                }
            }
        }
    }
}

private fun subscriptionSubtitle(sub: Subscription, c: Context): String {
    val parts = ArrayList<String>()
    sub.lastFetchedAtEpochMs?.let { parts += c.getString(R.string.servers_sub_updated_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) }
    sub.lastInfo?.let { info ->
        val used = info.usedBytes; val total = info.totalBytes
        if (used != null && total != null && total > 0) parts += c.getString(R.string.preview_subscription_info, formatBytes(used), formatBytes(total))
        if (info.isExpired(System.currentTimeMillis())) parts += c.getString(R.string.servers_sub_expired)
    }
    if (sub.lastError != null) parts += c.getString(R.string.servers_sub_last_error)
    if (!sub.autoUpdate) parts += c.getString(R.string.servers_sub_auto_update_off)
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(row: ServerRow, state: ServersUiState, vm: ServersViewModel) {
    val p = row.profile
    var menu by remember { mutableStateOf(false) }
    val checked = p.id in state.checked
    Card(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (state.selectionMode) vm.toggleChecked(p.id) else vm.select(p.id) },
            onLongClick = { if (!state.selectionMode) vm.enterSelection(p.id) else vm.toggleChecked(p.id) },
        ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = if (row.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer),
        border = if (row.selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selectionMode) Checkbox(checked = checked, onCheckedChange = { vm.toggleChecked(p.id) })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.name.ifBlank { p.address }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (row.active) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.servers_active), tint = MaterialTheme.colorScheme.primary)
                    ReachabilityBadge(row)
                }
                // Technical line stays LTR in RTL locales so host:port reads correctly.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        "${p.protocol.name} · ${p.address}:${p.port}" + if (p.tls.enabled) (if (p.tls.reality != null) " · REALITY" else " · TLS") else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start,
                    )
                }
            }
            if (!state.selectionMode) {
                IconButton(onClick = { vm.toggleFavorite(p.id) }) {
                    Icon(if (p.metadata.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = stringResource(R.string.servers_action_favorite))
                }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.servers_action_test)) }, leadingIcon = { Icon(Icons.Filled.NetworkCheck, null) }, onClick = { menu = false; vm.testReachability(p.id) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.servers_action_rename)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; vm.startRename(p.id) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.servers_action_share)) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; vm.share(setOf(p.id)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.servers_action_delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; vm.requestDelete(setOf(p.id)) })
                }
            }
        }
    }
}

@Composable
private fun ReachabilityBadge(row: ServerRow) {
    if (row.testing) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); return }
    val r = row.reachability ?: return
    val (text, color) = when (r) {
        is ReachabilityTester.Result.Ok -> "${r.rttMs} ms" to when {
            r.rttMs < 150 -> io.ucc.app.ui.theme.StateColors.connected
            r.rttMs < 400 -> io.ucc.app.ui.theme.StateColors.reconnecting
            else -> MaterialTheme.colorScheme.error
        }
        ReachabilityTester.Result.Timeout -> stringResource(R.string.servers_reach_timeout) to MaterialTheme.colorScheme.error
        ReachabilityTester.Result.Refused -> stringResource(R.string.servers_reach_refused) to MaterialTheme.colorScheme.error
        ReachabilityTester.Result.Unresolved -> stringResource(R.string.servers_reach_unresolved) to MaterialTheme.colorScheme.error
        ReachabilityTester.Result.NotApplicable -> stringResource(R.string.servers_reach_na) to MaterialTheme.colorScheme.onSurfaceVariant
        is ReachabilityTester.Result.Failed -> stringResource(R.string.servers_reach_failed) to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun RenameDialog(p: ConnectionProfile, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable(p.id) { mutableStateOf(p.name) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.servers_rename_title)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DeleteDialog(pending: PendingDelete, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    val body = when (pending) {
        is PendingDelete.Profiles -> context.resources.getQuantityString(R.plurals.servers_delete_body, pending.ids.size, pending.ids.size)
        is PendingDelete.SubscriptionGroup -> context.getString(R.string.servers_delete_sub_body, pending.subscription.name, pending.memberCount)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.servers_delete_title)) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.servers_action_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Share sheet only; the text contains credentials, so it is never written to logs or persisted here. */
private fun shareLinks(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.servers_action_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun SubscriptionRefresher.Outcome.label(c: Context): String = when (this) {
    is SubscriptionRefresher.Outcome.Failed -> when (val e = error) {
        is SubscriptionFetchError.InvalidUrl -> c.getString(R.string.add_error_sub_invalid_url)
        is SubscriptionFetchError.Http -> c.getString(R.string.add_error_sub_http, e.code)
        is SubscriptionFetchError.TooLarge -> c.getString(R.string.add_error_sub_too_large)
        is SubscriptionFetchError.Network -> c.getString(R.string.add_error_sub_network)
    }
    is SubscriptionRefresher.Outcome.EmptyBody -> c.getString(R.string.servers_refresh_empty)
    SubscriptionRefresher.Outcome.NotFound -> c.getString(R.string.error_unknown)
    is SubscriptionRefresher.Outcome.Updated -> ""
}
