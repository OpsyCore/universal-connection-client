package io.ucc.app.data

import android.content.Context
import io.ucc.core.engine.manager.ProfileProvider
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Phase-1 persistence: an atomic JSON file inside app-private storage
 * (`filesDir`, mode 0600, excluded from backup by data_extraction_rules).
 *
 * Replaced by Room + encrypted credential columns in Phase 4; the public
 * surface ([profiles], [upsert], [delete], [ProfileProvider]) is what the
 * rest of the app depends on, so the swap is internal.
 */
class JsonProfileStore(context: Context) : ProfileProvider, ProfileStore {
    private val file = File(context.applicationContext.filesDir, "profiles.json")
    private val tmp = File(file.parentFile, "profiles.json.tmp")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val serializer = ListSerializer(ConnectionProfile.serializer())
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    override val profiles: StateFlow<List<ConnectionProfile>> = _profiles

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.exists()) return@withLock
            _profiles.value = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyList() }
        }
    }

    override suspend fun byId(id: String): ConnectionProfile? = _profiles.value.firstOrNull { it.id == id }

    suspend fun upsert(profile: ConnectionProfile) = mutate { list ->
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list.toMutableList().also { it[idx] = profile } else list + profile
    }

    suspend fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }

    override fun current(): List<ConnectionProfile> = _profiles.value

    override suspend fun upsertAll(profiles: List<ConnectionProfile>) = apply(profiles, emptyList())

    override suspend fun deleteAll(ids: Collection<String>) = apply(emptyList(), ids)

    override suspend fun apply(upserts: List<ConnectionProfile>, deleteIds: Collection<String>) = mutate { list ->
        val byId = LinkedHashMap<String, ConnectionProfile>(list.size + upserts.size)
        list.forEach { byId[it.id] = it }
        upserts.forEach { byId[it.id] = it }
        deleteIds.forEach { byId.remove(it) }
        byId.values.toList()
    }

    private suspend fun mutate(block: (List<ConnectionProfile>) -> List<ConnectionProfile>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(_profiles.value)
            tmp.writeText(json.encodeToString(serializer, next))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(serializer, next))
                tmp.delete()
            }
            _profiles.value = next
        }
    }
}
