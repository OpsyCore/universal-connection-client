package io.ucc.app.ads

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * RELEASE variant: no advertising SDK, no initialisation, no view. Keeps call sites identical to the debug
 * variant while guaranteeing the shipped APK/AAB stays ad-free (see docs/PRIVACY_POLICY.md, DATA_SAFETY.md).
 */
object Ads {
    const val ENABLED: Boolean = false
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    @Composable
    fun Banner(@Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier) = Unit
}
