package io.ucc.iosvpn

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Requests the host app sends through `NETunnelProviderSession.sendProviderMessage`. */
@Serializable
public sealed class IpcRequest {
    @Serializable @SerialName("status") public data object Status : IpcRequest()
    @Serializable @SerialName("statistics") public data object Statistics : IpcRequest()
    /** Ask the provider to stop cleanly (the system also delivers stopTunnel, this only pre-empts). */
    @Serializable @SerialName("stop") public data object Stop : IpcRequest()
    @Serializable @SerialName("ping") public data class Ping(val nonce: Long) : IpcRequest()
}

/** Responses; every request yields exactly one. */
@Serializable
public sealed class IpcResponse {
    @Serializable @SerialName("status")
    public data class Status(val state: ProviderState, val profileId: String?, val sinceEpochMs: Long?, val lastErrorCode: String?) : IpcResponse()

    @Serializable @SerialName("statistics")
    public data class Statistics(val available: Boolean, val uplinkBytes: Long = 0, val downlinkBytes: Long = 0) : IpcResponse()

    @Serializable @SerialName("ack") public data object Ack : IpcResponse()
    @Serializable @SerialName("pong") public data class Pong(val nonce: Long) : IpcResponse()
    /** Provider understood the request but could not fulfil it; [code] is a [VpnError.code]. */
    @Serializable @SerialName("error") public data class Error(val code: String, val detail: String) : IpcResponse()
}

/** Provider-side lifecycle as reported over IPC (distinct from NEVPNStatus, which the system owns). */
@Serializable
public enum class ProviderState { IDLE, STARTING, RUNNING, STOPPING, STOPPED, FAILED }

/**
 * Wire format: UTF-8 JSON with a one-byte version prefix so a mismatched app/extension
 * pair fails deterministically instead of mis-decoding. Framing: `[VERSION][json…]`.
 */
public object IpcCodec {
    public const val VERSION: Byte = 1
    public const val MAX_MESSAGE_BYTES: Int = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    public class DecodeException(message: String) : Exception(message)

    public fun encodeRequest(r: IpcRequest): ByteArray = frame(json.encodeToString(IpcRequest.serializer(), r))
    public fun encodeResponse(r: IpcResponse): ByteArray = frame(json.encodeToString(IpcResponse.serializer(), r))

    /** @throws DecodeException */
    public fun decodeRequest(bytes: ByteArray): IpcRequest =
        runCatching { json.decodeFromString(IpcRequest.serializer(), unframe(bytes)) }.getOrElse { throw asDecode(it) }

    /** @throws DecodeException */
    public fun decodeResponse(bytes: ByteArray): IpcResponse =
        runCatching { json.decodeFromString(IpcResponse.serializer(), unframe(bytes)) }.getOrElse { throw asDecode(it) }

    private fun frame(body: String): ByteArray {
        val b = body.encodeToByteArray()
        check(b.size < MAX_MESSAGE_BYTES) { "ipc message too large" }
        return byteArrayOf(VERSION) + b
    }

    private fun unframe(bytes: ByteArray): String {
        if (bytes.isEmpty()) throw DecodeException("empty message")
        if (bytes[0] != VERSION) throw DecodeException("unsupported ipc version ${bytes[0]}")
        if (bytes.size > MAX_MESSAGE_BYTES) throw DecodeException("message too large")
        return bytes.decodeToString(1, bytes.size)
    }

    private fun asDecode(t: Throwable): DecodeException = t as? DecodeException ?: DecodeException("malformed ipc payload")
}

/** Raw byte transport; iOS actual wraps `NETunnelProviderSession.sendProviderMessage`. */
public fun interface IpcTransport {
    /** Returns the provider's reply bytes, or null when the provider replied with nothing. */
    public suspend fun send(bytes: ByteArray): ByteArray?
}

/** Typed, time-bounded request/response client used by the host app. */
public class IpcClient(
    private val transport: IpcTransport,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * @throws VpnError.IpcTimeout, VpnError.IpcCancelled, VpnError.IpcTransport,
     *         VpnError.MalformedResponse, VpnError.ProviderUnavailable
     */
    public suspend fun send(request: IpcRequest): IpcResponse {
        val reply = try {
            withTimeout(timeoutMs) { transport.send(IpcCodec.encodeRequest(request)) }
        } catch (t: Throwable) {
            throw VpnError.classify(t, timeoutMs)
        }
        if (reply == null) throw VpnError.MalformedResponse("provider returned no data")
        return try { IpcCodec.decodeResponse(reply) } catch (e: IpcCodec.DecodeException) { throw VpnError.MalformedResponse(e.message ?: "undecodable") }
    }

    public suspend fun status(): IpcResponse.Status = expect(IpcRequest.Status)
    public suspend fun statistics(): IpcResponse.Statistics = expect(IpcRequest.Statistics)
    public suspend fun requestStop(): Unit { expect<IpcResponse.Ack>(IpcRequest.Stop) }

    private suspend inline fun <reified T : IpcResponse> expect(request: IpcRequest): T = when (val r = send(request)) {
        is T -> r
        is IpcResponse.Error -> throw VpnError.IpcTransport("provider error ${r.code}: ${r.detail}")
        else -> throw VpnError.MalformedResponse("unexpected ${r::class.simpleName} for ${request::class.simpleName}")
    }

    public companion object { public const val DEFAULT_TIMEOUT_MS: Long = 5_000 }
}
