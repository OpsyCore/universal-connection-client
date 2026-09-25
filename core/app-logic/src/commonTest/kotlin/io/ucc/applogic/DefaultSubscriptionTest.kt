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
        assertFalse(DefaultSubscription.shouldSeed(seededVersion = DefaultSubscription.SEED_VERSION, existingIds = emptyList()), "deleted by the user → stays deleted")
        assertFalse(DefaultSubscription.shouldSeed(seededVersion = 0, existingIds = listOf(DefaultSubscription.ID)), "already present")
    }

    @Test fun `legacy list is removed only when present`() {
        val legacy = Subscription.idFor(DefaultSubscription.LEGACY_URL_V1)
        val legacy2 = Subscription.idFor(DefaultSubscription.LEGACY_URL_V2)
        assertEquals(listOf(legacy), DefaultSubscription.legacyToRemove(listOf(legacy, "other")))
        assertEquals(listOf(legacy, legacy2), DefaultSubscription.legacyToRemove(listOf(legacy2, legacy)))
        assertTrue(DefaultSubscription.isDefault(legacy2))
        assertEquals(emptyList(), DefaultSubscription.legacyToRemove(listOf("other")))
    }

    @Test fun `curate keeps only vless and vmess and drops unsupported transports`() {
        val out = DefaultSubscription.curate(listOf(
            p("a", Protocol.VLESS), p("b", Protocol.VMESS), p("c", Protocol.TROJAN), p("d", Protocol.SHADOWSOCKS),
            p("e", Protocol.SOCKS), p("f", Protocol.HTTP), p("g", Protocol.HYSTERIA2), p("h", Protocol.VLESS, transport = Transport.Unsupported("kcp")),
        ))
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test fun `curate prefers reality then tls with modern transport and dedupes by address and port`() {
        val out = DefaultSubscription.curate(listOf(
            p("plain", Protocol.VMESS),
            p("tls", Protocol.VLESS, tls = true),
            p("tlsws", Protocol.VLESS, tls = true, transport = Transport.WebSocket()),
            p("reality", Protocol.VLESS, reality = true),
            p("dupe", Protocol.VLESS, address = "reality.example.net", port = 443),
        ))
        assertEquals(listOf("reality", "tlsws", "tls", "plain"), out.map { it.id })
    }

    @Test fun `curate caps at MAX_SERVERS`() {
        val many = (1..80).map { p("s$it", Protocol.VLESS, tls = it % 2 == 0) }
        val out = DefaultSubscription.curate(many)
        assertEquals(DefaultSubscription.MAX_SERVERS, out.size)
        assertTrue(out.all { it.tls.enabled }, "TLS entries are ranked ahead of plain ones")
    }
}
