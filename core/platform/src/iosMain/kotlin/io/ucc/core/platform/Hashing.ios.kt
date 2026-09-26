@file:OptIn(ExperimentalForeignApi::class)

package io.ucc.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/** CommonCrypto one-shot SHA-256; same 32-byte digest as `MessageDigest.getInstance("SHA-256")`. */
public actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    out.usePinned { o ->
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, o.addressOf(0).reinterpret<UByteVar>())
        } else {
            bytes.usePinned { i -> CC_SHA256(i.addressOf(0), bytes.size.convert(), o.addressOf(0).reinterpret<UByteVar>()) }
        }
    }
    return out
}
