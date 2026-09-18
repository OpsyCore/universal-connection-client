package io.ucc.iosinfra

import io.ucc.applogic.ProfileStore
import io.ucc.applogic.SubscriptionStore
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionInfo
import io.ucc.core.engine.manager.ProfileProvider
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.platform.platformIoDispatcher
import io.ucc.core.smart.ServerHealth
import io.ucc.core.smart.ServerHealthStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * Byte-for-byte the same JSON documents as the Android JsonProfileStore /
 * JsonSubscriptionStore / JsonServerHealthStore (same Json configuration, same
 * serializers, same Row shape, same file names `profiles.enc`, `subscriptions.enc`,
 * `health.enc`), so a document produced on one platform decodes on the other
 * once the envelope is removed.
 */

/** Profile persistence: one encrypted JSON list at `profiles.enc`. */
public class FileProfileStore(
    store: BlobStore,
    codec: FileCodec,
    private val io: CoroutineDispatcher = platformIoDispatcher(),
    /** Redacted messages only (counts, file names). */
    private val log: (String) -> Unit = {},
) : ProfileProvider, ProfileStore {

    private val file = SecureFile(store, "profiles", codec)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val serializer = ListSerializer(ConnectionProfile.serializer())
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    override val profiles: StateFlow<List<ConnectionProfile>> = _profiles

    private val _lastLoadProblem = MutableStateFlow<LoadProblem?>(null)
    /** Set when the last load found an undecryptable file; the UI shows a one-time notice. */
    public val lastLoadProblem: StateFlow<LoadProblem?> = _lastLoadProblem

    public data class LoadProblem(val quarantinedFileName: String)

    public suspend fun load(): Unit = withContext(io) {
        mutex.withLock {
            when (val r = runCatching { file.read() }.getOrElse { SecureFile.ReadResult.Missing }) {
                SecureFile.ReadResult.Missing -> Unit
                is SecureFile.ReadResult.Ok ->
                    _profiles.value = runCatching { json.decodeFromString(serializer, r.bytes.decodeToString()) }.getOrElse { emptyList() }
                is SecureFile.ReadResult.Unreadable -> {
                    log("profile store unreadable; quarantined as ${r.quarantinedName}")
                    _lastLoadProblem.value = LoadProblem(r.quarantinedName)
                }
            }
        }
    }

    public fun clearLoadProblem() { _lastLoadProblem.value = null }

    override suspend fun byId(id: String): ConnectionProfile? = _profiles.value.firstOrNull { it.id == id }
    public suspend fun upsert(profile: ConnectionProfile): Unit = apply(listOf(profile), emptyList())
    public suspend fun delete(id: String): Unit = apply(emptyList(), listOf(id))
    override fun current(): List<ConnectionProfile> = _profiles.value
    override suspend fun upsertAll(profiles: List<ConnectionProfile>): Unit = apply(profiles, emptyList())
    override suspend fun deleteAll(ids: Collection<String>): Unit = apply(emptyList(), ids)

    override suspend fun apply(upserts: List<ConnectionProfile>, deleteIds: Collection<String>): Unit = mutate { list ->
        val byId = LinkedHashMap<String, ConnectionProfile>(list.size + upserts.size)
        list.forEach { byId[it.id] = it }
        upserts.forEach { byId[it.id] = it }
        deleteIds.forEach { byId.remove(it) }
        byId.values.toList()
    }

    private suspend fun mutate(block: (List<ConnectionProfile>) -> List<ConnectionProfile>) = withContext(io) {
        mutex.withLock {
            val next = block(_profiles.value)
            if (next == _profiles.value) return@withLock
            file.write(json.encodeToString(serializer, next).encodeToByteArray())
            _profiles.value = next
        }
    }
}

