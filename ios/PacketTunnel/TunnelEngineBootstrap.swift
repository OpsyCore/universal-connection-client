import Foundation
import IosVpn // Kotlin/Native framework exported from :core:ios-vpn (Phase 7 adds the Gradle framework config + Libbox)

/// The principal class is the Kotlin `UccPacketTunnelProvider` itself (Kotlin/Native
/// classes deriving from Objective-C classes are final, so no Swift subclass). Phase 7
/// installs the Libbox-backed engine here, before the system instantiates the provider:
///
///     TunnelEngineFactory.shared.install { LibboxTunnelEngine() }
///
/// Phase 6 installs nothing: the provider fails deterministically with
/// `ProviderStartupFailure("no tunnel engine linked")`.
enum TunnelEngineBootstrap {}
