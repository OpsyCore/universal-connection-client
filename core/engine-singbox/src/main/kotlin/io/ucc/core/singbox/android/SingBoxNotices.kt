package io.ucc.core.singbox.android

import io.ucc.core.engine.ThirdPartyNotice

/**
 * Licences of the code compiled into `libbox.aar` (see docs/CORE_LICENSE_AUDIT.md §1.9).
 * Versions of Go sub-modules follow the pinned sing-box tag; see the go.mod of that tag.
 */
internal object SingBoxNotices {
    private const val SINGBOX_ADDITIONAL_TERMS =
        "In addition, no derivative work may use the name or imply association with this application without prior consent."

    val all: List<ThirdPartyNotice> = listOf(
        ThirdPartyNotice("sing-box", LibboxRuntime.version, "GPL-3.0-or-later", "https://github.com/SagerNet/sing-box", SINGBOX_ADDITIONAL_TERMS),
        ThirdPartyNotice("sing", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/sing"),
        ThirdPartyNotice("sing-tun", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/sing-tun"),
        ThirdPartyNotice("sing-quic", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/sing-quic"),
        ThirdPartyNotice("sing-vmess", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/sing-vmess"),
        ThirdPartyNotice("sing-shadowsocks / sing-shadowsocks2", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/sing-shadowsocks2"),
        ThirdPartyNotice("sing-mux", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/sing-mux"),
        ThirdPartyNotice("sing-shadowtls", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/sing-shadowtls"),
        ThirdPartyNotice("cronet-go", "(per sing-box go.mod)", "GPL-3.0-or-later", "https://github.com/SagerNet/cronet-go"),
        ThirdPartyNotice("quic-go (SagerNet fork)", "(per sing-box go.mod)", "MIT", "https://github.com/SagerNet/quic-go"),
        ThirdPartyNotice("wireguard-go (SagerNet fork)", "(per sing-box go.mod)", "MIT", "https://github.com/SagerNet/wireguard-go"),
        ThirdPartyNotice("gVisor (SagerNet fork)", "(per sing-box go.mod)", "Apache-2.0", "https://github.com/SagerNet/gvisor"),
        ThirdPartyNotice("utls (metacubex fork)", "(per sing-box go.mod)", "BSD-3-Clause", "https://github.com/metacubex/utls"),
        ThirdPartyNotice("tailscale (SagerNet fork)", "(per sing-box go.mod)", "BSD-3-Clause", "https://github.com/SagerNet/tailscale"),
        ThirdPartyNotice("gomobile (SagerNet fork)", "(per sing-box go.mod)", "BSD-3-Clause", "https://github.com/SagerNet/gomobile"),
        ThirdPartyNotice("Go runtime", "(CI toolchain)", "BSD-3-Clause", "https://go.dev"),
    )
}
