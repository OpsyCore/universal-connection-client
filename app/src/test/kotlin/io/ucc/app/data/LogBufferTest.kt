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
        assertTrue(text.lines().size == 3 && text.contains("APP  LIFECYCLE connected"), text)
        buf.clear()
        assertTrue(buf.entries.value.isEmpty())
    }

    @Test fun `categories and levels follow event category, filtered export`() = runTest(UnconfinedTestDispatcher()) {
        val app = MutableSharedFlow<ConnectionEvent>()
        val buf = LogBuffer(backgroundScope, MutableSharedFlow(), app)
        app.emit(ConnectionEvent(1, "net", category = ConnectionEvent.Category.NETWORK))
        app.emit(ConnectionEvent(2, "retry", category = ConnectionEvent.Category.RECONNECT))
        app.emit(ConnectionEvent(3, "Failed", error = io.ucc.core.engine.ConnectionError.ConnectionTimeout("probe password=abc")))
        app.emit(ConnectionEvent(4, "ok"))
        assertEquals(listOf(LogBuffer.Level.WARN, LogBuffer.Level.WARN, LogBuffer.Level.ERROR, LogBuffer.Level.INFO), buf.entries.value.map { it.level })
        assertEquals(listOf(LogBuffer.Category.NETWORK, LogBuffer.Category.RECONNECT, LogBuffer.Category.ERROR, LogBuffer.Category.LIFECYCLE), buf.entries.value.map { it.category })
        val errorsOnly = buf.renderPlainText(LogBuffer.Level.ERROR)
        assertEquals(1, errorsOnly.lines().size)
        assertTrue("1970-01-01T00:00:00.003Z" in errorsOnly, errorsOnly)
        assertTrue("password=***" in errorsOnly && "abc" !in errorsOnly, errorsOnly)
    }

    @Test fun `secrets in core lines are scrubbed on ingest`() = runTest(UnconfinedTestDispatcher()) {
        val core = MutableSharedFlow<CoreLogLine>()
        val buf = LogBuffer(backgroundScope, core, MutableSharedFlow())
        core.emit(CoreLogLine(4, "outbound vless uuid=b831381d-6324-4d53-ad4f-8cda48b30811 to https://user:pw@h/x?token=zzz", 1))
        val m = buf.entries.value.single().message
        assertTrue("b831381d" !in m && "user:pw" !in m && "zzz" !in m, m)
    }
}
