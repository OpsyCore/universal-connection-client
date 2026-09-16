package io.ucc.app.data

import io.ucc.core.engine.CoreLogLine
import io.ucc.core.engine.manager.ConnectionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Ring buffer of the last [capacity] log entries from the core and the
 * connection manager, for the Logs screen and for "copy logs".
 *
 * Nothing here redacts: the adapter redacts core lines and the manager's
 * events contain no secrets by construction. Kept in memory only — never
 * written to disk.
 */
class LogBuffer(
    scope: CoroutineScope,
    coreLogs: Flow<CoreLogLine>,
    managerEvents: Flow<ConnectionEvent>,
    private val capacity: Int = 2_000,
) {
    data class Entry(val epochMs: Long, val level: Level, val source: Source, val message: String)
    enum class Level { DEBUG, INFO, WARN, ERROR }
    enum class Source { CORE, APP }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    init {
        scope.launch { coreLogs.collect { add(Entry(it.epochMs, levelOf(it.level), Source.CORE, it.message)) } }
        scope.launch { managerEvents.collect { add(Entry(it.epochMs, if (it.error != null) Level.ERROR else Level.INFO, Source.APP, it.message)) } }
    }

    @Synchronized
    private fun add(e: Entry) {
        val cur = _entries.value
        _entries.value = if (cur.size >= capacity) cur.drop(cur.size - capacity + 1) + e else cur + e
    }

    @Synchronized
    fun clear() { _entries.value = emptyList() }

    fun renderPlainText(): String = _entries.value.joinToString("\n") { "${it.epochMs} ${it.level.name.padEnd(5)} ${it.source.name.padEnd(4)} ${it.message}" }

    private companion object {
        /** sing-box/libbox levels: 0 panic,1 fatal,2 error,3 warn,4 info,5 debug,6 trace. */
        fun levelOf(core: Int): Level = when {
            core <= 2 -> Level.ERROR
            core == 3 -> Level.WARN
            core == 4 -> Level.INFO
            else -> Level.DEBUG
        }
    }
}
