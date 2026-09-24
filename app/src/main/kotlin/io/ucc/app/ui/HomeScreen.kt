package io.ucc.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import io.ucc.applogic.SmartConnectionCoordinator
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.ucc.app.R
import io.ucc.app.ui.theme.StateColors
import io.ucc.core.engine.ConnectionState
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.delay

/**
 * Home. Layout contract (see docs/UI.md):
 *  - Top: compact header with app identity and an always-visible Settings action.
 *  - Middle: hero connection card + secondary metrics + selected/other servers.
 *  - Bottom: Material NavigationBar (respects navigation-bar insets) and an
 *    Add FAB placed by Scaffold in the FAB slot, i.e. *above* the bar.
 *    Scaffold guarantees the FAB is laid out above `bottomBar` and the content
 *    receives padding for both, so nothing can sit on top of a nav item.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onSelectSmart: () -> Unit = {},
    onOpenSmart: () -> Unit = {},
    onDismissSmartPhase: () -> Unit = {},
    onAddConfig: () -> Unit = {},
    onOpenServers: () -> Unit = {},
    onDismissStoreProblem: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onMeasurePing: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeThen: (() -> Unit) -> () -> Unit = { action -> { scope.launch { drawerState.close() }; action() } }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HomeDrawer(
                state = state,
                onOpenServers = closeThen(onOpenServers), onOpenSmart = closeThen(onOpenSmart), onAddConfig = closeThen(onAddConfig),
                onOpenSettings = closeThen(onOpenSettings), onOpenLogs = closeThen(onOpenLogs),
                onOpenDiagnostics = closeThen(onOpenDiagnostics), onOpenLicenses = closeThen(onOpenLicenses),
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Header handles the status-bar inset itself; bottom bar handles the nav-bar inset.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { HomeHeader(state, onOpenMenu = { scope.launch { drawerState.open() } }) },
        bottomBar = { HomeBottomBar(onOpenServers = onOpenServers, onOpenLogs = onOpenLogs, onOpenSettings = onOpenSettings) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddConfig,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("home_fab_add"),
            ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_config)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), // bottom clears the FAB
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.storeProblem?.let { problem ->
                item(key = "store-problem") {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.home_store_unreadable_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.home_store_unreadable_body, problem.quarantinedFileName), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onDismissStoreProblem) { Text(stringResource(R.string.action_dismiss)) }
                        }
                    }
                }
            }
            item(key = "hero") { HeroCard(state, onConnect, onDisconnect, onOpenLogs, onAddConfig, onDismissSmartPhase, onMeasurePing) }
            item(key = "metrics") { MetricsRow(state) }
            if (io.ucc.app.BuildConfig.VIP_URL.isNotBlank()) item(key = "vip") { io.ucc.app.ui.components.VipCard() }
            item(key = "servers-header") {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_servers).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (state.profiles.isNotEmpty()) TextButton(onClick = onOpenServers, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(R.string.home_see_all, state.profiles.size))
                    }
                }
            }
            if (state.profiles.isEmpty()) {
                item(key = "empty") { EmptyServers(onAddConfig) }
            } else {
                item(key = "smart") { SmartRow(state, onClick = onSelectSmart, onDetails = onOpenSmart) }
                items(state.profiles.take(MAX_HOME_SERVERS), key = { it.id }) { p ->
                    ServerRow(p, selected = !state.smartMode && p.id == state.selectedProfile?.id, active = p.id == state.connection.profileIdOrNull && state.connection.isActive, recommended = p.id == state.recommendedProfile?.id, onClick = { onSelectProfile(p.id) })
                }
            }
            // Debug builds only: Google test banner for placement preview. Release: Ads.ENABLED = false, nothing rendered.
            if (io.ucc.app.ads.Ads.ENABLED) item(key = "ad-banner") { io.ucc.app.ads.Ads.Banner(Modifier.fillMaxWidth()) }
        }
    }
    }
}

// ------------------------------------------------------------------ ping chip

/**
 * Real end-to-end latency of the active tunnel (one HTTP fetch through the core). Tap = measure again.
 * Shows a spinner while measuring and "—" when the probe failed; never shows a stale number as fresh
 * (the value is dropped when the tunnel goes down).
 */
