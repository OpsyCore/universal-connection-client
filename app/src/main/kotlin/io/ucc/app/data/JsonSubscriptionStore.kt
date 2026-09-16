package io.ucc.app.data

import android.content.Context
import io.ucc.app.data.crypto.AesGcmFileCodec
import io.ucc.app.data.crypto.FileCodec
import io.ucc.app.data.crypto.KeystoreKeys
import io.ucc.app.data.crypto.SecureFile
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Subscription records, encrypted at rest like profiles (a subscription URL is
 * itself a credential). Same key alias, different file magic is not needed
 * because the file name is part of the path; AAD is the shared magic.
 */
class JsonSubscriptionStore internal constructor(
    dir: File,
    codec: FileCodec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SubscriptionStore {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        AesGcmFileCodec({ KeystoreKeys.aesKey(KeystoreKeys.PROFILES_ALIAS) }),
    )

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

    private val file = SecureFile(dir, "subscriptions", codec)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Row.serializer())
    private val mutex = Mutex()
    private val _all = MutableStateFlow<List<Subscription>>(emptyList())
    override val all: StateFlow<List<Subscription>> = _all

    suspend fun load() = withContext(io) {
        mutex.withLock {
            val r = runCatching { file.read() }.getOrNull() as? SecureFile.ReadResult.Ok ?: return@withLock
            _all.value = runCatching { json.decodeFromString(serializer, r.bytes.decodeToString()).map { it.toModel() } }.getOrDefault(emptyList())
        }
    }

    override suspend fun byId(id: String): Subscription? = _all.value.firstOrNull { it.id == id }

    override suspend fun upsert(subscription: Subscription) = write { it.filterNot { s -> s.id == subscription.id } + subscription }

    override suspend fun delete(id: String) = write { it.filterNot { s -> s.id == id } }

    private suspend fun write(block: (List<Subscription>) -> List<Subscription>) = withContext(io) {
        mutex.withLock {
            val next = block(_all.value)
            file.write(json.encodeToString(serializer, next.map { Row.of(it) }).encodeToByteArray())
            _all.value = next
        }
    }
}
