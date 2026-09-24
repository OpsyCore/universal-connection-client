package io.ucc.core.singbox.apple

import io.ucc.core.engine.TunProvider

/**
 * THE Libbox integration point. This is the only file in the repository allowed to
 * import the `Libbox.xcframework` cinterop (`import cocoapods.Libbox.*` / `import Libbox.*`).
 *
 * Phase 7 state: no framework is linked (no retained artifact, `libbox-apple.sha256` =
 * unpinned), so every call reports [LibboxAvailability.Unavailable]. When the real
 * framework is available, replace the bodies below with the verified calls — same
 * sequence as the Android adapter against the same sing-box version:
 *
 *   setup   → Libbox.setup(basePath, workingPath, tempPath, debug)
 *   create  → CommandServer(handler, platformInterface).start()   (PlatformInterface.openTun → tun.openTun)
 *   checkConfig / startOrReload / closeService / close → CommandServer equivalents
 *
 * Do NOT stub these with fake successes: the extension must fail deterministically
 * ("ProviderStartupFailure") until sing-box really runs.
 */
public object AppleLibboxServiceFactory : LibboxServiceFactory {
    private const val REASON = "Libbox.xcframework not linked (sing-box ${LibboxMetadata.SING_BOX_VERSION}, pin unpinned)"

    override val available: LibboxAvailability = LibboxAvailability.Unavailable(REASON)

    override fun setup(setup: LibboxSetup): Unit = throw LibboxUnavailableException(REASON)

    override fun create(listener: LibboxListener, tun: TunProvider): LibboxService = throw LibboxUnavailableException(REASON)
}
