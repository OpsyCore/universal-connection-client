package io.ucc.app.data

import android.content.Context
import io.ucc.core.config.subscription.Subscription
import io.ucc.core.config.subscription.SubscriptionInfo
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

/** Temporary JSON persistence for subscription records (Room replaces it in Phase 4). Mirrors JsonProfileStore. */
class JsonSubscriptionStore(context: Context) : SubscriptionStore {
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

    private val file = File(context.applicationContext.filesDir, "subscriptions.json")
    private val tmp = File(file.parentFile, "subscriptions.json.tmp")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Row.serializer())
    private val mutex = Mutex()
    private val _all = MutableStateFlow<List<Subscription>>(emptyList())
    override val all: StateFlow<List<Subscription>> = _all

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (file.exists()) {
                _all.value = runCatching { json.decodeFromString(serializer, file.readText()).map { it.toModel() } }.getOrDefault(emptyList())
            }
        }
    }

    override suspend fun byId(id: String): Subscription? = _all.value.firstOrNull { it.id == id }

    override suspend fun upsert(subscription: Subscription) = write { it.filterNot { s -> s.id == subscription.id } + subscription }

    override suspend fun delete(id: String) = write { it.filterNot { s -> s.id == id } }

    private suspend fun write(block: (List<Subscription>) -> List<Subscription>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(_all.value)
            tmp.writeText(json.encodeToString(serializer, next.map { Row.of(it) }))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            _all.value = next
        }
    }
}
