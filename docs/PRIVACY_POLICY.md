# Privacy Policy — Universal Connection Client (Android)

> **Status: publication candidate, pending publisher details and legal review.**
> Every statement below is derived from the application source at the v1.0.0
> release candidate (commit `64563ba`). The three `[[…]]` items are the only
> values the repository cannot know; the publisher must fill them in before
> hosting this page. Nothing else in this document is a placeholder.

**Publisher:** `[[LEGAL ENTITY OR INDIVIDUAL NAME]]`
**Contact:** `[[CONTACT E-MAIL]]`
**Effective date:** `[[DATE OF PUBLICATION]]`
**Applies to:** Universal Connection Client for Android, package `io.ucc.app`, version 1.0.0 and later versions until this policy is superseded.

## 1. Summary

Universal Connection Client ("the app") is a VPN/proxy client. It routes your
device's network traffic to proxy servers **that you configure yourself**. The
publisher operates no servers for the app. The app has no user accounts, no
analytics, no advertising SDK, no crash-reporting service and no behavioural
tracking. Data the app stores stays on your device and is encrypted where it
contains secrets. The app connects to the network only for what you ask it to
do: to your proxy servers, to the subscription URLs you add, and to a small
connectivity check after a tunnel comes up.

Because the app is a network client it necessarily *processes* data (your
server credentials, your traffic while connected). This policy describes that
processing precisely rather than claiming that no data is handled.

Terms used below:

| term | meaning |
|---|---|
| **processed locally** | handled in memory on your device to perform what you asked |
| **stored locally** | written to the app's private storage on your device |
| **transmitted** | sent over the network to a party other than the app |
| **not collected** | the app never receives, records or transmits it |

## 2. Data the app handles

### 2.1 Server configurations ("profiles")
*What:* server address, port, protocol, transport/TLS settings and the
**credentials** needed to use that server (UUIDs, passwords, private keys),
plus a name, tags, favourite flag and timestamps. They enter the app only
through your actions: pasting a link, scanning a QR code, picking a file or
image, or adding a subscription URL.
*Processed locally:* yes. *Stored locally:* yes — file `profiles.enc`,
AES-256-GCM, key held in the Android Keystore (not exportable); excluded from
Android cloud backup and device-to-device transfer. *Transmitted:* the address
and credentials are used to connect **to the server you configured**; the app
never sends them anywhere else. *Received by the publisher:* no.

### 2.2 Subscription URLs
*What:* the URLs you add. A subscription URL usually embeds a secret token and
is treated as a credential.
*Stored locally:* yes — `subscriptions.enc`, encrypted as above, together with
the last fetch time, quota headers returned by the provider and a redacted
last-error label. *Transmitted:* the app fetches the URL **from the subscription
provider you chose** when you add or refresh it and, while auto-update is on,
periodically in the background (Android WorkManager, at most every 12 hours,
only when a network is available). Only `https://` URLs are accepted. The
request carries a `User-Agent` header of the form
`UniversalConnectionClient/1.0.0 (sing-box/v1.13.21)` and no device or user
identifier.

### 2.3 Server health data (Smart selection)
*What:* per server, keyed by a hash of its configuration: last measured
connection latency, a moving average, success/failure counters, timestamps of
the last success/failure, the failure category (e.g. timeout, DNS) and the
network transport type at the time (Wi-Fi/cellular/other). These records
contain no addresses, names or credentials.
*Stored locally:* yes — `health.enc`, encrypted. *Transmitted:* no. Health is
recorded only from connection tests you start and from the outcome of tunnels
you start; there is no background probing of servers.

### 2.4 Connection tests
When you run a test, the app opens a TCP connection to the configured server
address and port over your current network (outside the tunnel) and measures
the handshake time. Nothing is sent inside that connection. The server operator
can observe the attempt as they can any connection.

### 2.5 Network traffic while connected
While the VPN is on, traffic from your apps (or only from the apps you selected
in per-app routing) enters the local TUN interface, is handled by the sing-box
core inside the app and forwarded to your proxy server. **The proxy server
operator can see your traffic to the extent the chosen protocol allows; the
publisher cannot**, because no publisher infrastructure is involved. Whether
the traffic between your device and the proxy is encrypted depends on the
protocol of the profile you use (for example VLESS/Trojan/Shadowsocks/Hysteria/
TUIC/WireGuard encrypt; a plain `socks://` or `http://` proxy does not).
Traffic content is processed in memory only and is never stored.

DNS: by default, DNS queries of tunnelled apps are resolved through the tunnel
by the resolver configured in Settings (default `https://1.1.1.1/dns-query`,
DNS-over-HTTPS). You can change it. Direct (non-tunnelled) traffic uses the
resolver you set as "direct DNS" or the system resolver.

After a tunnel is established, and during automatic reconnection, the app sends
one small HTTP request through the tunnel to `https://www.gstatic.com/generate_204`
to confirm the tunnel carries traffic. That endpoint (operated by Google) can
see the request like any web request; no identifier is added to it.

### 2.6 Logs and diagnostics
*What:* an in-memory ring buffer of the last 2 000 core and connection events.
Core log lines are redacted (credential-like values are masked) before they
enter the buffer; connection events are written without secrets by design.
*Stored locally:* no persistent log file; the buffer is lost when the app
process ends. *Transmitted:* only if **you** tap Share or Copy in the Logs
screen, and then only to the app you choose. The Diagnostics screen shows live
state (tunnel, network, capabilities) on screen only.

### 2.7 Core cache file
The sing-box core keeps a small unencrypted cache database
(`files/singbox/cache.db`) for its own runtime state (for example rule-set
cache). It contains no credentials or server configuration, is private to the
app, is excluded from backup, and is deleted with the app.