@Composable
private fun PingChip(ping: TunnelPing, onMeasure: () -> Unit) {
    val measuring = ping is TunnelPing.Measuring
    val label = when (ping) {
        is TunnelPing.Result -> stringResource(R.string.home_ping_ms, ping.rttMs)
        TunnelPing.Failed -> stringResource(R.string.home_ping_failed)
        TunnelPing.Measuring -> stringResource(R.string.home_ping_measuring)
        TunnelPing.Idle -> stringResource(R.string.home_ping_tap)
    }
    val tint = when {
        ping is TunnelPing.Result && ping.rttMs < 300 -> StateColors.connected
        ping is TunnelPing.Result && ping.rttMs < 800 -> MaterialTheme.colorScheme.tertiary
        ping is TunnelPing.Result || ping is TunnelPing.Failed -> StateColors.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onMeasure, enabled = !measuring, shape = CircleShape,
        color = tint.copy(alpha = 0.12f), contentColor = tint,
        modifier = Modifier.height(28.dp).testTag("home_ping"),
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (measuring) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tint)
            else Icon(Icons.Filled.NetworkPing, contentDescription = stringResource(R.string.home_ping_refresh), modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

// ------------------------------------------------------------------ drawer

/**
 * Navigation drawer (v1.0.3). Only destinations that exist in the app are listed — no placeholder rows.
 * (Per-app routing, routing rules, DNS etc. live inside Settings; backup/restore and update checks are not features of this app.)
 */
@Composable
private fun HomeDrawer(
    state: HomeUiState,
    onOpenServers: () -> Unit, onOpenSmart: () -> Unit, onAddConfig: () -> Unit,
    onOpenSettings: () -> Unit, onOpenLogs: () -> Unit, onOpenDiagnostics: () -> Unit, onOpenLicenses: () -> Unit,
) {
    val context = LocalContext.current
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${state.coreName} ${state.coreVersion}".trim(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        @Composable fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
            NavigationDrawerItem(
                icon = { Icon(icon, contentDescription = null) }, label = { Text(stringResource(label)) }, selected = false, onClick = onClick,
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
        item(Icons.Filled.Storage, R.string.home_servers, onOpenServers)
        item(Icons.Filled.Add, R.string.home_add_config, onAddConfig)
        item(Icons.Filled.AutoAwesome, R.string.drawer_smart, onOpenSmart)
        item(Icons.Filled.Settings, R.string.home_settings, onOpenSettings)
        Spacer(Modifier.height(8.dp)); HorizontalDivider(Modifier.padding(horizontal = 16.dp)); Spacer(Modifier.height(8.dp))
        item(Icons.Outlined.Description, R.string.logs_title, onOpenLogs)
        item(Icons.Filled.BugReport, R.string.drawer_diagnostics, onOpenDiagnostics)
        item(Icons.Filled.Info, R.string.drawer_about, onOpenLicenses)
        if (io.ucc.app.BuildConfig.VIP_URL.isNotBlank()) {
            item(Icons.Filled.Campaign, R.string.vip_title) {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(io.ucc.app.BuildConfig.VIP_URL))) } catch (_: android.content.ActivityNotFoundException) { }
            }
        }
    }
}

private const val MAX_HOME_SERVERS = 6

// ------------------------------------------------------------------ header

@Composable
private fun HomeHeader(state: HomeUiState, onOpenMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(16.dp)) {
                drawCircle(color = Color(0xFF3CC8C2), style = Stroke(width = 2.5f))
                drawCircle(color = Color(0xFF3CC8C2), radius = size.minDimension * 0.18f)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${state.coreName} ${state.coreVersion}".trim(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onOpenMenu, modifier = Modifier.size(48.dp).testTag("home_menu")) {
            Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.home_menu))
        }
    }
}

// ------------------------------------------------------------------ hero

