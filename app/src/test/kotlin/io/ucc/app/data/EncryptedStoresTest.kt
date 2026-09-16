package io.ucc.app.data

import io.ucc.app.data.crypto.AesGcmFileCodec
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.model.ProfileSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real stores against a temp dir with an in-memory AES key (Keystore is swapped out via the internal constructor). */
class EncryptedStoresTest {
    private val dir: File = Files.createTempDirectory("stores").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun codec() = AesGcmFileCodec({ key })
    private fun profileStore() = JsonProfileStore(dir, codec(), Dispatchers.Unconfined)
    private fun subStore() = JsonSubscriptionStore(dir, codec(), Dispatchers.Unconfined)

    @Test fun `profiles survive a restart and never touch disk in plaintext`() = runTest {
        val ps = testImporter().import("trojan://hunter2@1.2.3.4:443#A\ntrojan://hunter2@1.2.3.5:443#B", ProfileSource.Manual).profiles
        profileStore().apply { load(); upsertAll(ps); delete(ps[1].id) }
        val disk = File(dir, "profiles.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains("hunter2")); assertFalse(disk.contains("1.2.3.4"))
        assertFalse(File(dir, "profiles.json").exists())
        val reloaded = profileStore().apply { load() }
        assertEquals(listOf("A"), reloaded.current().map { it.name })
        assertEquals("A", reloaded.byId(ps[0].id)?.name)
    }

    @Test fun `legacy plaintext profiles json is migrated on load`() = runTest {
        val ps = testImporter().import("trojan://hunter2@1.2.3.4:443#Legacy", ProfileSource.Manual).profiles
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; classDiscriminator = "type" }
        File(dir, "profiles.json").writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(io.ucc.core.model.ConnectionProfile.serializer()), ps))
        val store = profileStore().apply { load() }
        assertEquals(listOf("Legacy"), store.current().map { it.name })
        assertFalse(File(dir, "profiles.json").exists())
        assertTrue(File(dir, "profiles.enc").exists())
        assertNull(store.lastLoadProblem.value)
    }

    @Test fun `undecryptable profile file is reported, quarantined, and the store starts empty but writable`() = runTest {
        profileStore().apply { load(); upsertAll(testImporter().import("trojan://pw@1.2.3.4:443#A", ProfileSource.Manual).profiles) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = JsonProfileStore(dir, AesGcmFileCodec({ otherKey }), Dispatchers.Unconfined).apply { load() }
        assertTrue(store.current().isEmpty())
        val problem = assertNotNull(store.lastLoadProblem.value)
        assertTrue(File(dir, problem.quarantinedFileName).exists())
        store.upsertAll(testImporter().import("trojan://pw@9.9.9.9:443#new", ProfileSource.Manual).profiles)
        assertEquals(1, JsonProfileStore(dir, AesGcmFileCodec({ otherKey }), Dispatchers.Unconfined).apply { load() }.current().size)
    }

    @Test fun `no-op apply does not rewrite the file`() = runTest {
        val store = profileStore().apply { load(); upsertAll(testImporter().import("trojan://pw@1.2.3.4:443#A", ProfileSource.Manual).profiles) }
        val before = File(dir, "profiles.enc").readBytes()
        store.deleteAll(listOf("does-not-exist"))
        assertTrue(before.contentEquals(File(dir, "profiles.enc").readBytes()))
    }

    @Test fun `subscriptions round trip with all fields and delete`() = runTest {
        val s = Subscription("s1", "https://example.com/secret-token", "S", 1L, 2L, io.ucc.core.config.subscription.SubscriptionInfo(1, 2, 3, 4), autoUpdate = false, updateIntervalHours = 24, lastError = "http:503")
        subStore().apply { load(); upsert(s); upsert(Subscription("s2", "https://x/2", "T", 1L)); delete("s2") }
        assertFalse(File(dir, "subscriptions.enc").readBytes().toString(Charsets.ISO_8859_1).contains("secret-token"))
        val re = subStore().apply { load() }
        assertEquals(listOf(s), re.all.value)
    }
}
