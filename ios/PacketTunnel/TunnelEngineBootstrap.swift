import Foundation
import IosVpn // Kotlin/Native framework export of :core:engine-singbox-apple (+ :core:ios-vpn) — Gradle framework config is Phase 8 work on a Mac

/// The principal class is the Kotlin `UccPacketTunnelProvider` itself (Kotlin/Native
/// classes deriving from Objective-C classes are final, so no Swift subclass). Phase 7
/// (and later) installs the sing-box engine here, before the system instantiates the provider:
///
///     AppleTunnelEngineBootstrap.shared.install(storage: AppGroupStorage(), libbox: AppleLibboxServiceFactory.shared, debug: false)
///
/// Until Libbox.xcframework is linked, AppleLibboxServiceFactory is Unavailable and the provider fails deterministically with
/// `ProviderStartupFailure("CoreFailure: Libbox unavailable ...")` (or, if install is never called, "no tunnel engine linked").
enum TunnelEngineBootstrap {}
