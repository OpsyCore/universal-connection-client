package io.ucc.app.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android-Keystore-backed AES key. The key material never leaves the
 * Keystore (hardware-backed where available); the app only holds a handle.
 *
 * No user authentication is required for the key: the VPN service must read
 * profiles unattended (boot, network switch, system restart of the service).
 */
object KeystoreKeys {
    private const val PROVIDER = "AndroidKeyStore"
    const val PROFILES_ALIAS = "ucc.profiles.v1"

    @Synchronized
    fun aesKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }
}
