package io.ucc.core.singbox.android

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/**
 * One-time process-wide libbox initialisation. Must run before any other
 * libbox call (in both the UI process and the VPN service process if they
 * are ever separated).
 */
public object LibboxRuntime {
    @Volatile private var initialised = false

    public fun ensureInitialised(context: Context, debug: Boolean) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val app = context.applicationContext
            val base = app.filesDir
            val working = File(app.filesDir, "singbox").apply { mkdirs() }
            val temp = app.cacheDir
            Libbox.setup(
                SetupOptions().also {
                    it.basePath = base.path
                    it.workingPath = working.path
                    it.tempPath = temp.path
                    it.fixAndroidStack = true
                    it.logMaxLines = 2000
                    it.debug = debug
                },
            )
            // The redirected stderr captures Go panics for diagnostics; it lives in cache and is bounded by the OS.
            runCatching { Libbox.redirectStderr(File(temp, "libbox-stderr.log").path) }
            initialised = true
        }
    }

    public val version: String
        get() = runCatching { Libbox.version() }.getOrDefault("unknown")
}
