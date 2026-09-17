package io.ucc.app.data

import android.content.Context
import io.ucc.app.data.crypto.AesGcmFileCodec
import io.ucc.app.data.crypto.FileCodec
import io.ucc.app.data.crypto.KeystoreKeys
import io.ucc.app.data.crypto.SecureFile
import io.ucc.core.smart.ServerHealth
import io.ucc.core.smart.ServerHealthStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent [ServerHealthStore]: `filesDir/health.enc`, keyed by profile
 * fingerprint. The records themselves hold no addresses or credentials, but a
 * fingerprint is a stable identifier of *which* servers the user has, so the
 * file gets the same at-rest encryption as profiles. Unreadable files are
 * quarantined and treated as empty — health is a cache, never the source of
 * truth.
 */
class JsonServerHealthStore internal constructor(
    dir: File,
    codec: FileCodec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ServerHealthStore {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        AesGcmFileCodec({ KeystoreKeys.aesKey(KeystoreKeys.PROFILES_ALIAS) }),
    )

    private val file = SecureFile(dir, "health", codec)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), ServerHealth.serializer())
    private val mutex = Mutex()
    private val _all = MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    override val all: StateFlow<Map<String, ServerHealth>> = _all

    suspend fun load() = withContext(io) {
        mutex.withLock {
            val r = runCatching { file.read() }.getOrNull() as? SecureFile.ReadResult.Ok ?: return@withLock
            _all.value = runCatching { json.decodeFromString(serializer, r.bytes.decodeToString()) }.getOrDefault(emptyMap())
        }
    }

    override suspend fun update(fingerprint: String, transform: (ServerHealth) -> ServerHealth) =
        write { m -> m + (fingerprint to transform(m[fingerprint] ?: ServerHealth.EMPTY)) }

    override suspend fun prune(liveFingerprints: Set<String>) = write { m ->
        if (m.keys.all { it in liveFingerprints }) m else m.filterKeys { it in liveFingerprints }
    }

    override suspend fun clear() = write { emptyMap() }

    private suspend fun write(block: (Map<String, ServerHealth>) -> Map<String, ServerHealth>) = withContext(io) {
        mutex.withLock {
            val next = block(_all.value)
            if (next == _all.value) return@withLock
            _all.value = next
            runCatching { file.write(json.encodeToString(serializer, next).encodeToByteArray()) } // cache: a failed write is not fatal
            Unit
        }
    }
}