### 2.8 Settings and preferences
Routing rules, DNS choice, per-app routing lists (package names you ticked),
theme, language, the selected server, the Smart-mode flag and the id of the
last active profile (used only to resume a tunnel if Android restarts the VPN
service after the process was killed). Stored locally in Android
SharedPreferences (unencrypted — none of these are secrets), excluded from
backup and device transfer.

### 2.9 Camera and images (QR import)
The camera preview is analysed on-device by Google ML Kit Barcode Scanning
(bundled model; no network connection is needed or made by the app for it).
Frames are processed in memory and discarded; **no image is stored or
transmitted**. Importing a QR image from your gallery decodes the picked image
the same way. The `CAMERA` permission is requested only when you open the
scanner and can be denied; gallery import works without it.

### 2.10 Installed apps (per-app routing)
To let you include or exclude apps from the VPN, the app lists installed apps
that hold the INTERNET permission (this requires the `QUERY_ALL_PACKAGES`
permission on Android 11+). The list is shown on screen; only the package names
you tick are stored (see 2.8). The list is never transmitted.

### 2.11 Clipboard
The app reads the clipboard **only** when you tap Paste/Import from clipboard,
never in the background. Export/Share writes to the clipboard or the share
sheet only on your explicit action; note that exported links contain your
server credentials.

### 2.12 Notifications
While the VPN is on, Android requires a persistent notification for the
foreground VPN service. It shows the connection state and a Disconnect action
and contains no personal data. The app does not itself prompt for the Android
13+ notification permission; if you deny notifications, the VPN still works and
the service remains visible in Android's active-apps list. The app uses
notifications for nothing else.

## 3. Data the app does not collect
No account, name or e-mail; no advertising identifier (the permission is
explicitly removed from the app); no analytics or usage statistics; no crash
reports; no location; no contacts; no device identifiers sent anywhere; no
telemetry to the publisher. The app contains no analytics, advertising,
attribution or crash-reporting SDK. There is no publisher server.

## 4. Third-party software inside the app

| component | role | data it can see |
|---|---|---|
| sing-box / libbox v1.13.21 (GPL-3.0-or-later, SagerNet) | proxy/VPN core, runs entirely on your device | your traffic and server credentials, in memory, in order to forward them to **your** server |
| Google ML Kit Barcode Scanning 17.3.0 (bundled model) | QR decoding on-device | camera frames / the picked image, in memory |
| AndroidX (Jetpack Compose, CameraX, WorkManager, AppCompat, DataStore/preferences), Kotlin, kotlinx libraries | UI, camera, scheduling, serialization | nothing beyond the above |

The sing-box features that would expose a local control API (Clash API,
V2Ray API) are not enabled in the configuration the app generates. ML Kit's
bundled barcode model works offline; the library is used under Google's ML Kit
Terms of Service. The app itself sends no data to Google through it. (Legal
review item: confirm against the current Google Play SDK Index entry for
`com.google.mlkit:barcode-scanning` whether Google declares any collection for
this SDK; if so it must be mirrored in the Play Data Safety form.)

## 5. Where data goes over the network

| destination | when | what |
|---|---|---|
| your proxy server(s) | connect, connection test, failover | tunnel traffic, protocol handshake with the credentials you configured |
| your subscription provider(s) | add / manual refresh / background refresh (default every 24 h, configurable, or off) | HTTPS GET of the URL you added, with the User-Agent described in 2.2 |
| `https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/super-sub.txt` (GitHub) — the pre-installed **"Public Free Servers"** subscription (v1.0.3) | once on first launch, then like any other subscription until you delete it or turn its auto-update off | HTTPS GET of the list; the servers in it are run by unknown third parties — traffic you send through them is visible to their operators. The app never connects to them on its own. |
| `https://www.gstatic.com/generate_204` | after a tunnel is up and during reconnect | one small HTTP request through the tunnel |
| your configured DNS resolver (default `https://1.1.1.1/dns-query`) | while connected | DNS queries of tunnelled apps |

No other network destinations exist in the app's code.

## 6. Retention and deletion
Everything the app stores lives in its private storage on your device. It is
removed when you uninstall the app or clear its data (Android Settings → Apps →
Universal Connection Client → Storage → Clear storage). Inside the app you can
delete individual servers and subscriptions in the Servers screen; health
records of deleted servers are pruned automatically. Logs are never retained
across restarts. The app has no "reset everything" button in v1.0.0; clearing
storage in Android Settings is the complete reset. Because the publisher holds
no data, there is nothing to request deletion of from the publisher.

## 7. Security
Profiles, subscriptions and health data are encrypted at rest with AES-256-GCM
using a key generated in and never exported from the Android Keystore. If an
encrypted file cannot be decrypted (for example after tampering), it is moved
aside and not silently overwritten. Cloud backup and device transfer are
disabled for all app data. App-originated HTTP requests are HTTPS-only. The app
does not log secrets. See `docs/SECURITY.md` in the source repository.

## 8. Children
The app is a networking utility and is not directed at children. The publisher
declares the target audience in the Google Play Console (see
`docs/GOOGLE_PLAY_RELEASE.md`); it is not designed for or marketed to children.

## 9. Changes to this policy
Changes are published at the URL where this policy is hosted, with an updated
effective date, and the version in the store listing is updated at the same
time. Material changes are also noted in the app's release notes.

## 10. Open source
The app is licensed under the GNU General Public License v3.0 or later. The
source code corresponding to each released version is made available as
described in `docs/SOURCE_RELEASE.md` (repository:
`https://github.com/OpsyCore/universal-connection-client`; the exact public
availability arrangement is confirmed by the publisher before publication).
The licences of all bundled third-party components are listed in the app
(Settings → Licences) and in `THIRD_PARTY_NOTICES.md`.
