# core/ios-infra — iOS platform infrastructure (KMP Phase 4)

Implements the shared store/port interfaces for Apple targets. No UI, no
NetworkExtension, no Libbox, no VPN code. Android is untouched: it keeps
`app/data/*` (Keystore + AES-GCM). Both platforms implement the same
interfaces from `core/app-logic`, `core/smart` and `core/engine-api`.

## Layout
| Source set | Content |
|---|---|
| `commonMain` | Ports (`BlobStore`, `KeyValueStore`, `CryptoPrimitives`, `FileCodec`), `SecureFile`, `EtmFileCodec`, `FileProfileStore`, `FileSubscriptionStore`, `FileServerHealthStore`, `KeyValuePreferences` (SelectionStore + LastProfileStore + LanguageStore), `KeyValueSettingsStore`, in-memory ports |
| `iosMain` | `DarwinCrypto` (CommonCrypto AES-CBC/HMAC + `SecRandomCopyBytes`), `KeychainKeys`, `DirectoryBlobStore` (NSFileManager), `UserDefaultsKeyValueStore`, `PathNetworkMonitor` (`nw_path_monitor`), `keychainFileCodec()` |
| `commonTest` | envelope framing/tamper, SecureFile quarantine, store contracts + Android document compatibility, preference keys |
| `jvmTest` | real AES/HMAC via javax.crypto incl. NIST/RFC known answers |

## Interfaces implemented
`ProfileStore`, `ProfileProvider` → `FileProfileStore`; `SubscriptionStore` → `FileSubscriptionStore`;
`ServerHealthStore` → `FileServerHealthStore`; `SelectionStore`, `LanguageStore`, `LastProfileStore` (iOS
declaration, same shape as the Android one in `core/vpn`) → `KeyValuePreferences`; `SettingsStore` →
`KeyValueSettingsStore`; `NetworkMonitor` → `PathNetworkMonitor` (also exposes `underlying: StateFlow<UnderlyingNetwork?>`).

## Storage design and Android compatibility
* **JSON documents are identical** to Android (same `Json` flags, same serializers, same `Row` for
  subscriptions, same `Map<fingerprint, ServerHealth>`), same file names `profiles.enc`,
  `subscriptions.enc`, `health.enc`, same quarantine name `*.enc.corrupt-<ts>`. Only the byte envelope differs.
* **Envelope `UCC2`** (iOS): `"UCC2"` · ivLen(16) · IV · AES-256-CBC/PKCS7 · HMAC-SHA-256(magic‖ivLen‖IV‖ct).
  Android keeps `UCC1` (AES-256-GCM, magic as AAD). GCM is not used on iOS because CommonCrypto has no
  public GCM API and CryptoKit is Swift-only; encrypt-then-MAC with OS primitives gives equivalent
  integrity+confidentiality without hand-rolled crypto. The two magics can never be confused; a foreign or
  tampered file is quarantined, never silently dropped.
* **Keys**: one 64-byte random key per alias (`ucc.profiles.v1`, shared by the three stores like Android),
  stored as a Keychain generic-password item, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` (readable by a
  background extension after reboot; never synced or restored to another device). Duplicate-add races between
  app and extension resolve to the stored key.
* **Files**: `NSData.writeToURL(atomically = true)` (temp + rename) with
  `NSFileProtectionCompleteUntilFirstUserAuthentication`; confidentiality comes from the envelope.
* **Preferences**: `NSUserDefaults` with Android key names `selected_profile_id`, `last_active_profile_id`,
  `smart_selection`, `connection_settings_v1` (+ `app_language`, iOS-only because Android delegates to per-app locales).
* No plaintext-migration branch: no plaintext generation ever shipped on iOS.

## Linux limitations (honest)
* iOS sources are **compiled** (klib, iosArm64 + iosSimulatorArm64) in CI; iOS tests are **not executed**.
* Keychain, NSFileManager, NSUserDefaults and `nw_path_monitor` behaviour is verified only by compilation and
  code review until an Apple runtime is available. The JVM tests exercise all logic above those four seams.
* Wiring into an iOS app / extension (an `AppGraph` equivalent) is Phase 5 and not part of this module.
