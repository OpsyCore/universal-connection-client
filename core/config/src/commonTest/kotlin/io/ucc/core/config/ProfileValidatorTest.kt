package io.ucc.core.config

import io.ucc.core.config.parser.LinkParser
import io.ucc.core.model.TlsSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileValidatorTest {
    private val base = (LinkParser({ "x" }, { 1L }).parse("trojan://pw@example.com:443#n") as ParseResult.Success).profile

    @Test fun `valid profile passes`() { assertTrue(ProfileValidator.isValid(base)) }
    @Test fun `ipv6 literal passes`() { assertTrue(ProfileValidator.isValid(base.copy(address = "2001:db8::1"))) }
    @Test fun `blank host and bad port are reported`() {
        val errs = ProfileValidator.validate(base.copy(address = " ", port = 0))
        assertEquals(2, errs.size)
        assertIs<ConfigError.MissingField>(errs[0]); assertIs<ConfigError.InvalidField>(errs[1])
    }
    @Test fun `blank name is reported`() { assertIs<ConfigError.MissingField>(ProfileValidator.validate(base.copy(name = "")).single()) }
    @Test fun `reality without key is reported`() {
        val p = base.copy(tls = TlsSettings(enabled = true, reality = TlsSettings.Reality(publicKey = "")))
        assertTrue(ProfileValidator.validate(p).any { it.detail.contains("reality") })
    }
}
