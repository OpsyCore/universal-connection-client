package io.ucc.core.singbox.apple

import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreDescriptor
import io.ucc.core.engine.CoreFactory
import io.ucc.core.engine.CorePlatform
import io.ucc.core.engine.ThirdPartyNotice
import io.ucc.core.engine.manager.Clock
import io.ucc.core.singbox.SingBoxCapabilities

/** sing-box on Apple through the core-agnostic [CoreFactory] boundary. Same id as Android so settings/logs line up. */
public class AppleSingBoxCoreFactory(
    private val libbox: LibboxServiceFactory,
    private val clock: Clock,
) : CoreFactory {
    override val id: String = ID

    override val descriptor: CoreDescriptor
        get() = CoreDescriptor(id, "sing-box", (libbox.available as? LibboxAvailability.Available)?.version ?: "unavailable")

    override val capabilities: CoreCapabilities = SingBoxCapabilities.capabilities

    override val notices: List<ThirdPartyNotice> = listOf(
        ThirdPartyNotice(
            "sing-box", LibboxMetadata.SING_BOX_VERSION, "GPL-3.0-or-later", "https://github.com/SagerNet/sing-box",
            "In addition, no derivative work may use the name or imply association with this application without prior consent.",
        ),
    )

    override fun create(platform: CorePlatform): CoreAdapter = AppleSingBoxCoreAdapter(
        libbox = libbox,
        setup = LibboxSetup(
            basePath = platform.workingDirectory,
            workingPath = platform.workingDirectory + "/singbox",
            tempPath = platform.cacheDirectory,
            debug = platform.debug,
        ),
        clock = clock,
    )

    public companion object { public const val ID: String = "singbox" }
}
