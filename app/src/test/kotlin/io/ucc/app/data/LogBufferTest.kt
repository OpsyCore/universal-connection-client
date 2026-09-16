package io.ucc.app.data

import io.ucc.core.engine.CoreLogLine
import io.ucc.core.engine.manager.ConnectionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LogBufferTest {
    @Test fun `merges core and app entries, maps levels, caps size, renders and clears`() = runTest(UnconfinedTestDispatcher()) {
        val core = MutableSharedFlow<CoreLogLine>()
        val app = MutableSharedFlow<ConnectionEvent>()
        val buf = LogBuffer(backgroundScope, core, app, capacity = 3)
        core.emit(CoreLogLine(2, "err", 1L)); core.emit(CoreLogLine(3, "warn", 2L)); core.emit(CoreLogLine(5, "dbg", 3L))
        assertEquals(listOf(LogBuffer.Level.ERROR, LogBuffer.Level.WARN, LogBuffer.Level.DEBUG), buf.entries.value.map { it.level })
        app.emit(ConnectionEvent(4L, "connected"))
        assertEquals(3, buf.entries.value.size)
        assertEquals(listOf("warn", "dbg", "connected"), buf.entries.value.map { it.message })
        assertEquals(LogBuffer.Source.APP, buf.entries.value.last().source)
        val text = buf.renderPlainText()
        assertTrue(text.lines().size == 3 && text.contains("APP  connected"))
        buf.clear()
        assertTrue(buf.entries.value.isEmpty())
    }
}
