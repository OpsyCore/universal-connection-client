package io.ucc.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.ucc.app.ui.HomeScreen
import io.ucc.app.ui.HomeViewModel
import io.ucc.app.ui.import.AddConfigScreen
import io.ucc.app.ui.import.AddConfigViewModel
import io.ucc.app.ui.scan.QrScanScreen
import io.ucc.app.ui.logs.LogsScreen
import io.ucc.app.ui.logs.LogsViewModel
import io.ucc.app.ui.servers.ServersScreen
import io.ucc.app.ui.settings.SettingsScreen
import io.ucc.app.ui.settings.SettingsViewModel
import io.ucc.app.ui.servers.ServersViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        val g = UccApplication.graph(this)
        HomeViewModel.Factory(g.connectionManager, g.profileStore, g.preferences, g.core.descriptor.displayName, g.core.descriptor.version)
    }

    private val addConfigViewModel: AddConfigViewModel by viewModels {
        AddConfigViewModel.Factory(UccApplication.graph(this).importRepository)
    }

    private val serversViewModel: ServersViewModel by viewModels {
        val g = UccApplication.graph(this)
        ServersViewModel.Factory(g.serverRepository, g.subscriptionRefresher, g.preferences, g.connectionManager, g.reachabilityTester)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        val g = UccApplication.graph(this)
        SettingsViewModel.Factory(g.settingsStore, g.connectionManager, g.core.capabilities, "${g.core.descriptor.displayName} ${g.core.descriptor.version}", g.preferences, g.logBuffer, g.notices, io.ucc.core.vpn.VpnServiceRegistry.lockdownStatus, BuildConfig.VERSION_NAME)
    }

    private val logsViewModel: LogsViewModel by viewModels { LogsViewModel.Factory(UccApplication.graph(this).logBuffer) }

    private object Routes { const val HOME = "home"; const val ADD = "add"; const val SCAN = "scan"; const val SERVERS = "servers"; const val SETTINGS = "settings"; const val LOGS = "logs" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by UccApplication.graph(this).preferences.themeFlow.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                io.ucc.app.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                io.ucc.app.data.ThemeMode.LIGHT -> false
                io.ucc.app.data.ThemeMode.DARK -> true
            }
            val context = LocalContext.current
            val scheme = when {
                android.os.Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
                android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.HOME) {
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                            if (result.resultCode == Activity.RESULT_OK) {
                                viewModel.connectSelected()
                            } else {
                                Toast.makeText(this@MainActivity, R.string.error_vpn_permission, Toast.LENGTH_LONG).show()
                            }
                        }
                        HomeScreen(
                            state = state,
                            onConnect = {
                                val intent = VpnService.prepare(this@MainActivity)
                                if (intent == null) viewModel.connectSelected() else permissionLauncher.launch(intent)
                            },
                            onDisconnect = viewModel::disconnect,
                            onSelectProfile = viewModel::select,
                            onAddConfig = { addConfigViewModel.cancelPreview(); nav.navigate(Routes.ADD) },
                            onOpenServers = { nav.navigate(Routes.SERVERS) },
                            onDismissStoreProblem = viewModel::dismissStoreProblem,
                            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                            onOpenLogs = { nav.navigate(Routes.LOGS) },
                        )
                    }
                    composable(Routes.SETTINGS) {
                        val state by settingsViewModel.state.collectAsStateWithLifecycle()
                        SettingsScreen(state = state, vm = settingsViewModel, onBack = { nav.popBackStack() }, onOpenLogs = { nav.navigate(Routes.LOGS) })
                    }
                    composable(Routes.LOGS) {
                        val state by logsViewModel.state.collectAsStateWithLifecycle()
                        LogsScreen(state = state, vm = logsViewModel, onBack = { nav.popBackStack() })
                    }
                    composable(Routes.SERVERS) {
                        val state by serversViewModel.state.collectAsStateWithLifecycle()
                        ServersScreen(
                            state = state,
                            vm = serversViewModel,
                            onBack = { nav.popBackStack() },
                            onAddConfig = { addConfigViewModel.cancelPreview(); nav.navigate(Routes.ADD) },
                        )
                    }
                    composable(Routes.ADD) {
                        val state by addConfigViewModel.state.collectAsStateWithLifecycle()
                        AddConfigScreen(
                            state = state,
                            vm = addConfigViewModel,
                            onScanQr = { nav.navigate(Routes.SCAN) },
                            onClose = { nav.popBackStack(Routes.HOME, inclusive = false) },
                        )
                    }
                    composable(Routes.SCAN) {
                        QrScanScreen(
                            onResult = { payload ->
                                addConfigViewModel.importQr(payload)
                                nav.popBackStack(Routes.ADD, inclusive = false)
                            },
                            onClose = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
