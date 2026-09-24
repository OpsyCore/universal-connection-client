# Google Play Data Safety — final checklist (v1.0.0)

> Derived from the Android implementation at commit `64563ba`. This is the
> answer sheet for the Play Console form; the person accountable for the listing
> enters and confirms it. Three questions (section F) depend on Google's
> interpretation, not on code, and are marked as such — they are not fabricated
> here.

## A. Overview answers

| Play question | Answer | Basis |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** (recommended answer — see F1). The app transmits data only to endpoints the user configured (their proxy server, their subscription provider), plus one connectivity probe and the DNS resolver the user selected. Nothing is transmitted to the developer or to a developer-chosen third party. | `docs/PRIVACY_POLICY.md` §5; grep of the source finds no other endpoints |
| Is all of the user data collected by your app encrypted in transit? | Not applicable if A1 = No. If Google requires an answer: app-originated HTTP is HTTPS-only (`network_security_config.xml`); tunnel traffic encryption depends on the user's protocol choice. | see F2 |
| Do you provide a way for users to request that their data is deleted? | Data exists only on the device; uninstall / "Clear storage" deletes everything; per-item deletion exists in-app. No developer-held data → nothing to request. | `docs/PRIVACY_POLICY.md` §6 |
| Independent security review | **No** | fact |
| App targets children / Families | **No** — not designed for children (publisher confirms) | see F4 |

## B. Data types — Play taxonomy

Legend: *Collected* = transmitted off device to developer or developer-chosen party (Google's definition). *On-device* = processed/stored only locally.

| Play category | Data type | Handled by app? | Collected | Shared | Purpose | Required / optional | Ephemeral | Notes |
|---|---|---|---|---|---|---|---|---|
| Personal info | Name, email, user IDs, address, phone, race, political, sexual orientation, other | No | No | No | — | — | — | no account system |
| Financial info | all | No | No | No | — | — | — | |
| Health & fitness | all | No | No | No | — | — | — | |
| Location | approximate / precise | No | No | No | — | — | — | no location permission |
| Messages | all | No | No | No | — | — | — | |
| Photos and videos | Photos | On-device only: camera frames / picked image decoded for QR, discarded | No | No | App functionality | optional (user action) | yes | never stored |
| Audio | all | No | No | No | — | — | — | |
| Files and docs | Files | On-device only: configuration files/text the user imports | No | No | App functionality | optional | no (parsed profiles stored encrypted) | |
| Calendar / Contacts | all | No | No | No | — | — | — | |
| App activity | App interactions, in-app search, other user-generated content | No | No | No | — | — | — | no analytics |
| App activity | **Installed apps** | On-device only: list of apps with INTERNET permission shown for per-app routing | No | No | App functionality | optional | list ephemeral; only ticked package names stored locally | `QUERY_ALL_PACKAGES` |
| Web browsing | Web browsing history | Traffic transits the tunnel in memory; **not stored, not collected** | No | No (see F1) | App functionality | — | yes | user's own proxy operator can observe traffic |
| App info & performance | Crash logs | No | No | No | — | — | — | no crash SDK |
| App info & performance | Diagnostics | On-device in-memory log buffer; leaves device only if the user taps Share | No | No | — | — | yes | |
| App info & performance | Other performance | No | No | No | — | — | — | |
| Device or other IDs | Device or other IDs | No (advertising ID permission removed; User-Agent = app + core version only) | No | No | — | — | — | |
| *(no Play type)* | Proxy server credentials entered by the user | Stored locally AES-256-GCM; transmitted only to the user's own server as protocol handshake | No | No (see F1) | App functionality | required for the app to work | no | Play has no category; declare nothing unless a reviewer asks (F1) |

## C. Purposes
App functionality only. There is no analytics, advertising/marketing, fraud
prevention, personalisation, account-management or developer-communications
purpose anywhere in the app.

## D. Security practices (form section)

| Item | Tick | Evidence |
|---|---|---|
| Data is encrypted in transit | Yes for all app-originated requests (HTTPS-only). Tunnel: protocol-dependent (F2). | `network_security_config.xml`, `HttpSubscriptionFetcher` rejects non-https |
| You can request that data be deleted | Local-only data; deletion by uninstall / clear storage / in-app delete | `PRIVACY_POLICY.md` §6 |
| Committed to Play Families Policy | No (not a children's app) | |
| Independent security review | No | |
| Data encrypted at rest (not a form item, but true) | profiles / subscriptions / health: AES-256-GCM, Android Keystore | `app/src/main/kotlin/io/ucc/app/data/crypto/` |

## E. Third-party SDKs in the release artifact

| SDK | Collects / shares? | Action |
|---|---|---|
| Google ML Kit Barcode Scanning 17.3.0 (`com.google.mlkit:barcode-scanning`, bundled model; brings `mlkit:common`, `vision-common`, `play-services-basement`) | The app sends nothing. The advertising-ID permission that play-services-basement can merge is stripped (`tools:node="remove"`, verified by CI). | **Check the Play SDK Index page for this artifact at submission time** and mirror any collection Google declares for it (F3). |
| sing-box / libbox v1.13.21 | No developer telemetry. Clash API / V2Ray API are not enabled in the generated config. Talks only to user-configured servers and the selected DNS resolver. | none |
| AndroidX, Jetpack Compose, CameraX, WorkManager, Kotlin, kotlinx | none | none |

Ads: **none** · Analytics: **none** · Tracking: **none** · Account data: **none**.

## F. Items that need a human decision (cannot be settled from code)

1. **Interpretation of "collected/shared"** for traffic that the user routes through their own proxy and for credentials sent to the user's own server. Recommended: "No data collected/shared" (the developer chooses neither the destination nor receives anything). A reviewer may read it differently; be prepared to explain per §5 of the privacy policy.
2. **Encryption in transit statement** given that plain `socks://`/`http://` proxies can be imported. Options: answer per app-originated traffic only (HTTPS), or add a note in the listing.
3. **ML Kit SDK Index** — copy whatever Google currently declares for `barcode-scanning` (may be "no data").
4. **Target audience / Families** — confirm "Not designed for children".
5. **Privacy policy URL** — host `docs/PRIVACY_POLICY.md` (after filling the three `[[…]]` fields) and paste the URL.
