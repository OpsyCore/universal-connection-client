package io.ucc.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorClassifierTest {
    private fun kind(msg: String) = ErrorClassifier.classifyStart(msg)::class.simpleName

    @Test fun `maps common core messages to error classes`() {
        assertEquals("VpnPermissionDenied", kind("VpnService not prepared"))
        assertEquals("AuthenticationFailure", kind("trojan: authentication failed for user"))
        assertEquals("TlsFailure", kind("tls: failed to verify certificate: x509: unknown authority"))
        assertEquals("InvalidConfiguration", kind("decode config: json: unknown field \"flow2\""))
        assertEquals("NetworkUnavailable", kind("dial tcp: connect: network is unreachable"))
        assertEquals("DnsFailure", kind("lookup example.com: no such host"))
        assertEquals("ConnectionTimeout", kind("dial tcp 1.2.3.4:443: i/o timeout"))
        assertEquals("ConnectionTimeout", kind("connection refused"))
        assertEquals("CoreFailure", kind("something odd happened"))
        assertEquals("CoreFailure", kind(""))
    }

    @Test fun `retryable flag follows class`() {
        assertTrue(ErrorClassifier.classifyStart("i/o timeout").retryable)
        assertFalse(ErrorClassifier.classifyStart("certificate expired").retryable)
    }

    @Test fun `technical detail never carries credential values`() {
        val e = ErrorClassifier.classifyStart("start: password=hunter2 uuid: 123e4567-e89b token=\"abc\" i/o timeout", "sing-box")
        assertFalse("hunter2" in e.technicalDetail)
        assertFalse("123e4567" in e.technicalDetail)
        assertFalse("abc" in e.technicalDetail)
        assertTrue(e.technicalDetail.startsWith("sing-box start:"))
    }
}
