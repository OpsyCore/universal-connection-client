# Google Play release guide — Android v1.0.0

Actionable, section by section, in Play Console order. Facts come from the
source at commit `64563ba` (CI run 35463471779). "☐" = human action in the
Console or outside the repo; nothing here can be done from the repository.

## 1. App identity
| field | value |
|---|---|
| App name | Universal Connection Client (`@string/app_name`; fa localisation present) |
| Package / applicationId | `io.ucc.app` (immutable after first upload) |
| versionName / versionCode | `1.0.0` / `1` |
| minSdk / targetSdk / compileSdk | 24 / 36 / 36 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 (no 32-bit x86) |
| Default language | English; Persian (fa, RTL) fully localised |
| Source | `https://github.com/OpsyCore/universal-connection-client` (see §16 for visibility) |

## 2. Category
The app's functionality is a VPN/proxy client (VpnService, per-app routing,
server management). Google Play offers *Tools* and *Communication* as the
categories most VPN clients use. ☐ Publisher picks one; this document makes no
recommendation about which is better.

## 3. App access
All functionality is available without login or special access — there is no
account system. ☐ Select "All functionality is available without special
access". Note for reviewers (optional): the app does not ship any server; to
test a tunnel the reviewer needs their own proxy server. Consider providing a
test profile link owned by the publisher in the "Instructions" field (this is a
credential — put it only in the Console, never in the repo).

## 4. Ads
☐ Declare **No, my app does not contain ads**. Fact: no ads SDK; the
`com.google.android.gms.permission.AD_ID` permission is removed from the merged
manifest and CI fails if it reappears.

## 5. Target audience and content
☐ Age group: 18+ (or 13+ per publisher choice) — **not designed for children**.
☐ "Does your app appeal to children?" → No. No Families programme.

## 6. Data Safety
Fill from `docs/DATA_SAFETY.md`. Recommended answers: no data collected, no
data shared, no account, encryption at rest, deletion by uninstall/clear
storage, no independent review. Three interpretation questions are listed in
`DATA_SAFETY.md` §F — ☐ decide them before submitting.

## 7. Privacy policy
☐ Fill the three `[[…]]` fields in `docs/PRIVACY_POLICY.md` (publisher name,
contact e-mail, effective date). ☐ Host it at a public HTTPS URL (see
`docs/ANDROID_V1_RELEASE_CHECKLIST.md` §Hosting). ☐ Paste the URL in
*App content → Privacy policy* and in the store listing. The same URL should
be reachable from the app's About/Licences screen in a later version
(v1.0.0 links to the repository only).

## 8. VPN declaration (App content → VpnService)
Factual justification to paste:

