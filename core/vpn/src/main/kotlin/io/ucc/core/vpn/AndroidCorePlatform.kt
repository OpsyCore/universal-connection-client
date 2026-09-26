package io.ucc.core.vpn

import android.content.Context
import io.ucc.core.engine.CorePlatform
import io.ucc.core.engine.UnderlyingNetwork
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Android implementation of the host services an engine may use. Core-agnostic. */
public class AndroidCorePlatform(
    context: Context,
    override val debug: Boolean,
    networkMonitor: AndroidNetworkMonitor,
) : CorePlatform {
    private val app = context.applicationContext
    override val workingDirectory: String = File(app.filesDir, "core").apply { mkdirs() }.absolutePath
    override val cacheDirectory: String = File(app.cacheDir, "core").apply { mkdirs() }.absolutePath
    override val underlyingNetwork: StateFlow<UnderlyingNetwork?> = networkMonitor.underlying
}
