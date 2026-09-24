@file:OptIn(ExperimentalForeignApi::class)

package io.ucc.iosinfra

import io.ucc.core.engine.UnderlyingNetwork
import io.ucc.core.engine.manager.NetworkEvent
import io.ucc.core.engine.manager.NetworkMonitor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_interface_get_index
import platform.Network.nw_interface_get_name
import platform.Network.nw_interface_t
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_prohibit_interface_type
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.Network.nw_interface_type_other
import platform.Network.nw_path_t
import platform.darwin.dispatch_queue_create
import kotlinx.cinterop.toKString

/**
 * Apple counterpart of `AndroidNetworkMonitor`: tracks the *underlying* default
 * path with `nw_path_monitor` and excludes our own tunnel by prohibiting the
 * `other` interface type (utun). Emits the same [NetworkEvent] vocabulary with
 * the same key shape `"<transport>:<ifname>:<ifindex>"`.
 */
public class PathNetworkMonitor : NetworkMonitor {
    private val queue = dispatch_queue_create("io.ucc.iosinfra.path", null)
    private val _underlying = MutableStateFlow<UnderlyingNetwork?>(null)
    /** Core-agnostic view for `CorePlatform.underlyingNetwork`. */
    public val underlying: StateFlow<UnderlyingNetwork?> = _underlying

    override val events: Flow<NetworkEvent> = callbackFlow {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_prohibit_interface_type(monitor, nw_interface_type_other)
        var lastKey: String? = null
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (nw_path_get_status(path) != nw_path_status_satisfied) {
                if (lastKey != null || _underlying.value != null) {
                    lastKey = null; _underlying.value = null; trySend(NetworkEvent.Lost)
                }
                return@nw_path_monitor_set_update_handler
            }
            val iface = primaryInterface(path)
            val name = iface?.let { nw_interface_get_name(it)?.toKString() } ?: "unknown"
            val index = iface?.let { nw_interface_get_index(it).toInt() } ?: -1
            val transport = transportName(path)
            val expensive = nw_path_is_expensive(path)
            val key = "$transport:$name:$index"
            _underlying.value = UnderlyingNetwork(handle = key, interfaceName = name, interfaceIndex = index, expensive = expensive)
            if (key != lastKey) { lastKey = key; trySend(NetworkEvent.DefaultChanged(key, transport)) }
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }.distinctUntilChanged()

    private fun primaryInterface(path: nw_path_t): nw_interface_t? {
        var first: nw_interface_t? = null
        nw_path_enumerate_interfaces(path) { i -> if (first == null) first = i; first == null }
        return first
    }

    private fun transportName(path: nw_path_t): String = when {
        nw_path_uses_interface_type(path, nw_interface_type_wifi) -> "wifi"
        nw_path_uses_interface_type(path, nw_interface_type_cellular) -> "cellular"
        nw_path_uses_interface_type(path, nw_interface_type_wired) -> "ethernet"
        else -> "other"
    }
}
