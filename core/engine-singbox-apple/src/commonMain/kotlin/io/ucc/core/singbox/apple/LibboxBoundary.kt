package io.ucc.core.singbox.apple

/**
 * The narrow boundary between the Apple engine and the real `Libbox.xcframework`.
 *
 * Deliberately limited to the calls the Android adapter is *verified* to use against
 * the same sing-box version (v1.13.21): `Libbox.setup`, `Libbox.checkConfig`,
 * `CommandServer.start / startOrReloadService / closeService / close`, the
 * `serviceStop` callback and the status push (`writeStatus`). Nothing else is
 * modelled, and nothing here names a Libbox symbol — the concrete implementation
 * lives in iosMain once the framework is linked ([LibboxServiceFactory.available]).
 */
public interface LibboxService {
    /** Validates [configJson] without starting anything. Throws with the core's message on rejection. */
    public fun checkConfig(configJson: String)

    /** Starts (or reloads, when already running) the service with [configJson]. Throws on failure. */
    public fun startOrReload(configJson: String)

    /** Stops the running service; the service object stays usable for another [startOrReload]. */
    public fun closeService()

    /** Releases everything (command server, sockets). The object must not be used afterwards. */
    public fun close()
}

/** Events pushed by the core through the boundary (mirrors CommandServerHandler/CommandClientHandler usage on Android). */
public interface LibboxListener {
    /** The core stopped on its own (e.g. fatal error) — the adapter must surface this, never hide it. */
    public fun onServiceStopped()

    /** Periodic traffic/status sample from the core. */
    public fun onStatus(status: LibboxStatus)

    /** Already-redacted log line from the core. */
    public fun onLog(level: Int, message: String)
}

/** Snapshot the core reports; identical fields to the Android `StatusMessage` usage. */
public data class LibboxStatus(
    val uplinkBytesPerSecond: Long,
    val downlinkBytesPerSecond: Long,
    val uplinkTotalBytes: Long,
    val downlinkTotalBytes: Long,
    val connectionsIn: Int,
    val connectionsOut: Int,
    val memoryBytes: Long,
    val goroutines: Int,
)

/** Host directories the core needs (`Libbox.setup` on Android). Platform-neutral strings. */
public data class LibboxSetup(
    val basePath: String,
    val workingPath: String,
    val tempPath: String,
    val debug: Boolean,
)

/** Whether a real Libbox is linked into this binary. */
public sealed class LibboxAvailability {
    public data class Available(val version: String) : LibboxAvailability()
    public data class Unavailable(val reason: String) : LibboxAvailability()
}

/**
 * Creates [LibboxService]s. Exactly one implementation per binary: the iosMain
 * [AppleLibboxServiceFactory] (Unavailable until the framework is linked) or a test fake.
 */
public interface LibboxServiceFactory {
    public val available: LibboxAvailability

    /**
     * One-time process-wide initialisation; must succeed before [create]. Throws
     * [LibboxUnavailableException] when [available] is [LibboxAvailability.Unavailable].
     */
    public fun setup(setup: LibboxSetup)

    /** Creates a service bound to [listener] and the platform [tun] bridge. Throws [LibboxUnavailableException] if not linked. */
    public fun create(listener: LibboxListener, tun: io.ucc.core.engine.TunProvider): LibboxService
}

/** Thrown for every Libbox call while no framework is linked. Message never contains configuration. */
public class LibboxUnavailableException(reason: String) : IllegalStateException("Libbox unavailable: $reason")
