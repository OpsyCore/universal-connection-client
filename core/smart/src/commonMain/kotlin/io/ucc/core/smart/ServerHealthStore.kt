package io.ucc.core.smart

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Health records keyed by **profile fingerprint** (content identity), not by
 * profile id or name. Consequences, all intentional:
 *  - duplicate profiles (same endpoint imported twice) share one record;
 *  - a renamed profile keeps its history;
 *  - a subscription refresh that keeps the endpoint keeps the history, one
 *    that changes it (new fingerprint) starts fresh;
 *  - records whose fingerprint no longer matches any stored profile are
 *    ignored by the selector and removed by [prune].
 */
public interface ServerHealthStore {
    public val all: StateFlow<Map<String, ServerHealth>>
    public fun get(fingerprint: String): ServerHealth = all.value[fingerprint] ?: ServerHealth.EMPTY
    public suspend fun update(fingerprint: String, transform: (ServerHealth) -> ServerHealth)
    /** Drops every record whose fingerprint is not in [liveFingerprints]. */
    public suspend fun prune(liveFingerprints: Set<String>)
    public suspend fun clear()
}

/** In-memory implementation; the app wraps it with encrypted persistence. */
public class InMemoryServerHealthStore(initial: Map<String, ServerHealth> = emptyMap()) : ServerHealthStore {
    private val _all = MutableStateFlow(initial)
    override val all: StateFlow<Map<String, ServerHealth>> = _all

    override suspend fun update(fingerprint: String, transform: (ServerHealth) -> ServerHealth) {
        _all.update { m -> m + (fingerprint to transform(m[fingerprint] ?: ServerHealth.EMPTY)) }
    }

    override suspend fun prune(liveFingerprints: Set<String>) {
        _all.update { m -> m.filterKeys { it in liveFingerprints } }
    }

    override suspend fun clear() { _all.value = emptyMap() }
}
