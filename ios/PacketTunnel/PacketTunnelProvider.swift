import NetworkExtension
import IosVpn // Kotlin/Native framework exported from :core:ios-vpn (Phase 7 adds the Gradle framework config + Libbox)

/// Principal class of the packet tunnel extension. All behaviour lives in the Kotlin
/// `UccPacketTunnelProvider`; this shim only exists because NSExtensionPrincipalClass
/// needs an Objective-C-visible class defined in the extension bundle.
@objc(PacketTunnelProvider)
final class PacketTunnelProvider: UccPacketTunnelProvider {}
