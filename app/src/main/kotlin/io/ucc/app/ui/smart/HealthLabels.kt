package io.ucc.app.ui.smart

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.ucc.app.R
import io.ucc.app.ui.theme.StateColors
import io.ucc.core.smart.HealthStatus
import io.ucc.core.smart.TestFailure

@Composable
fun healthLabel(s: HealthStatus): String = stringResource(
    when (s) {
        HealthStatus.HEALTHY -> R.string.health_healthy
        HealthStatus.DEGRADED -> R.string.health_degraded
        HealthStatus.OFFLINE -> R.string.health_offline
        HealthStatus.UNKNOWN -> R.string.health_unknown
    },
)

@Composable
fun failureLabel(f: TestFailure): String = stringResource(
    when (f) {
        TestFailure.TIMEOUT -> R.string.servers_reach_timeout
        TestFailure.DNS_FAILURE -> R.string.servers_reach_unresolved
        TestFailure.CONNECTION_REFUSED -> R.string.servers_reach_refused
        TestFailure.TLS_FAILURE -> R.string.health_fail_tls
        TestFailure.AUTH_FAILURE -> R.string.health_fail_auth
        TestFailure.UNSUPPORTED -> R.string.servers_reach_na
        TestFailure.NETWORK_UNAVAILABLE -> R.string.health_fail_network
        TestFailure.CANCELLED -> R.string.health_fail_cancelled
        TestFailure.UNKNOWN -> R.string.servers_reach_failed
    },
)

/** Colour is always paired with a text label; these only reinforce it. */
object HealthColors {
    @Composable
    fun forStatus(s: HealthStatus): Color = when (s) {
        HealthStatus.HEALTHY -> StateColors.connected
        HealthStatus.DEGRADED -> StateColors.reconnecting
        HealthStatus.OFFLINE -> MaterialTheme.colorScheme.error
        HealthStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    @Composable
    fun forLatency(ms: Long): Color = when {
        ms < 150 -> StateColors.connected
        ms < 400 -> StateColors.reconnecting
        else -> MaterialTheme.colorScheme.error
    }
}
