package io.ucc.core.singbox.android

import android.content.Context
import android.net.Network
import io.ucc.core.engine.CoreAdapter
import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreDescriptor
import io.ucc.core.engine.CoreFactory
import io.ucc.core.engine.CorePlatform
import io.ucc.core.engine.ThirdPartyNotice
import io.ucc.core.singbox.SingBoxCapabilities

/**
 * The sing-box engine as seen through the core-agnostic [CoreFactory] boundary.
 * This is the only class the application needs to reference (via
 * `CoreFactories`), and only from build-configuration code.
 */
public class SingBoxCoreFactory(context: Context) : CoreFactory {
    private val appContext = context.applicationContext

    override val id: String = "singbox"

    override val descriptor: CoreDescriptor
        get() = CoreDescriptor(id = id, displayName = "sing-box", version = LibboxRuntime.version)

    override val capabilities: CoreCapabilities = SingBoxCapabilities.capabilities

    override val notices: List<ThirdPartyNotice> = SingBoxNotices.all

    override fun create(platform: CorePlatform): CoreAdapter = SingBoxCoreAdapter(
        context = appContext,
        debug = platform.debug,
        defaultNetwork = { platform.underlyingNetwork.value?.handle as? Network },
    )
}
