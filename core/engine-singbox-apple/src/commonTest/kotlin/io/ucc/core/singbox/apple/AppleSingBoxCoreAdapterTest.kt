package io.ucc.core.singbox.apple

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.CoreStatistics
import io.ucc.core.engine.CoreEvent
import io.ucc.core.engine.TunProvider
import io.ucc.core.engine.TunRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val tun = object : TunProvider {
    override fun openTun(request: TunRequest) = 3
    override fun protectSocket(fd: Int) = true
}

class AppleSingBoxCoreAdapterTest {
    @Test fun start_sequence_matches_android_adapter() = runTest {
        val lb = FakeLibbox(); val a = adapter(lb)
        a.start(profile(), CoreStartOptions(), tun)
        assertEquals(listOf("setup", "create", "check", "start"), lb.calls)
        assertTrue(a.isRunning)
        assertTrue(lb.lastConfig!!.contains("\"type\":\"trojan\""), "config from singbox-config generator")
        assertTrue(lb.lastConfig!!.contains(SECRET), "the core itself must receive the real credential")
        a.stop(); assertEquals("closeService", lb.calls.last()); assertFalse(a.isRunning)
        // second start reuses the service and skips setup
        a.start(profile(), CoreStartOptions(), tun)
        assertEquals(1, lb.services); assertEquals(1, lb.calls.count { it == "setup" })
    }

    @Test fun unavailable_libbox_fails_deterministically_without_secrets() = runTest {
        val lb = FakeLibbox(available = false); val a = adapter(lb)
        val e = assertFailsWith<CoreException> { a.start(profile(), CoreStartOptions(), tun) }
        assertIs<ConnectionError.CoreFailure>(e.error)
        assertTrue(e.error.technicalDetail.contains("Libbox unavailable"))
        assertFalse(e.error.technicalDetail.contains(SECRET)); assertFalse(a.isRunning)
        assertEquals("unavailable", a.descriptor.version)
        assertEquals(listOf("setup"), lb.calls)
    }

    @Test fun rejected_config_is_invalid_configuration_and_redacted() = runTest {
        val lb = FakeLibbox().apply { failCheck = IllegalArgumentException("parse config: password=$SECRET bad") }
        val e = assertFailsWith<CoreException> { adapter(lb).start(profile(), CoreStartOptions(), tun) }
        assertIs<ConnectionError.InvalidConfiguration>(e.error)
        assertFalse(e.error.technicalDetail.contains(SECRET)); assertTrue(e.error.technicalDetail.contains("***"))
        assertFalse(lb.calls.contains("start"))
    }

    @Test fun start_failure_is_classified() = runTest {
        val lb = FakeLibbox().apply { failStart = IllegalStateException("tls handshake failed") }
        val a = adapter(lb)
        val e = assertFailsWith<CoreException> { a.start(profile(), CoreStartOptions(), tun) }
        assertIs<ConnectionError.TlsFailure>(e.error); assertFalse(a.isRunning)
    }

    @Test fun stop_and_shutdown_semantics() = runTest {
        val lb = FakeLibbox(); val a = adapter(lb)
        a.stop() // idle stop is a no-op
        assertTrue(lb.calls.isEmpty())
        a.start(profile(), CoreStartOptions(), tun)
        lb.failClose = IllegalStateException("boom")
        val e = assertFailsWith<CoreException> { a.stop() }
        assertIs<ConnectionError.CoreFailure>(e.error); assertFalse(a.isRunning)
        lb.failClose = null
        a.shutdown(); a.shutdown()
        assertEquals(1, lb.calls.count { it == "close" })
        val again = assertFailsWith<CoreException> { a.start(profile(), CoreStartOptions(), tun) }
        assertTrue(again.error.technicalDetail.contains("shut down"))
    }

    @Test fun reload_requires_running_core() = runTest {
        val lb = FakeLibbox(); val a = adapter(lb)
        assertFailsWith<CoreException> { a.reload(profile(), CoreStartOptions()) }
        a.start(profile(), CoreStartOptions(), tun)
        a.reload(profile(), CoreStartOptions(mtu = 1400))
        assertEquals(2, lb.calls.count { it == "start" })
    }

    @Test fun listener_propagates_status_logs_and_service_stop() = runTest {
        val lb = FakeLibbox(); val a = adapter(lb)
        a.start(profile(), CoreStartOptions(), tun)
        assertNull(a.lastStatistics)
        lb.listener!!.onStatus(LibboxStatus(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(CoreStatistics(1, 2, 3, 4, 5, 6, 7, 8), a.lastStatistics)
        assertEquals(CoreStatistics(1, 2, 3, 4, 5, 6, 7, 8), a.statistics.first())
        lb.listener!!.onServiceStopped()
        assertFalse(a.isRunning)
        assertIs<CoreEvent.Fatal>(a.events.first())
        lb.listener!!.onLog(1, "auth token=$SECRET rejected")
        val line = a.logs.first()
        assertFalse(line.message.contains(SECRET)); assertEquals(42L, line.epochMs)
    }

    @Test fun url_test_and_unsupported_profile() = runTest {
        val a = adapter(FakeLibbox())
        assertFailsWith<CoreException> { a.urlTest("https://example.com", 1000) }
        assertEquals(AppleSingBoxCoreFactory.ID, a.descriptor.id)
    }
}
