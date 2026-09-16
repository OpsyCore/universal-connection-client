# Security notes

Status: Phase 1. Reviewed items only; the full review is Phase 8.

## Data at rest
- Profiles are stored in `filesDir/profiles.json` (app-private, mode 0600) —
  **temporary**; Phase 4 moves credentials into Room columns encrypted with an
  Android-Keystore-backed AES-GCM key. Fields to encrypt are marked `@Secret`
  in `core/model`.
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