@Composable
private fun HeroCard(state: HomeUiState, onConnect: () -> Unit, onDisconnect: () -> Unit, onOpenLogs: () -> Unit, onAddConfig: () -> Unit, onDismissSmartPhase: () -> Unit, onMeasurePing: () -> Unit = {}) {
    val context = LocalContext.current
    val conn = state.connection
    val accent = when (conn) {
        is ConnectionState.Connected -> StateColors.connected
        is ConnectionState.Reconnecting -> StateColors.reconnecting
        is ConnectionState.Error -> StateColors.error
        is ConnectionState.Starting, is ConnectionState.Connecting, is ConnectionState.Stopping -> StateColors.transitioning
        ConnectionState.Disconnected -> StateColors.idle
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Status pill — always present, content animates.
            StatusPill(conn, accent)
            Spacer(Modifier.height(20.dp))

            val smartBusy = state.smartPhase is SmartConnectionCoordinator.Phase.Measuring
            PowerButton(conn = conn, accent = accent, enabled = !smartBusy && (state.selectedProfile != null || conn.isActive || conn is ConnectionState.Starting),
                onClick = { if (conn.isActive || conn is ConnectionState.Starting) onDisconnect() else onConnect() })
            Spacer(Modifier.height(14.dp))

            // Action label under the button — stable slot.
            val label = when {
                conn is ConnectionState.Stopping -> stringResource(R.string.state_stopping)
                conn.isActive || conn is ConnectionState.Starting -> stringResource(R.string.action_disconnect)
                conn is ConnectionState.Error -> stringResource(R.string.action_retry)
                else -> stringResource(R.string.action_connect)
            }
            Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))

            // Selected server slot — stable height, never hidden.
            if (state.smartMode) SmartSlot(state, onDismissSmartPhase) else SelectedServerSlot(state.selectedProfile, conn, onAddConfig)

            // Detail slot: error message / reconnect reason / connected-since. Fixed minimum height keeps the card from jumping.
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(44.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = conn, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, contentKey = { it::class }, label = "detail") { target ->
                    when (target) {
                        is ConnectionState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(target.error.userMessage(context), style = MaterialTheme.typography.bodySmall, color = StateColors.error, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                target.error.userHint(context)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                                TextButton(onClick = onOpenLogs, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) { Text(stringResource(R.string.home_view_logs), style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        is ConnectionState.Reconnecting -> Text(target.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        is ConnectionState.Connected -> {
                            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                            LaunchedEffect(target.sinceEpochMs) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.home_connected_for, formatDuration(now - target.sinceEpochMs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PingChip(state.tunnelPing, onMeasurePing)
                            }
                        }
                        ConnectionState.Disconnected -> Text(
                            if (state.selectedProfile == null) stringResource(R.string.home_hint_select) else stringResource(R.string.home_hint_tap_connect),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                        else -> Text(state.events.lastOrNull()?.message ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(conn: ConnectionState, accent: Color) {
    val context = LocalContext.current
    Surface(shape = CircleShape, color = accent.copy(alpha = 0.12f), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pulse = if (conn.isTransitioning) {
                rememberInfiniteTransition(label = "pulse").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseA").value
            } else 1f
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent.copy(alpha = pulse)))
            Text(conn.label(context), style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PowerButton(conn: ConnectionState, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val cd = stringResource(if (conn.isActive || conn is ConnectionState.Starting) R.string.action_disconnect else R.string.action_connect)
    val ringAlpha by animateFloatAsState(if (conn is ConnectionState.Connected) 1f else if (conn.isTransitioning) 0.7f else 0.35f, tween(400), label = "ring")
    val sweep = if (conn.isTransitioning) rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(1200)), label = "spinA").value else 0f
    Box(
        Modifier
            .size(148.dp)
            .semantics { contentDescription = cd }
            .testTag("home_power"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(color = accent.copy(alpha = 0.18f), startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            if (conn.isTransitioning) {
                drawArc(color = accent.copy(alpha = ringAlpha), startAngle = sweep - 90f, sweepAngle = 110f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            } else {
                drawArc(color = accent.copy(alpha = ringAlpha), startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            }
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = if (conn is ConnectionState.Connected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(112.dp).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(44.dp))
            }
        }
    }
}

@Composable
private fun SelectedServerSlot(profile: ConnectionProfile?, conn: ConnectionState, onAddConfig: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProtocolBadge(profile?.protocol?.name ?: "—")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(profile?.let { it.name.ifBlank { it.address } } ?: stringResource(R.string.home_none_selected), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (profile == null) Text(stringResource(R.string.home_hint_select_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                else TechnicalText("${profile.address}:${profile.port}" + securitySuffix(profile))
            }
            if (profile == null) TextButton(onClick = onAddConfig, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.home_add_config)) }
            else if (conn is ConnectionState.Connected) Icon(Icons.Filled.Check, contentDescription = null, tint = StateColors.connected, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Hero slot in Smart mode. Shows the server Smart is using / would use and the
 * live phase (measuring, failing over, nothing healthy). Same height class as
 * [SelectedServerSlot] so the card does not jump when the mode changes.
 */
@Composable
private fun SmartSlot(state: HomeUiState, onDismissPhase: () -> Unit) {
    val conn = state.connection
    val activeId = conn.profileIdOrNull.takeIf { conn.isActive || conn is ConnectionState.Starting }
    val shown = activeId?.let { id -> state.profiles.firstOrNull { it.id == id } } ?: state.recommendedProfile
    val phase = state.smartPhase
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.smart_title), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                val line = when (phase) {
                    is SmartConnectionCoordinator.Phase.Measuring -> stringResource(R.string.smart_phase_measuring, phase.count)
                    is SmartConnectionCoordinator.Phase.FailingOver -> stringResource(R.string.smart_phase_failover, state.profiles.firstOrNull { it.id == phase.toId }?.name ?: "")
                    SmartConnectionCoordinator.Phase.NoHealthyServer -> stringResource(R.string.smart_verdict_none)
                    SmartConnectionCoordinator.Phase.NoCandidates -> stringResource(R.string.smart_verdict_no_servers)
                    else -> shown?.let { p -> (if (activeId != null) "" else stringResource(R.string.health_recommended) + ": ") + p.name.ifBlank { p.address } } ?: stringResource(R.string.smart_phase_will_test)
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                phase is SmartConnectionCoordinator.Phase.Measuring -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                phase is SmartConnectionCoordinator.Phase.NoHealthyServer || phase is SmartConnectionCoordinator.Phase.NoCandidates ->
                    TextButton(onClick = onDismissPhase, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.action_dismiss)) }
                conn is ConnectionState.Connected -> Icon(Icons.Filled.Check, contentDescription = null, tint = StateColors.connected, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** "Smart" entry in the Home picker: selecting it is a mode, not a server. */
@Composable
private fun SmartRow(state: HomeUiState, onClick: () -> Unit, onDetails: () -> Unit) {
    val selected = state.smartMode
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).semantics { contentDescription = context.getString(R.string.smart_row_cd) },
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(stringResource(R.string.smart_title), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                val sub = state.recommendedProfile?.let { stringResource(R.string.smart_row_recommends, it.name.ifBlank { it.address }) } ?: stringResource(R.string.smart_row_body)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            TextButton(onClick = onDetails, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.smart_details)) }
        }
    }
}

/** Host:port / protocol lines are always laid out LTR, even in RTL locales. */
@Composable
private fun TechnicalText(text: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
    }
}

private fun securitySuffix(p: ConnectionProfile): String = when {
    p.tls.reality != null -> " · REALITY"
    p.tls.enabled -> " · TLS"
    else -> ""
}

@Composable
private fun ProtocolBadge(text: String) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
        Text(text.take(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

// ------------------------------------------------------------------ metrics

@Composable
private fun MetricsRow(state: HomeUiState) {
    val s = state.statistics?.takeIf { state.connection is ConnectionState.Connected || state.connection is ConnectionState.Reconnecting }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricTile(Modifier.weight(1f), Icons.Filled.ArrowDownward, stringResource(R.string.home_download), s?.let { formatRate(it.downlinkBytesPerSecond) } ?: "—", s?.let { formatBytes(it.downlinkTotalBytes) })
        MetricTile(Modifier.weight(1f), Icons.Filled.ArrowUpward, stringResource(R.string.home_upload), s?.let { formatRate(it.uplinkBytesPerSecond) } ?: "—", s?.let { formatBytes(it.uplinkTotalBytes) })
    }
}

@Composable
private fun MetricTile(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, total: String?) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(total?.let { "$label · $it" } ?: label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ------------------------------------------------------------------ servers

@Composable
private fun ServerRow(p: ConnectionProfile, selected: Boolean, active: Boolean, recommended: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProtocolBadge(p.protocol.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.name.ifBlank { p.address }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (recommended) Text(stringResource(R.string.health_recommended), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                TechnicalText("${p.address}:${p.port}${securitySuffix(p)}")
            }
            when {
                active -> Box(Modifier.size(8.dp).clip(CircleShape).background(StateColors.connected))
                selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptyServers(onAddConfig: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_no_profiles_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.home_no_profiles_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAddConfig, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.home_add_config)) }
        }
    }
}

// ------------------------------------------------------------------ bottom bar

@Composable
private fun HomeBottomBar(onOpenServers: () -> Unit, onOpenLogs: () -> Unit, onOpenSettings: () -> Unit) {
    // NavigationBar applies WindowInsets.navigationBars by default; kept explicit for clarity.
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, windowInsets = WindowInsets.navigationBars, tonalElevation = 0.dp) {
        val colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary)
        NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text(stringResource(R.string.nav_home)) }, colors = colors)
        NavigationBarItem(selected = false, onClick = onOpenServers, icon = { Icon(Icons.Filled.Storage, contentDescription = null) }, label = { Text(stringResource(R.string.home_servers)) }, colors = colors, modifier = Modifier.testTag("nav_servers"))
        NavigationBarItem(selected = false, onClick = onOpenLogs, icon = { Icon(Icons.Outlined.Description, contentDescription = null) }, label = { Text(stringResource(R.string.logs_title)) }, colors = colors, modifier = Modifier.testTag("nav_logs"))
        NavigationBarItem(selected = false, onClick = onOpenSettings, icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text(stringResource(R.string.home_settings)) }, colors = colors, modifier = Modifier.testTag("nav_settings"))
    }
}