/** Subscription records at `subscriptions.enc`; the flattened `Row` matches the Android document. */
public class FileSubscriptionStore(
    store: BlobStore,
    codec: FileCodec,
    private val io: CoroutineDispatcher = platformIoDispatcher(),
) : SubscriptionStore {

    @Serializable
    private data class Row(
        val id: String, val url: String, val name: String, val addedAtEpochMs: Long,
        val lastFetchedAtEpochMs: Long? = null,
        val upload: Long? = null, val download: Long? = null, val total: Long? = null, val expire: Long? = null,
        val autoUpdate: Boolean = true, val updateIntervalHours: Int? = null, val lastError: String? = null,
    ) {
        fun toModel() = Subscription(id, url, name, addedAtEpochMs, lastFetchedAtEpochMs,
            if (upload == null && download == null && total == null && expire == null) null else SubscriptionInfo(upload, download, total, expire),
            autoUpdate, updateIntervalHours, lastError)
        companion object {
            fun of(s: Subscription) = Row(s.id, s.url, s.name, s.addedAtEpochMs, s.lastFetchedAtEpochMs,
                s.lastInfo?.uploadBytes, s.lastInfo?.downloadBytes, s.lastInfo?.totalBytes, s.lastInfo?.expireEpochSeconds,
                s.autoUpdate, s.updateIntervalHours, s.lastError)
        }
    }

    private val file = SecureFile(store, "subscriptions", codec)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Row.serializer())
    private val mutex = Mutex()
    private val _all = MutableStateFlow<List<Subscription>>(emptyList())
    override val all: StateFlow<List<Subscription>> = _all

    public suspend fun load(): Unit = withContext(io) {
        mutex.withLock {
            val r = runCatching { file.read() }.getOrElse { SecureFile.ReadResult.Missing }
            if (r is SecureFile.ReadResult.Ok) {
                _all.value = runCatching { json.decodeFromString(serializer, r.bytes.decodeToString()).map { it.toModel() } }.getOrDefault(emptyList())
            }
        }
    }

    override suspend fun byId(id: String): Subscription? = _all.value.firstOrNull { it.id == id }
    override suspend fun upsert(subscription: Subscription): Unit = write { it.filterNot { s -> s.id == subscription.id } + subscription }
    override suspend fun delete(id: String): Unit = write { it.filterNot { s -> s.id == id } }

    private suspend fun write(block: (List<Subscription>) -> List<Subscription>) = withContext(io) {
        mutex.withLock {
            val next = block(_all.value)
            if (next == _all.value) return@withLock
            file.write(json.encodeToString(serializer, next.map { Row.of(it) }).encodeToByteArray())
            _all.value = next
        }
    }
}

/** Health cache at `health.enc` (`Map<fingerprint, ServerHealth>`); a failed write is not fatal, as on Android. */
public class FileServerHealthStore(
    store: BlobStore,
    codec: FileCodec,
    private val io: CoroutineDispatcher = platformIoDispatcher(),
) : ServerHealthStore {

    private val file = SecureFile(store, "health", codec)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), ServerHealth.serializer())
    private val mutex = Mutex()
    private val _all = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    override val all: StateFlow<Map<String, ServerHealth>> = _all

    public suspend fun load(): Unit = withContext(io) {
        mutex.withLock {
            val r = runCatching { file.read() }.getOrElse { SecureFile.ReadResult.Missing }
            if (r is SecureFile.ReadResult.Ok) {
                _all.value = runCatching { json.decodeFromString(serializer, r.bytes.decodeToString()) }.getOrDefault(emptyMap())
            }
        }
    }

    override suspend fun update(fingerprint: String, transform: (ServerHealth) -> ServerHealth): Unit =
        write { m -> m + (fingerprint to transform(m[fingerprint] ?: ServerHealth.EMPTY)) }

    override suspend fun prune(liveFingerprints: Set<String>): Unit = write { m -> m.filterKeys { it in liveFingerprints } }

    override suspend fun clear(): Unit = write { emptyMap() }

    private suspend fun write(block: (Map<String, ServerHealth>) -> Map<String, ServerHealth>) = withContext(io) {
        mutex.withLock {
            val next = block(_all.value)
            if (next == _all.value) return@withLock
            _all.value = next
            runCatching { file.write(json.encodeToString(serializer, next).encodeToByteArray()) }
        }
    }
}
