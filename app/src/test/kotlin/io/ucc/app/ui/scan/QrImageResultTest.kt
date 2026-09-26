package io.ucc.app.ui.scan

import kotlin.test.Test
import kotlin.test.assertEquals

class QrImageResultTest {
    @Test fun `no codes or only blank codes means NoQr`() {
        assertEquals(QrImageResult.NoQr, QrImageResult.fromRawValues(emptyList()))
        assertEquals(QrImageResult.NoQr, QrImageResult.fromRawValues(listOf(null, "", "   ")))
    }

    @Test fun `single code is passed through trimmed`() {
        val r = QrImageResult.fromRawValues(listOf("  vless://x@h:443?type=tcp#n \n"))
        assertEquals(QrImageResult.Found("vless://x@h:443?type=tcp#n", 1), r)
    }

    @Test fun `multiple codes are joined one per line and deduplicated`() {
        val r = QrImageResult.fromRawValues(listOf("ss://a", null, "trojan://b", "ss://a"))
        assertEquals(QrImageResult.Found("ss://a\ntrojan://b", 2), r)
    }

    @Test fun `malformed payload is still handed to the import pipeline unchanged`() {
        // Classification (malformed / unsupported / duplicate) is the parser's job, not the decoder's.
        assertEquals(QrImageResult.Found("not a config", 1), QrImageResult.fromRawValues(listOf("not a config")))
    }
}
