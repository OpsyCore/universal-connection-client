@file:OptIn(ExperimentalForeignApi::class)

package io.ucc.iosinfra

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToURL
import platform.Foundation.NSData
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey

/**
 * [BlobStore] over one directory (app container or App Group container).
 * `NSData.writeToURL(atomically = true)` writes a temp file and renames it into
 * place — the same guarantee `SecureFile` relied on with `File.renameTo` on Android.
 * Files carry `CompleteUntilFirstUserAuthentication` protection so a background
 * extension can still read them; confidentiality rests on the [EtmFileCodec] envelope.
 */
public class DirectoryBlobStore(private val directory: String) : BlobStore {
    private val fm = NSFileManager.defaultManager

    init { fm.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null) }

    private fun path(name: String): String = (directory as NSString).stringByAppendingPathComponent(name)
    private fun url(name: String): NSURL = NSURL.fileURLWithPath(path(name))

    override fun exists(name: String): Boolean = fm.fileExistsAtPath(path(name))

    override fun read(name: String): ByteArray =
        NSData.dataWithContentsOfURL(url(name))?.toByteArray() ?: throw BlobIoException("cannot read $name")

    override fun writeAtomically(name: String, bytes: ByteArray) {
        if (!bytes.toNSData().writeToURL(url(name), atomically = true)) throw BlobIoException("cannot write $name")
        fm.setAttributes(mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), ofItemAtPath = path(name), error = null)
    }

    override fun rename(from: String, to: String): Boolean = fm.moveItemAtPath(path(from), toPath = path(to), error = null)

    override fun delete(name: String) { fm.removeItemAtPath(path(name), error = null) }
}

/** [KeyValueStore] over `NSUserDefaults` (standard or an App Group suite). */
public class UserDefaultsKeyValueStore(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : KeyValueStore {
    public constructor(suiteName: String) : this(NSUserDefaults(suiteName = suiteName))
    override fun getString(key: String): String? = defaults.stringForKey(key)
    override fun putString(key: String, value: String?) { if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key) }
    override fun getBoolean(key: String, default: Boolean): Boolean = if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)
    override fun putBoolean(key: String, value: Boolean) { defaults.setBool(value, forKey = key) }
}

/** Production codec wiring: Keychain file key + CommonCrypto primitives. */
public fun keychainFileCodec(keys: KeychainKeys, alias: String): FileCodec = EtmFileCodec(DarwinCrypto, { keys.fileKey(alias) })
