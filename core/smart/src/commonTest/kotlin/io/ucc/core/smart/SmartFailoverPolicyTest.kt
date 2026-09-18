package io.ucc.core.smart

import io.ucc.core.engine.ConnectionError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmartFailoverPolicyTest {
    private val policy = SmartFailoverPolicy(SmartServerSelector(), maxSwitchesPerSession = 2)
    private val ranked = listOf(cand("a", healthy(20)), cand("b", healthy(30)), cand("c", healthy(40)), cand("d", healthy(50)))
    private val timeout = ConnectionError.ConnectionTimeout("x")

    @Test fun `manual mode never switches`() {
        assertEquals(SmartFailoverPolicy.Decision.GiveUp(SmartFailoverPolicy.Reason.NOT_SMART_MODE), policy.onTerminalFailure(false, null, timeout, ranked, NOW))
        assertEquals(SmartFailoverPolicy.Decision.GiveUp(SmartFailoverPolicy.Reason.NOT_SMART_MODE), policy.onTerminalFailure(false, SmartFailoverPolicy.Session("a"), timeout, ranked, NOW))
    }

    @Test fun `smart mode switches to next candidate after terminal failure`() {
        val s = SmartFailoverPolicy.Session("a")
        val d = assertIs<SmartFailoverPolicy.Decision.Switch>(policy.onTerminalFailure(true, s, timeout, ranked, NOW))
        assertEquals("b", d.to.profile.id)
        assertEquals(setOf("a", "b"), s.tried)
    }

    @Test fun `switch limit bounds the loop and never revisits a server`() {
        val s = SmartFailoverPolicy.Session("a")
        assertEquals("b", assertIs<SmartFailoverPolicy.Decision.Switch>(policy.onTerminalFailure(true, s, timeout, ranked, NOW)).to.profile.id)
        assertEquals("c", assertIs<SmartFailoverPolicy.Decision.Switch>(policy.onTerminalFailure(true, s, timeout, ranked, NOW)).to.profile.id)
        assertEquals(SmartFailoverPolicy.Decision.GiveUp(SmartFailoverPolicy.Reason.SWITCH_LIMIT), policy.onTerminalFailure(true, s, timeout, ranked, NOW))
    }

    @Test fun `no candidate left gives up`() {
        val s = SmartFailoverPolicy.Session("a")
        assertEquals(SmartFailoverPolicy.Decision.GiveUp(SmartFailoverPolicy.Reason.NO_CANDIDATE), policy.onTerminalFailure(true, s, timeout, listOf(cand("a", healthy(1)), cand("x", failing(3))), NOW))
    }

    @Test fun `non-server errors never switch`() {
        val s = SmartFailoverPolicy.Session("a")
        for (e in listOf(ConnectionError.VpnPermissionDenied(), ConnectionError.VpnRevoked(), ConnectionError.NetworkUnavailable(), ConnectionError.InvalidConfiguration("x"))) {
            assertEquals(SmartFailoverPolicy.Decision.GiveUp(SmartFailoverPolicy.Reason.ERROR_NOT_SERVER_RELATED), policy.onTerminalFailure(true, s, e, ranked, NOW), e.userMessageKey)
        }
        assertEquals(0, s.switches)
    }

    @Test fun `error to failure class mapping`() {
        assertEquals(TestFailure.TIMEOUT, SmartFailoverPolicy.toTestFailure(timeout))
        assertEquals(TestFailure.AUTH_FAILURE, SmartFailoverPolicy.toTestFailure(ConnectionError.AuthenticationFailure("x")))
        assertEquals(TestFailure.TLS_FAILURE, SmartFailoverPolicy.toTestFailure(ConnectionError.TlsFailure("x")))
        assertEquals(TestFailure.NETWORK_UNAVAILABLE, SmartFailoverPolicy.toTestFailure(ConnectionError.NetworkUnavailable()))
        assertEquals(TestFailure.CANCELLED, SmartFailoverPolicy.toTestFailure(ConnectionError.VpnRevoked()))
    }
}
