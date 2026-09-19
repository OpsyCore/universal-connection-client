@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.ucc.iosinfra

import platform.CoreFoundation.CFTypeRefVar
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * Per-store 64-byte file keys held in the Keychain as generic-password items
 * (service = [service], account = alias). Created lazily with
 * `SecRandomCopyBytes`; `AfterFirstUnlockThisDeviceOnly` so a background
 * extension can read them after reboot and they never leave the device
 * (no iCloud Keychain, no backup restore onto another device).
 *
 * Mirrors the role of Android `KeystoreKeys` (alias per store); the aliases are
 * the same strings so the mapping is obvious.
 */
public class KeychainKeys(private val service: String = DEFAULT_SERVICE) {
    /** Cached in memory after first access — the Keychain round-trip is not free. */
    private val cache = HashMap<String, ByteArray>()

    public fun fileKey(alias: String): ByteArray = cache[alias] ?: loadOrCreate(alias).also { cache[alias] = it }

    private fun loadOrCreate(alias: String): ByteArray {
        read(alias)?.let { if (it.size == EtmFileCodec.KEY_BYTES) return it }
        val fresh = DarwinCrypto.randomBytes(EtmFileCodec.KEY_BYTES)
        return when (val status = add(alias, fresh)) {
            errSecSuccess -> fresh
            // Lost a race with another process (app vs. extension): the stored one wins.
            errSecDuplicateItem -> read(alias) ?: error("Keychain item vanished for $alias")
            else -> error("Keychain add failed for $alias: $status")
        }
    }

    private fun read(alias: String): ByteArray? = memScoped {
        val query = baseQuery(alias)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        when (status) {
            errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()
            errSecItemNotFound -> null
            else -> error("Keychain read failed for $alias: $status")
        }
    }

    private fun add(alias: String, key: ByteArray): Int = memScoped {
        val query = baseQuery(alias)
        val data = CFBridgingRetain(key.toNSData())
        CFDictionarySetValue(query, kSecValueData, data)
        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val status = SecItemAdd(query, null)
        CFBridgingRelease(data)
        CFRelease(query)
        status
    }

    private fun baseQuery(alias: String) = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!.also { q ->
        CFDictionarySetValue(q, kSecClass, kSecClassGenericPassword)
        retained(service) { CFDictionarySetValue(q, kSecAttrService, it) }
        retained(alias) { CFDictionarySetValue(q, kSecAttrAccount, it) }
    }

    private inline fun retained(value: Any, block: (CFTypeRef?) -> Unit) {
        // The dictionary retains its values (kCFTypeDictionaryValueCallBacks); release our +1 after insertion.
        val ref = CFBridgingRetain(value)
        try { block(ref) } finally { CFBridgingRelease(ref) }
    }

    public companion object {
        public const val DEFAULT_SERVICE: String = "io.ucc.storage"
        public const val PROFILES_ALIAS: String = "ucc.profiles.v1"
    }
}

public fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

public fun NSData.toByteArray(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
