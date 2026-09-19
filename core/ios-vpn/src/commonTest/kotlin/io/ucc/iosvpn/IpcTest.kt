package io.ucc.iosvpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IpcCodecTest {
    @Test fun request_round_trip() {
        for (r in listOf<IpcRequest>(IpcRequest.Status, IpcRequest.Statistics, IpcRequest.Stop, IpcRequest.Ping(42))) {
            val b = IpcCodec.encodeRequest(r)
            assertEquals(IpcCodec.VERSION, b[0]); assertEquals(r, IpcCodec.decodeRequest(b))
        }
    }

    @Test fun response_round_trip() {
        for (r in listOf<IpcResponse>(
            IpcResponse.Status(ProviderState.RUNNING, "p1", 123L, null), IpcResponse.Status(ProviderState.FAILED, null, null, "ProviderStartupFailure"),
            IpcResponse.Statistics(true, 1, 2), IpcResponse.Statistics(false), IpcResponse.Ack, IpcResponse.Pong(7), IpcResponse.Error("IpcTimeout", "x"),
        )) assertEquals(r, IpcCodec.decodeResponse(IpcCodec.encodeResponse(r)))
    }

    @Test fun wire_format_is_versioned_json_with_type_discriminator() {
        val b = IpcCodec.encodeRequest(IpcRequest.Ping(1))
        assertTrue(b.decodeToString(1, b.size).contains("\"type\":\"ping\""))
    }

    @Test fun malformed_inputs() {
        assertFailsWith<IpcCodec.DecodeException> { IpcCodec.decodeResponse(byteArrayOf()) }
        assertFailsWith<IpcCodec.DecodeException> { IpcCodec.decodeResponse(byteArrayOf(9) + "{}".encodeToByteArray()) }
        assertFailsWith<IpcCodec.DecodeException> { IpcCodec.decodeResponse(byteArrayOf(1) + "{".encodeToByteArray()) }
        assertFailsWith<IpcCodec.DecodeException> { IpcCodec.decodeResponse(byteArrayOf(1) + """{"type":"nope"}""".encodeToByteArray()) }
        assertFailsWith<IpcCodec.DecodeException> { IpcCodec.decodeRequest(byteArrayOf(1) + """{"type":"ping"}""".encodeToByteArray()) } // missing nonce
        assertFailsWith<IpcCodec.DecodeException> { IpcCodec.decodeResponse(byteArrayOf(1) + ByteArray(IpcCodec.MAX_MESSAGE_BYTES)) }
    }
}

class IpcClientTest {
    private fun replying(block: (IpcRequest) -> IpcResponse?) = IpcTransport { bytes -> block(IpcCodec.decodeRequest(bytes))?.let(IpcCodec::encodeResponse) }

    @Test fun typed_round_trip() = runTest {
        val c = IpcClient(replying { r -> when (r) { IpcRequest.Status -> IpcResponse.Status(ProviderState.RUNNING, "p", 1, null); is IpcRequest.Ping -> IpcResponse.Pong(r.nonce); IpcRequest.Stop -> IpcResponse.Ack; else -> IpcResponse.Statistics(false) } })
        assertEquals(ProviderState.RUNNING, c.status().state)
        assertEquals(IpcResponse.Pong(5), c.send(IpcRequest.Ping(5)))
        c.requestStop()
        assertEquals(false, c.statistics().available)
    }

    @Test fun timeout_is_classified() = runTest {
        val c = IpcClient(IpcTransport { delay(10_000); null }, timeoutMs = 100)
        val e = assertFailsWith<VpnError.IpcTimeout> { c.status() }
        assertEquals(100, e.timeoutMs)
    }

    @Test fun cancellation_is_classified_and_propagates() = runTest {
        val started = CompletableDeferred<Unit>()
        val c = IpcClient(IpcTransport { started.complete(Unit); delay(10_000); null }, timeoutMs = 60_000)
        val job = async { runCatching { c.status() } }
        started.await(); job.cancel(); yield()
        assertTrue(job.isCancelled)
        // classify directly as well
        assertIs<VpnError.IpcCancelled>(VpnError.classify(CancellationException("x")))
    }

    @Test fun transport_failures_and_empty_replies() = runTest {
        assertFailsWith<VpnError.IpcTransport> { IpcClient(IpcTransport { throw IllegalStateException("boom") }).status() }
        assertFailsWith<VpnError.ProviderUnavailable> { IpcClient(IpcTransport { throw VpnError.ProviderUnavailable("no session") }).status() }
        assertFailsWith<VpnError.MalformedResponse> { IpcClient(IpcTransport { null }).status() }
        assertFailsWith<VpnError.MalformedResponse> { IpcClient(IpcTransport { byteArrayOf(1, 2, 3) }).status() }
    }

    @Test fun wrong_response_type_and_error_response() = runTest {
        assertFailsWith<VpnError.MalformedResponse> { IpcClient(replying { IpcResponse.Ack }).status() }
        val e = assertFailsWith<VpnError.IpcTransport> { IpcClient(replying { IpcResponse.Error("ProviderStartupFailure", "engine") }).status() }
        assertTrue(e.detail.contains("ProviderStartupFailure"))
    }
}

class VpnErrorTest {
    @Test fun classification_is_deterministic() = runTest {
        val timeout = runCatching { kotlinx.coroutines.withTimeout(1) { delay(1_000) } }.exceptionOrNull()!!
        assertEquals(1, assertIs<VpnError.IpcTimeout>(VpnError.classify(timeout, 1)).timeoutMs)
        assertIs<VpnError.IpcCancelled>(VpnError.classify(CancellationException()))
        assertIs<VpnError.MalformedResponse>(VpnError.classify(IpcCodec.DecodeException("m")))
        assertIs<VpnError.IpcTransport>(VpnError.classify(RuntimeException("r")))
        val own = VpnError.ProviderShutdownFailure("s"); assertEquals(own, VpnError.classify(own))
    }

    @Test fun codes_and_connection_error_bridge() {
        assertEquals("IpcTimeout", VpnError.IpcTimeout(1).code)
        assertIs<io.ucc.core.engine.ConnectionError.VpnPermissionDenied>(VpnError.PermissionOrConfigurationUnavailable("x").toConnectionError())
        assertIs<io.ucc.core.engine.ConnectionError.InvalidConfiguration>(VpnError.InvalidTunnelConfiguration("x").toConnectionError())
        assertIs<io.ucc.core.engine.ConnectionError.ConnectionTimeout>(VpnError.IpcTimeout(1).toConnectionError())
        assertIs<io.ucc.core.engine.ConnectionError.CoreFailure>(VpnError.ProviderStartupFailure("x").toConnectionError())
        assertIs<io.ucc.core.engine.ConnectionError.CoreFailure>(VpnError.ProviderUnavailable("x").toConnectionError())
    }
}
