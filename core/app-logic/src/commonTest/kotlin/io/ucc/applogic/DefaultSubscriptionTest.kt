package io.ucc.applogic

import io.ucc.core.config.subscription.Subscription
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSubscriptionTest {
    private fun p(id: String, proto: Protocol, port: Int = 443, address: String = "$id.example.net", tls: Boolean = false, reality: Boolean = false, transport: Transport = Transport.Tcp) =
        ConnectionProfile(
            id = id, name = id, protocol = proto, address = address, port = port, transport = transport,
            tls = TlsSettings(enabled = tls || reality, reality = if (reality) TlsSettings.Reality(publicKey = "pk") else null),
        )

    @Test fun `id matches what a manual add of the same url would produce`() {
        assertEquals(Subscription.idFor(DefaultSubscription.URL), DefaultSubscription.ID)
        assertEquals(DefaultSubscription.ID, DefaultSubscription.record("x", 1).id)
        assertTrue(DefaultSubscription.URL.startsWith("https://"))
        assertTrue(DefaultSubscription.isDefault(DefaultSubscription.ID))
        assertTrue(DefaultSubscription.isDefault(Subscription.idFor(DefaultSubscription.LEGACY_URL_V1)))
        assertFalse(DefaultSubscription.isDefault("someone-elses"))
    }

    @Test fun `seeds once per seed version and never over an existing record`() {
        assertTrue(DefaultSubscription.shouldSeed(seededVersion = 0, existingIds = emptyList()))
        assertTrue(DefaultSubscription.shouldSeed(seededVersion = 1, existingIds = emptyList()), "upgrade from the v1 list re-seeds")
        assertTrue(DefaultSubscription.shouldSeed(seededVersion = 2, existingIds = emptyList()), "upgrade from the dead v2 list re-seeds")
        assertTrue(DefaultSubscription.shouldSeed(seededVersion = 4, existingIds = emptyList()), "upgrade from the mixed v3/v4 list re-seeds")
        assertFalse(DefaultSubscription.shouldSeed(seededVersion = DefaultSubscription.SEED_VERSION, existingIds = emptyList()), "deleted by the user → stays deleted")
        assertFalse(DefaultSubscription.shouldSeed(seededVersion = 0, existingIds = listOf(DefaultSubscription.ID)), "already present")
    }

    @Test fun `legacy list is removed only when present`() {
        val legacy = Subscription.idFor(DefaultSubscription.LEGACY_URL_V1)
        val legacy2 = Subscription.idFor(DefaultSubscription.LEGACY_URL_V2)
        val legacy3 = Subscription.idFor(DefaultSubscription.LEGACY_URL_V3)
        assertEquals(listOf(legacy), DefaultSubscription.legacyToRemove(listOf(legacy, "other")))
        assertEquals(listOf(legacy, legacy2, legacy3), DefaultSubscription.legacyToRemove(listOf(legacy3, legacy2, legacy)))
        assertTrue(DefaultSubscription.isDefault(legacy2))
        assertTrue(DefaultSubscription.isDefault(legacy3))
        assertTrue(legacy3 != DefaultSubscription.ID)
        assertEquals(emptyList(), DefaultSubscription.legacyToRemove(listOf("other")))
    }

    @Test fun `curate keeps only vless with reality or websocket`() {
        val out = DefaultSubscription.curate(listOf(
            p("reality", Protocol.VLESS, reality = true),
            p("ws", Protocol.VLESS, transport = Transport.WebSocket()),
            p("wstls", Protocol.VLESS, tls = true, transport = Transport.WebSocket()),
            p("plainTcp", Protocol.VLESS),
            p("tlsTcp", Protocol.VLESS, tls = true),
            p("grpcTls", Protocol.VLESS, tls = true, transport = Transport.Grpc()),
            p("vmessWs", Protocol.VMESS, tls = true, transport = Transport.WebSocket()),
            p("vmessReality", Protocol.VMESS, reality = true),
            p("trojan", Protocol.TROJAN, tls = true, transport = Transport.WebSocket()),
            p("ss", Protocol.SHADOWSOCKS), p("hy2", Protocol.HYSTERIA2),
            p("unsupported", Protocol.VLESS, reality = true, transport = Transport.Unsupported("kcp")),
        ))
        assertEquals(listOf("reality", "wstls", "ws"), out.map { it.id })
    }

    @Test fun `curate orders reality then ws tls then ws and dedupes by address and port`() {
        val out = DefaultSubscription.curate(listOf(
            p("ws", Protocol.VLESS, transport = Transport.WebSocket()),
            p("wstls", Protocol.VLESS, tls = true, transport = Transport.WebSocket()),
            p("reality", Protocol.VLESS, reality = true),
            p("dupe", Protocol.VLESS, address = "reality.example.net", port = 443, transport = Transport.WebSocket()),
        ))
        assertEquals(listOf("reality", "wstls", "ws"), out.map { it.id })
    }

    @Test fun `curate caps at MAX_SERVERS with a reality quota so websocket entries are represented`() {
        val many = (1..80).map { if (it % 2 == 0) p("r$it", Protocol.VLESS, reality = true) else p("w$it", Protocol.VLESS, tls = true, transport = Transport.WebSocket()) }
        val out = DefaultSubscription.curate(many)
        assertEquals(DefaultSubscription.MAX_SERVERS, out.size)
        assertEquals(DefaultSubscription.MAX_REALITY, out.count { it.tls.reality != null })
        assertEquals(DefaultSubscription.MAX_SERVERS - DefaultSubscription.MAX_REALITY, out.count { it.transport is Transport.WebSocket && it.tls.reality == null })
        assertTrue(out.indexOfLast { it.tls.reality != null } < out.indexOfFirst { it.tls.reality == null }, "REALITY entries come first")
    }

    @Test fun `curate fills with extra reality entries when websocket ones are scarce`() {
        val many = (1..40).map { p("r$it", Protocol.VLESS, reality = true) } + p("w", Protocol.VLESS, transport = Transport.WebSocket())
        val out = DefaultSubscription.curate(many)
        assertEquals(DefaultSubscription.MAX_SERVERS, out.size)
        assertEquals(1, out.count { it.tls.reality == null })
    }
}