> Universal Connection Client is a VPN/proxy client. Its core functionality is
> to route the device's traffic through proxy servers that the user configures
> themselves (VLESS, VMess, Trojan, Shadowsocks, Hysteria/Hysteria2, TUIC,
> WireGuard, SOCKS, HTTP). The app uses Android's VpnService (`UcVpnService`,
> foreground service type `specialUse`, subtype `vpn`, supports always-on) to
> create a TUN interface and hands the traffic to the on-device sing-box core.
> The developer operates no VPN servers, does not collect, log, inspect or
> monetise user traffic, does not inject ads or content, and has no analytics
> or advertising SDK. The VPN is only ever started by an explicit user action
> (Connect button, notification action, or the system's always-on setting);
> it is never started to serve the developer's purposes.

Policy points to tick: core functionality is VPN ✔; no traffic collection ✔
(prominent disclosure not required because no data is collected, but the
privacy policy still explains what the user's own server can see).

## 9. FOREGROUND_SERVICE_SPECIAL_USE declaration (App content → Foreground service permissions)
Manifest facts: `android:foregroundServiceType="specialUse"`,
`<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn"/>`,
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` declared in `core/vpn`.

Justification to paste:

> The foreground service is the Android VpnService that owns the TUN interface
> and the running proxy core. It must keep running for as long as the VPN
> tunnel is active, independent of any UI, otherwise the user's connectivity
> would drop. Android offers no dedicated foreground-service type for VPN
> clients, therefore `specialUse` with the manifest sub-type `vpn` is used, as
> Android's documentation prescribes for this case. The service shows the
> mandatory persistent notification with the connection state and a
> Disconnect action, starts only on explicit user action or system always-on,
> and stops when the user disconnects, when the system revokes the VPN, or on
> unrecoverable failure.

☐ Upload a short screen recording if the form asks (Connect → notification →
Disconnect).

## 10. QUERY_ALL_PACKAGES justification (App content → Permissions declaration)
Facts: declared in `core/vpn/src/main/AndroidManifest.xml`; used by
`app/ui/settings/InstalledApps.kt` to list installed apps that hold the INTERNET
permission for per-app routing; the chosen package names are passed to
`VpnService.Builder.addAllowedApplication/addDisallowedApplication`. The list
is displayed on-device only and never transmitted.

Justification to paste (Play's allowed use: "device security / VPN apps that
need to enumerate apps for per-app routing"):

> The app offers per-app VPN routing (include-only or exclude lists). To let
> the user choose, it must enumerate every installed application that can use
> the network and pass the selected package names to
> `VpnService.Builder.addAllowedApplication` / `addDisallowedApplication`.
> A `<queries>` filter cannot express "all apps that hold INTERNET", so broad
> package visibility is required. The inventory is shown on screen only; only
> the package names the user ticks are saved locally, and nothing is
> transmitted off the device.

☐ If the declaration is rejected, the fallback is removing per-app routing
(product decision; not done pre-emptively).

## 11. Permissions (merged release manifest, CI-verified)
| permission | source | why |
|---|---|---|
| INTERNET, ACCESS_NETWORK_STATE | core/vpn | proxy core, network monitor |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE | core/vpn | VpnService (Android 14+) |
| POST_NOTIFICATIONS | core/vpn | foreground-service notification (13+); not runtime-prompted |
| CAMERA (`hardware.camera.any` not required) | app | QR scan, runtime-requested on demand |
| QUERY_ALL_PACKAGES | core/vpn | per-app routing (§10) |
| RECEIVE_BOOT_COMPLETED, WAKE_LOCK | androidx.work (blame-attributed) | re-arm periodic subscription refresh after reboot; no boot auto-connect exists |
| `io.ucc.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | framework-internal signature permission |
Removed: `AD_ID`. No location, storage, contacts, phone, SMS.

## 12. Content rating (IARC)
☐ Complete the questionnaire: utility app; no user-generated content, no
social features, no ads, no gambling, no violence, no purchases. Mention that
it is a VPN tool (some questionnaires ask about "unrestricted internet
access" — answer truthfully: yes, it is a VPN client).

## 13. Store listing
☐ Title (≤30), short description (≤80), full description — en and fa. Describe
only implemented features: multi-protocol import (links, QR, files,
subscriptions), encrypted local storage, Smart selection/failover, per-app
routing, routing rules, DNS choice, logs; do **not** claim DNS-leak protection
or a system kill switch (the app's "strict route" is documented as
leak-protection *while connected* only) and do not claim any server network.
☐ 512×512 PNG icon (export from `mipmap-anydpi-v26` vector), 1024×500 feature
graphic, ≥2 phone screenshots per language (from a release build).
☐ Contact e-mail (Console requires one), ☐ privacy policy URL (§7).

## 14. Internal testing
☐ Upload the **production-signed** AAB (§17) to *Internal testing* first.
☐ Add testers, install from Play, run `docs/DEVICE_TEST_PLAN.md` sections A–J
on a physical device with the store-delivered build. ☐ Review the Pre-launch
report (VPN apps typically show a permission warning for QUERY_ALL_PACKAGES —
expected).

## 15. Production rollout
☐ Promote the same AAB from internal testing; staged rollout (e.g. 20 %) is
advisable for a first release. Release notes en + fa. Record the AAB SHA-256
and the Play App Signing certificate fingerprint in
`docs/ANDROID_V1_RELEASE_CHECKLIST.md`.

## 16. GPL / source compliance
The APK/AAB links sing-box (GPL-3.0-or-later) → the app is distributed under
GPL-3.0-or-later (LICENSE) and the distributor must make the complete
corresponding source available. See `docs/SOURCE_RELEASE.md`. ☐ Decision:
make the repository public at the release tag **or** publish a source archive
with a written offer. The repository is **private** today; changing visibility
is a repository-owner action (the CI integration has no permission for it).
Legal review is recommended for the ML Kit (proprietary binary) + GPL
combination and for the sing-box naming clause (`docs/CORE_LICENSE_AUDIT.md`).

## 17. Production signing
Current CI key: `CN=Ucc Test, …` (SHA-256 `629ef5f0…5f12b`) — **TEST ONLY;
must not sign the store build.** ☐ Generate the production upload key offline
(`docs/ANDROID_V1_RELEASE_CHECKLIST.md` §Signing has the exact command
sequence, incl. Termux), ☐ enrol in **Play App Signing** (Google holds the app
signing key; you upload with the upload key), ☐ replace the four GitHub
secrets `UCC_KEYSTORE_BASE64`, `UCC_KEYSTORE_PASSWORD`, `UCC_KEY_ALIAS`,
`UCC_KEY_PASSWORD`, ☐ re-run Android CI and confirm `PROVENANCE.txt` shows the
new certificate DN, ☐ only then download `universal-connection-client-1.0.0-release-signed.aab`
for upload. Keep the keystore and passwords offline in two places; losing the
upload key requires a key-reset request to Google.
