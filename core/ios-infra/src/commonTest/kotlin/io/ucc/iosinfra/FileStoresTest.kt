package io.ucc.iosinfra

import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionInfo
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.smart.TestFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun profile(id: String, name: String = "Server $id") =
    ConnectionProfile(id = id, name = name, protocol = Protocol.VLESS, address = "$id.example.net", port = 443)

/**
 * Store behaviour + on-disk document compatibility. The JSON literals below are
 * what the Android JsonSubscriptionStore / JsonServerHealthStore write (same
 * serializers, same Json flags), so decoding them proves cross-platform reads.
 */
class FileStoresTest {
    private val io = Dispatchers.Unconfined

    @Test fun profiles_persist_dedupe_and_reload() = runTest {
        val fs = InMemoryBlobStore()
        val s = FileProfileStore(fs, PlainCodec, io)
        s.load(); assertTrue(s.current().isEmpty())
        s.upsertAll(listOf(profile("a"), profile("b")))
        s.apply(listOf(profile("a", "renamed"), profile("c")), listOf("b"))
        assertEquals(listOf("a", "c"), s.current().map { it.id })
        assertEquals("renamed", s.byId("a")?.name)
        val doc = fs.files.getValue("profiles.enc").decodeToString()
        assertTrue(doc.contains("\"type\""), doc) // polymorphic discriminator as on Android
        val again = FileProfileStore(fs, PlainCodec, io); again.load()
        assertEquals(s.current(), again.current())
        assertNull(again.lastLoadProblem.value)
    }

    @Test fun unchanged_apply_does_not_write() = runTest {
        val fs = InMemoryBlobStore(); val s = FileProfileStore(fs, PlainCodec, io)
        s.upsertAll(listOf(profile("a")))
        fs.failNextWrite = true
        s.upsertAll(listOf(profile("a")))   // identical → no write → no failure
        s.deleteAll(listOf("zzz"))
        assertTrue(fs.failNextWrite)
    }

    @Test fun write_failure_keeps_previous_state() = runTest {
        val fs = InMemoryBlobStore(); val s = FileProfileStore(fs, PlainCodec, io)
        s.upsertAll(listOf(profile("a")))
        fs.failNextWrite = true
        val failed = runCatching { s.upsertAll(listOf(profile("b"))) }
        assertTrue(failed.isFailure); assertEquals(listOf("a"), s.current().map { it.id })
    }

    @Test fun corrupt_profile_file_is_quarantined_and_reported() = runTest {
        val fs = InMemoryBlobStore(); fs.files["profiles.enc"] = byteArrayOf(0, 1, 2)
        val codec = object : FileCodec { override fun encode(plain: ByteArray) = plain; override fun decode(stored: ByteArray) = throw FileCodec.CorruptOrForeign("x") }
        val s = FileProfileStore(fs, codec, io); s.load()
        val p = assertNotNull(s.lastLoadProblem.value)
        assertTrue(p.quarantinedFileName.startsWith("profiles.enc.corrupt-")); assertTrue(fs.exists(p.quarantinedFileName))
        assertTrue(s.current().isEmpty())
        s.clearLoadProblem(); assertNull(s.lastLoadProblem.value)
    }

    @Test fun subscriptions_use_android_row_document() = runTest {
        val fs = InMemoryBlobStore()
        val androidDoc = """[{"id":"s1","url":"https://x/sub","name":"Sub","addedAtEpochMs":1700000000000,"lastFetchedAtEpochMs":1700000001000,"upload":1,"download":2,"total":300,"expire":1800000000,"autoUpdate":false,"updateIntervalHours":12,"lastError":null,"futureField":true},
            {"id":"s2","url":"https://y","name":"Bare","addedAtEpochMs":5}]"""
        fs.files["subscriptions.enc"] = androidDoc.encodeToByteArray()
        val s = FileSubscriptionStore(fs, PlainCodec, io); s.load()
        val s1 = assertNotNull(s.byId("s1"))
        assertEquals(SubscriptionInfo(1, 2, 300, 1800000000), s1.lastInfo); assertFalse(s1.autoUpdate); assertEquals(12, s1.updateIntervalHours)
        val s2 = assertNotNull(s.byId("s2")); assertNull(s2.lastInfo); assertTrue(s2.autoUpdate)

        s.upsert(Subscription("s3", "https://z", "New", 9, lastInfo = SubscriptionInfo(totalBytes = 10)))
        s.delete("s1")
        val written = fs.files.getValue("subscriptions.enc").decodeToString()
        assertTrue(written.contains("\"total\":10") && written.contains("\"upload\":null") && written.contains("\"autoUpdate\":true"), written)
        assertFalse(written.contains("lastInfo")); assertFalse(written.contains("\"s1\""))
        val again = FileSubscriptionStore(fs, PlainCodec, io); again.load()
        assertEquals(listOf("s2", "s3"), again.all.value.map { it.id })
    }

    @Test fun health_map_document_and_non_fatal_write() = runTest {
        val fs = InMemoryBlobStore()
        fs.files["health.enc"] = """{"fp1":{"latencyMs":120,"rollingLatencyMs":130,"latencySampleCount":3,"lastSuccessAtEpochMs":1,"lastFailure":"TIMEOUT"}}""".encodeToByteArray()
        val h = FileServerHealthStore(fs, PlainCodec, io); h.load()
        assertEquals(120L, h.get("fp1")?.latencyMs); assertEquals(TestFailure.TIMEOUT, h.get("fp1")?.lastFailure)
        h.update("fp2") { it.copy(latencyMs = 50) }
        h.prune(setOf("fp2"))
        assertFalse(h.all.value.containsKey("fp1")); assertEquals(50L, h.get("fp2")?.latencyMs)
        fs.failNextWrite = true
        h.update("fp2") { it.copy(latencyMs = 60) } // must not throw
        assertEquals(60L, h.get("fp2")?.latencyMs)
        h.clear(); assertTrue(h.all.value.isEmpty())
    }

    @Test fun encrypted_end_to_end_with_envelope() = runTest {
        val fs = InMemoryBlobStore()
        val key = ByteArray(64) { it.toByte() }
        val codec = EtmFileCodec(FakePrimitives, { key })
        val s = FileProfileStore(fs, codec, io); s.upsertAll(listOf(profile("a")))
        val raw = fs.files.getValue("profiles.enc")
        assertTrue(EtmFileCodec.isEncrypted(raw)); assertFalse(raw.decodeToString().contains("example.net"))
        val again = FileProfileStore(fs, codec, io); again.load(); assertEquals("a", again.current().single().id)
        val wrongKey = FileProfileStore(fs, EtmFileCodec(FakePrimitives, { ByteArray(64) }), io); wrongKey.load()
        assertNotNull(wrongKey.lastLoadProblem.value); assertTrue(wrongKey.current().isEmpty())
    }
}
