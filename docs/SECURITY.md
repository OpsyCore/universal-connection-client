# Security notes

Status: Phase 1. Reviewed items only; the full review is Phase 8.

## Data at rest
- Profiles (`filesDir/profiles.enc`) and subscription records
  (`filesDir/subscriptions.enc`) are **AES-256-GCM encrypted at rest** with an
  Android-Keystore key (alias `ucc.profiles.v1`, hardware-backed where the
  device supports it, randomised IV per write, file magic bound as AAD). The
  whole document is encrypted, not only `@Secret` fields: host/SNI/path
  identify the user's server just as much as the password. See
  `app/.../data/crypto/` and `EncryptedStoresTest`.
- Phase-1 plaintext `profiles.json` / `subscriptions.json` are migrated on the
  first load after upgrade, then overwritten with random bytes and deleted.
- If the file cannot be decrypted (key lost after factory reset / restore,
  tampering) it is moved to `*.corrupt-<ts>` — never silently deleted — the
  store starts empty and the UI shows a one-time notice.
- The key does **not** require user authentication: `UcVpnService` must read
  profiles unattended (boot, network change, system-restarted service).
- Decision: whole-file JSON + Keystore instead of Room + SQLCipher. The data set
  is small (hundreds of rows), access is whole-list, and SQLCipher adds a large
  native dependency; Room remains the plan only if per-row queries become
  necessary.
- `Authentication.toString()` masks every `@Secret` field, so accidental
  `"$profile"` in a log line cannot leak credentials (`AuthenticationToStringTest`).
- Cloud backup and device-to-device transfer are disabled for all domains
  (`data_extraction_rules.xml`, `allowBackup=false`).

## Logging
- `ConnectionProfile.toLogString()` prints protocol, redacted host, port,
  transport only.
- `SingBoxCoreAdapter.redact()` scrubs `password/uuid/private_key/...=` tokens
  and UUIDs from sing-box messages before they reach the log flow.
- No analytics SDK. No crash reporter uploads. Core stderr goes to app cache only.

## Network
- App-originated HTTP (probe, later subscriptions) is HTTPS-only via
  `network_security_config.xml`; system trust anchors only.
- `TlsSettings.allowInsecure` is stored faithfully but never defaulted to
  true; the UI must warn (Phase 7).
- `systemCertificates()` hands the system CA store to sing-box so proxy TLS
  validation uses the same trust as the OS.

## Components
- `UcVpnService` is `exported=true` **with** `android.permission.BIND_VPN_SERVICE`
  — required by the framework; only the system holds that permission.
- `MainActivity` is the only other exported component.
- Permissions requested: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+SPECIAL_USE),
  POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED (declared for Phase 3 boot-connect;
  no receiver yet), QUERY_ALL_PACKAGES (per-app routing; Play policy justification
  required — will be revisited before release).

## Third-party core
- `libbox.aar` is built by CI from a pinned upstream tag; the SHA-256 pin in
  `core/engine-singbox/libbox.sha256` is checked at build time (currently
  `unpinned` until the first CI build publishes the hash).

## Kill switch and "strict routing" (Phase 5)

`strict_route` in the sing-box TUN inbound only prevents traffic from bypassing the
tunnel **while the tunnel is up**. It is **not** a kill switch: if the VPN process
dies, is stopped, or has not started yet, the OS routes traffic normally.

The only OS-enforced kill switch on Android is the system setting
*Settings → Network → VPN → (this app) → Always-on VPN + Block connections without
VPN* (API 24+, `VpnService` opts in via `android:supportsAlwaysOn`, our manifest
does not disable it). The Settings screen wording reflects this; the app never
claims to provide a kill switch of its own.

## DNS

When connected, the TUN inbound captures port-53 traffic and the sing-box DNS
module answers via the configured remote server through the proxy (`detour`).
This is the standard design, but it has **not been independently leak-tested on a
device in this project** (device verification is NOT AVAILABLE in CI). We do not
advertise "DNS leak protection". Private DNS (DoT set by the OS) is handled by
Android outside the tunnel's control on some versions.
