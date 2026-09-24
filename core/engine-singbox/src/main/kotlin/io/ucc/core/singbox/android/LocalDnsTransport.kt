package io.ucc.core.singbox.android

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Implements sing-box's `local` DNS server on top of the platform resolver,
 * bound to the *underlying* default network so system DNS never loops back
 * into the tunnel. On API 29+ raw DNS messages are forwarded; below that only
 * A/AAAA lookups via `Network.getAllByName`.
 */
internal class LocalDnsTransport(private val defaultNetwork: () -> Network?) : LocalDNSTransport {
    private companion object {
        const val RCODE_NXDOMAIN = 3
        const val RCODE_SERVFAIL = 2
    }

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = defaultNetwork() ?: run { ctx.errorCode(RCODE_SERVFAIL); return }
        runBlocking {
            suspendCoroutine { cont ->
                val signal = CancellationSignal()
                var done = false
                ctx.onCancel {
                    signal.cancel()
                    synchronized(cont) { if (!done) { done = true; cont.resumeWithException(CancellationException()) } }
                }
                DnsResolver.getInstance().rawQuery(
                    network, message, DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                            synchronized(cont) { if (!done) { done = true; cont.resume(Unit) } }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            val cause = error.cause
                            if (cause is ErrnoException) ctx.errnoCode(cause.errno) else ctx.errorCode(RCODE_SERVFAIL)
                            synchronized(cont) { if (!done) { done = true; cont.resume(Unit) } }
                        }
                    },
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val net = defaultNetwork() ?: run { ctx.errorCode(RCODE_SERVFAIL); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lookupQ(ctx, net, network, domain)
        } else {
            val answer = try {
                net.getAllByName(domain)
            } catch (e: UnknownHostException) {
                ctx.errorCode(RCODE_NXDOMAIN)
                return
            }
            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun lookupQ(ctx: ExchangeContext, net: Network, network: String, domain: String) = runBlocking {
        suspendCoroutine { cont ->
            val signal = CancellationSignal()
            var done = false
            ctx.onCancel {
                signal.cancel()
                synchronized(cont) { if (!done) { done = true; cont.resumeWithException(CancellationException()) } }
            }
            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    if (rcode == 0) ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n")) else ctx.errorCode(rcode)
                    synchronized(cont) { if (!done) { done = true; cont.resume(Unit) } }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) ctx.errnoCode(cause.errno) else ctx.errorCode(RCODE_SERVFAIL)
                    synchronized(cont) { if (!done) { done = true; cont.resume(Unit) } }
                }
            }
            val type = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            val resolver = DnsResolver.getInstance()
            if (type != null) {
                resolver.query(net, domain, type, DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal, callback)
            } else {
                resolver.query(net, domain, DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal, callback)
            }
        }
    }
}
