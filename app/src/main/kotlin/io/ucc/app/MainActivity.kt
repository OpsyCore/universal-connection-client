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
import io.ucc.app.ui.HomeScreen
import io.ucc.app.ui.HomeViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels {
        val g = UccApplication.graph(this)
        HomeViewModel.Factory(g.connectionManager, g.profileStore, g.preferences, g.core.descriptor.displayName, g.core.descriptor.version)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val context = LocalContext.current
            val scheme = when {
                android.os.Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
                android.os.Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        viewModel.connectSelected()
                    } else {
                        Toast.makeText(this, R.string.error_vpn_permission, Toast.LENGTH_LONG).show()
                    }
                }
                HomeScreen(
                    state = state,
                    onConnect = {
                        val intent = VpnService.prepare(this)
                        if (intent == null) viewModel.connectSelected() else permissionLauncher.launch(intent)
                    },
                    onDisconnect = viewModel::disconnect,
                    onSelectProfile = viewModel::select,
                )
            }
        }
    }
}
