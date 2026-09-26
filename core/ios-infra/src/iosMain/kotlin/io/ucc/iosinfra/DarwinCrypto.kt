@file:OptIn(ExperimentalForeignApi::class)

package io.ucc.iosinfra

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCOperation
import platform.posix.size_tVar
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** CommonCrypto / Security.framework implementation of [CryptoPrimitives]. */
public object DarwinCrypto : CryptoPrimitives {
    override fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray = crypt(kCCEncrypt, key, iv, plain)

    override fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray): ByteArray = crypt(kCCDecrypt, key, iv, cipher)

    private fun crypt(op: CCOperation, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key" }; require(iv.size == kCCBlockSizeAES128.toInt()) { "16-byte IV" }
        val out = ByteArray(input.size + kCCBlockSizeAES128.toInt())
        var moved = 0
        memScoped {
            val n = alloc<size_tVar>()
            val status = key.usePinned { k -> iv.usePinned { v -> out.usePinned { o ->
                if (input.isEmpty()) {
                    CCCrypt(op, kCCAlgorithmAES, kCCOptionPKCS7Padding, k.addressOf(0), key.size.convert(), v.addressOf(0), null, 0.convert(), o.addressOf(0), out.size.convert(), n.ptr)
                } else input.usePinned { i ->
                    CCCrypt(op, kCCAlgorithmAES, kCCOptionPKCS7Padding, k.addressOf(0), key.size.convert(), v.addressOf(0), i.addressOf(0), input.size.convert(), o.addressOf(0), out.size.convert(), n.ptr)
                }
            } } }
            if (status != kCCSuccess) throw IllegalArgumentException("CCCrypt status $status")
            moved = n.value.toInt()
        }
        return out.copyOf(moved)
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        key.usePinned { k -> out.usePinned { o ->
            if (data.isEmpty()) CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.convert(), null, 0.convert(), o.addressOf(0))
            else data.usePinned { d -> CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.convert(), d.addressOf(0), data.size.convert(), o.addressOf(0)) }
        } }
        return out
    }

    override fun randomBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        if (n == 0) return out
        val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
        return out
    }
}
