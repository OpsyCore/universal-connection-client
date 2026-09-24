package io.ucc.app.data

import io.ucc.applogic.ProfileStore
import android.content.Context
import android.util.Log
import io.ucc.app.data.crypto.AesGcmFileCodec
import io.ucc.app.data.crypto.FileCodec
import io.ucc.app.data.crypto.KeystoreKeys
import io.ucc.app.data.crypto.SecureFile
import io.ucc.core.engine.manager.ProfileProvider
import io.ucc.core.model.ConnectionProfile
import kotlinx.coroutines.CoroutineDispatcher
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
 * Profile persistence: one JSON document, **AES-256-GCM encrypted at rest**
 * with an Android-Keystore key ([KeystoreKeys.PROFILES_ALIAS]), written
 * atomically to `filesDir/profiles.enc`. The Phase-1 plaintext `profiles.json`
 * is migrated and shredded on first load (see [SecureFile]).
 *
 * The whole document is encrypted (not just `@Secret` columns) because every
 * field — host, SNI, path — identifies the user's server; there is nothing in a
 * profile that benefits from being readable without the key.
 *
 * Public surface ([profiles], [upsert], [delete], [ProfileProvider], [ProfileStore])
 * is what the rest of the app depends on.
 */
class JsonProfileStore internal constructor(
    dir: File,
    codec: FileCodec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Redacted messages only (counts, file names). Injected so JVM tests need no android.util.Log. */
    private val log: (String) -> Unit = {},
) : ProfileProvider, ProfileStore {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        AesGcmFileCodec({ KeystoreKeys.aesKey(KeystoreKeys.PROFILES_ALIAS) }),
        log = { Log.i(TAG, it) },
    )

    private val file = SecureFile(dir, "profiles", codec)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val serializer = ListSerializer(ConnectionProfile.serializer())
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    override val profiles: StateFlow<List<ConnectionProfile>> = _profiles

    /** Set when the last load found an undecryptable file; the UI shows a one-time notice. */
    private val _lastLoadProblem = MutableStateFlow<LoadProblem?>(null)
    val lastLoadProblem: StateFlow<LoadProblem?> = _lastLoadProblem

    data class LoadProblem(val quarantinedFileName: String)

    suspend fun load() = withContext(io) {
        mutex.withLock {
            when (val r = runCatching { file.read() }.getOrElse { SecureFile.ReadResult.Missing }) {
                SecureFile.ReadResult.Missing -> Unit
                is SecureFile.ReadResult.Ok -> {
                    _profiles.value = runCatching { json.decodeFromString(serializer, r.bytes.decodeToString()) }.getOrElse { emptyList() }
                    if (r.migratedFromPlaintext) log("migrated ${_profiles.value.size} profiles to encrypted storage")
                }
                is SecureFile.ReadResult.Unreadable -> {
                    log("profile store unreadable; quarantined as ${r.quarantined.name}")
                    _lastLoadProblem.value = LoadProblem(r.quarantined.name)
                }
            }
        }
    }

    fun clearLoadProblem() { _lastLoadProblem.value = null }

    override suspend fun byId(id: String): ConnectionProfile? = _profiles.value.firstOrNull { it.id == id }

    suspend fun upsert(profile: ConnectionProfile) = apply(listOf(profile), emptyList())

    suspend fun delete(id: String) = apply(emptyList(), listOf(id))

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

    private suspend fun mutate(block: (List<ConnectionProfile>) -> List<ConnectionProfile>) = withContext(io) {
        mutex.withLock {
            val next = block(_profiles.value)
            if (next == _profiles.value) return@withLock
            file.write(json.encodeToString(serializer, next).encodeToByteArray())
            _profiles.value = next
        }
    }

    private companion object { const val TAG = "ProfileStore" }
}
