package io.ucc.iosvpn

import io.ucc.iosinfra.DirectoryBlobStore
import io.ucc.iosinfra.FileCodec
import io.ucc.iosinfra.FileProfileStore
import io.ucc.iosinfra.FileServerHealthStore
import io.ucc.iosinfra.FileSubscriptionStore
import io.ucc.iosinfra.KeyValuePreferences
import io.ucc.iosinfra.KeyValueSettingsStore
import io.ucc.iosinfra.KeychainKeys
import io.ucc.iosinfra.UserDefaultsKeyValueStore
import io.ucc.iosinfra.keychainFileCodec
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.stringByAppendingPathComponent

/**
 * The one place that decides *where* the ios-infra stores live so that the host app
 * and the packet tunnel extension read the same files: the App Group container
 * (`containerURLForSecurityApplicationGroupIdentifier`) and the App Group
 * `NSUserDefaults` suite. Secrets stay in the Keychain (`KeychainKeys`, shared via
 * the keychain access group), never in UserDefaults. No store implementation is
 * duplicated — this only constructs the existing ones with shared locations.
 *
 * Requires both targets to carry the `com.apple.security.application-groups`
 * entitlement with [AppleTargetIds.appGroup] and the keychain-access-groups
 * entitlement with [AppleTargetIds.keychainAccessGroup].
 */
@OptIn(ExperimentalForeignApi::class)
public class AppGroupStorage(private val ids: AppleTargetIds = AppleTargetIds.Default) {
    public class MissingAppGroup(group: String) : IllegalStateException("App Group container '$group' unavailable — check the application-groups entitlement")

    private val containerPath: String by lazy {
        val url: NSURL = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(ids.appGroup)
            ?: throw MissingAppGroup(ids.appGroup)
        url.path ?: throw MissingAppGroup(ids.appGroup)
    }

    public val dataDirectory: String get() = (containerPath as NSString).stringByAppendingPathComponent("ucc")

    public val defaults: UserDefaultsKeyValueStore by lazy { UserDefaultsKeyValueStore(ids.appGroup) }
    public val keys: KeychainKeys by lazy { KeychainKeys(service = KeychainKeys.DEFAULT_SERVICE) }
    private val codec: FileCodec by lazy { keychainFileCodec(keys, KeychainKeys.PROFILES_ALIAS) }
    private val blobs: DirectoryBlobStore by lazy { DirectoryBlobStore(dataDirectory) }

    public val profiles: FileProfileStore by lazy { FileProfileStore(blobs, codec) }
    public val subscriptions: FileSubscriptionStore by lazy { FileSubscriptionStore(blobs, codec) }
    public val health: FileServerHealthStore by lazy { FileServerHealthStore(blobs, codec) }
    public val preferences: KeyValuePreferences by lazy { KeyValuePreferences(defaults) }
    public val settings: KeyValueSettingsStore by lazy { KeyValueSettingsStore(defaults) }
}
