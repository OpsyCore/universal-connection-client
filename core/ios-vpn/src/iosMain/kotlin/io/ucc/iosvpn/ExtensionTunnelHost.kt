@file:OptIn(ExperimentalForeignApi::class)

package io.ucc.iosvpn

import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.TunRequest
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.engine.manager.TunnelHost
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.NetworkExtension.NEPacketTunnelProvider
import platform.posix.getsockopt
import platform.posix.socklen_tVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.toKString

/**
 * Inside the extension the tunnel already exists — the system created the utun when
 * `setTunnelNetworkSettings` succeeded. This host therefore never asks for
 * permission or starts a service (that is the Android shape); it hands the core a
 * [TunProvider] whose `openTun` returns the utun file descriptor found in the
 * provider process, and whose `protectSocket` is a no-op because NetworkExtension
 * already routes provider sockets outside the tunnel.
 *
 * The fd lookup mirrors what sing-box's own Apple client does (scan descriptors for
 * a `utun` control socket); it is used by Libbox in Phase 7 and is not exercised yet.
 */
public class ExtensionTunnelHost(private val provider: NEPacketTunnelProvider) : TunnelHost {
    private val _revoked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val revoked: Flow<Unit> = _revoked

    /** Called by the provider's stopTunnel so the shared manager sees a revocation. */
    public fun notifyRevoked() { _revoked.tryEmit(Unit) }

    override suspend fun acquire(profile: ConnectionProfile): TunProvider = object : TunProvider {
        override fun openTun(request: TunRequest): Int = findUtunFd()
            ?: throw VpnError.ProviderStartupFailure("utun descriptor not found in extension process")
        override fun protectSocket(fd: Int): Boolean = true
    }

    override suspend fun release() { /* the system tears the utun down with the provider */ }

    public companion object {
        private const val MAX_FD = 1024
        /** <sys/kern_control.h> SYSPROTO_CONTROL and <net/if_utun.h> UTUN_OPT_IFNAME — stable XNU ABI values, not exported by K/N platform libs. */
        private const val SYSPROTO_CONTROL = 2
        private const val UTUN_OPT_IFNAME = 2

        /** First descriptor that is a utun control socket, or null. */
        public fun findUtunFd(): Int? = memScoped {
            val name = allocArray<ByteVar>(32)
            val len = alloc<socklen_tVar>()
            for (fd in 0 until MAX_FD) {
                len.value = 32u
                if (getsockopt(fd, SYSPROTO_CONTROL, UTUN_OPT_IFNAME, name, len.ptr) == 0 &&
                    name.toKString().startsWith("utun")
                ) return fd
            }
            null
        }
    }
}
