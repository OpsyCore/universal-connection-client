package io.ucc.core.vpn

import io.ucc.core.vpn.BootConnectPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootConnectPolicyTest {
    private fun d(enabled: Boolean = true, consent: Boolean = true, selected: String? = "sel", last: String? = "last", active: Boolean = false) =
        BootConnectPolicy.decide(enabled, consent, selected, last, active)

    @Test fun `default off never connects even when everything else is in place`() {
        assertEquals(Decision.Skip("switch off"), d(enabled = false))
    }

    @Test fun `no VPN consent never connects`() {
        assertEquals(Decision.Skip("no VPN consent"), d(consent = false))
        assertEquals(Decision.Skip("no VPN consent"), d(consent = false, enabled = true, selected = "sel"))
    }

    @Test fun `already active tunnel is left alone`() {
        assertEquals(Decision.Skip("already active"), d(active = true))
    }

    @Test fun `selected profile wins over last active and blank ids are ignored`() {
        assertEquals(Decision.Connect("sel"), d())
        assertEquals(Decision.Connect("last"), d(selected = null))
        assertEquals(Decision.Connect("last"), d(selected = "  "))
        assertEquals(Decision.Skip("no profile"), d(selected = null, last = null))
        assertEquals(Decision.Skip("no profile"), d(selected = "", last = ""))
    }

    @Test fun `only the two boot actions are accepted`() {
        assertTrue("android.intent.action.BOOT_COMPLETED" in BootConnectPolicy.ACCEPTED_ACTIONS)
        assertTrue("android.intent.action.LOCKED_BOOT_COMPLETED" in BootConnectPolicy.ACCEPTED_ACTIONS)
        assertEquals(2, BootConnectPolicy.ACCEPTED_ACTIONS.size)
        assertTrue("android.intent.action.MY_PACKAGE_REPLACED" !in BootConnectPolicy.ACCEPTED_ACTIONS)
    }
}
