package io.ucc.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.ucc.app.ui.HomeScreen
import io.ucc.app.ui.HomeViewModel
import io.ucc.app.ui.import.AddConfigScreen
import io.ucc.app.ui.import.AddConfigViewModel
import io.ucc.app.ui.scan.QrScanScreen
import io.ucc.app.ui.diagnostics.DiagnosticsScreen
import io.ucc.app.ui.diagnostics.DiagnosticsViewModel
import io.ucc.app.ui.logs.LogsScreen
import io.ucc.app.ui.logs.LogsViewModel
import io.ucc.app.ui.servers.ServersScreen
import io.ucc.app.ui.settings.SettingsScreen
import io.ucc.app.ui.settings.SettingsViewModel
import io.ucc.app.ui.servers.ServersViewModel

class MainActivity : AppCompatActivity() {

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
        SettingsViewModel.Factory(g.settingsStore, g.connectionManager, g.core.capabilities, "${g.core.descriptor.displayName} ${g.core.descriptor.version}", g.preferences, g.logBuffer, g.notices, io.ucc.core.vpn.VpnServiceRegistry.lockdownStatus, BuildConfig.VERSION_NAME, g.languageStore, "${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE} · ${BuildConfig.FLAVOR}", BuildConfig.SOURCE_URL)
    }

    private val logsViewModel: LogsViewModel by viewModels { LogsViewModel.Factory(UccApplication.graph(this).logBuffer) }

    private val diagnosticsViewModel: DiagnosticsViewModel by viewModels {
        val g = UccApplication.graph(this)
        DiagnosticsViewModel.Factory(
            g.connectionManager, g.profileStore, g.preferences, g.settingsStore, g.logBuffer,
            io.ucc.core.vpn.VpnServiceRegistry.tunState, g.networkMonitor.underlying, io.ucc.core.vpn.VpnServiceRegistry.lockdownStatus,
            g.core.capabilities, "${g.core.descriptor.displayName} ${g.core.descriptor.version}", { p -> io.ucc.core.config.CapabilityCheck(g.core.capabilities).isSupported(p) },
        )
    }

    private object Routes { const val HOME = "home"; const val ADD = "add"; const val SCAN = "scan"; const val SERVERS = "servers"; const val SETTINGS = "settings"; const val LOGS = "logs"; const val DIAGNOSTICS = "diagnostics"; const val LICENSES = "licenses" }

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
            io.ucc.app.ui.theme.UccTheme(dark = dark) {
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
                        SettingsScreen(state = state, vm = settingsViewModel, onBack = { nav.popBackStack() }, onOpenLogs = { nav.navigate(Routes.LOGS) }, onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) }, onOpenLicenses = { nav.navigate(Routes.LICENSES) })
                    }
                    composable(Routes.LICENSES) {
                        io.ucc.app.ui.settings.LicensesScreen(notices = UccApplication.graph(this@MainActivity).notices, onBack = { nav.popBackStack() })
                    }
                    composable(Routes.DIAGNOSTICS) {
                        val state by diagnosticsViewModel.state.collectAsStateWithLifecycle()
                        DiagnosticsScreen(state = state, vm = diagnosticsViewModel, onBack = { nav.popBackStack() })
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
