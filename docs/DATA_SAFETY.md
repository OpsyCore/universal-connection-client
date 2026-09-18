# Google Play Data Safety — working sheet

> **Not a legal certification.** This maps the app's *actual* behaviour (from
> source, release candidate v1.0.0) to the questions in the Play Console Data
> Safety form. The final answers must be entered and confirmed by a human who is
> accountable for the listing. Column "status" marks what is a technical fact
> versus what needs a product/legal decision.

## A. Overview questions

| Play question | Technical answer | status |
|---|---|---|
| Does your app collect or share any of the required user data types? | The app **processes** server credentials, traffic and other data on-device, but Google's definition of "collected" is *transmitted off the device to the developer or a third party the developer chose*. The app transmits data only to servers **the user configured** (their proxy, their subscription provider) and a connectivity probe. No data reaches the developer. | **Needs confirmation** — Google treats "data sent to user-selected third party" case-by-case; the conservative reading is "No data collected, no data shared", but a reviewer may consider *traffic routed through a user's proxy* as data handled by a third party. Get a policy read. |
| Is all user data encrypted in transit? | App-originated HTTP is HTTPS-only. Tunnel traffic encryption depends on the protocol the **user** chose (e.g. Shadowsocks/VLESS+TLS encrypt; plain `socks://`/`http://` proxies do not). | Answer "Yes" only if you restrict store distribution to encrypted profiles, otherwise explain. **Product decision.** |
| Do you provide a way for users to request deletion? | All data is local; uninstall / clear data deletes it; per-item delete exists in-app. No account. | Fact. Play still requires a deletion mechanism statement: "data is stored only on device and deleted with the app". |
| Independent security review | none performed | Fact — answer "No". |

## B. Data types (Play taxonomy)

| Data type | Handled? | Collected (sent to dev)? | Shared? | Processed on device only | Notes |
|---|---|---|---|---|---|
| Personal info (name, e-mail, IDs) | No | No | No | — | no accounts |
| Financial | No | No | No | — | |
| Location | No | No | No | — | no permission |
| Web browsing history | **No storage.** Traffic passes through the tunnel in memory. | No | No (to developer) | yes | user's proxy sees it — see A |
| App activity — installed apps inventory | Listed on-screen for per-app routing (`QUERY_ALL_PACKAGES`) | No | No | yes | only ticked package names persisted, locally |
| App info & performance — crash logs | No crash reporting | No | No | — | |
| App info & performance — diagnostics | in-memory log buffer; shared only if the user taps Share | No | No | yes | |
| Device or other IDs | None read | No | No | — | User-Agent has app+core version only |
| Photos and videos | Camera frames / picked image analysed on-device for QR | No | No | yes | not stored |
| Files and docs | Config files the user picks for import | No | No | yes | contents parsed, profiles stored encrypted |
| Messages / contacts / calendar / audio / health | No | No | No | — | |
| **User-provided credentials (proxy secrets)** | stored encrypted locally; sent only to the user's own server | No | No (to developer) | yes | Play has no dedicated type; usually declared under "Other" or not at all. **Needs confirmation.** |

## C. Purposes (if a reviewer requires declaring the on-device processing)
App functionality only. No analytics, advertising, fraud prevention,
personalisation or account management purposes exist.

## D. Security practices to tick
- Data encrypted at rest: yes (AES-256-GCM, Keystore) for profiles, subscriptions, health.
- Data encrypted in transit: HTTPS for app-originated requests; tunnel = user's protocol (see A).
- Users can request deletion: local-only, delete in-app / uninstall.
- Follows Families policy: not a children's app (**confirm**).
- Committed to Play Families: no.

## E. Third-party SDKs present in the release artifact
| SDK | Sends data? | Evidence |
|---|---|---|
| Google ML Kit Barcode Scanning 17.3.0 (bundled model, `play-services-mlkit-barcode-scanning`, `mlkit:common`, `vision-common`, `play-services-basement`) | Bundled model works offline; the app sends nothing. Google's SDK index lists data-safety hints for ML Kit — **verify the current Play SDK Index entry for `com.google.mlkit:barcode-scanning` before submitting** and copy any collection it declares. | `app/build.gradle.kts`, `THIRD_PARTY_NOTICES.md` |
| sing-box / libbox | No developer telemetry; it is the proxy engine and talks only to user servers. Note: sing-box's clash/v2ray API and Tailscale endpoints are **not** enabled in the generated config (`core/singbox-config`). | `SingBoxConfigBuilder` |
| AndroidX, Compose, CameraX, WorkManager, Kotlin | none | |

## F. Items requiring human decision before submission
1. "Collected/shared" interpretation for traffic routed through user-chosen proxies (A).
2. Encryption-in-transit statement given unencrypted proxy protocols are importable (A).
3. ML Kit SDK Index declarations (E).
4. Target audience / Families (D).
5. Privacy policy URL — `docs/PRIVACY_POLICY.md` still has placeholders and must be hosted at a public URL.
