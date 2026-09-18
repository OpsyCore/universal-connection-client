# Privacy Policy — Universal Connection Client

> **Status: DRAFT for legal review.** This text is derived from the application's
> source code at the v1.0.0 release candidate. Items in `[[double brackets]]` are
> placeholders that must be filled in by the publisher before the policy is
> published; the application does not know who publishes it.

**Publisher:** `[[LEGAL ENTITY OR INDIVIDUAL NAME]]`
**Contact:** `[[CONTACT E-MAIL]]`
**Effective date:** `[[DATE]]`
**Applies to:** Universal Connection Client for Android, package `io.ucc.app`, version 1.0.0

## 1. Summary

Universal Connection Client ("the app") is a VPN/proxy client. It connects your
device to proxy servers **that you configure yourself**. The app has no
account system, no analytics, no advertising SDK, no crash-reporting service
and no server operated by the publisher. Everything the app stores stays on
your device, encrypted. The only network connections the app makes on its own
are the ones you ask for: to your proxy servers, to the subscription URLs you
add, and to a connectivity probe URL after a tunnel comes up.

The table below uses four distinct terms:

| term | meaning |
|---|---|
| **processed locally** | handled in memory on your device to do what you asked |
| **stored locally** | written to the app's private storage on your device |
| **transmitted** | sent over the network to a party other than the app |
| **not collected** | the app never receives or records it |

## 2. Data the app handles

### 2.1 Server configurations ("profiles")
*What:* server address, port, protocol, transport/TLS settings and the
**credentials** needed to use that server (UUIDs, passwords, private keys),
plus a name, tags, favourite flag and timestamps. Imported from links, QR
codes, clipboard, files or subscription URLs that you provide.
*Processed locally:* yes. *Stored locally:* yes — `profiles.enc`, AES-256-GCM
with a key held in the Android Keystore; excluded from Android cloud backup
and device-to-device transfer. *Transmitted:* the address and credentials are
sent **to the server you configured** when you connect (that is what a proxy
client does). They are never sent anywhere else by the app. *Shared with the
publisher:* no.

### 2.2 Subscription URLs
*What:* the URLs you add. A subscription URL usually contains a secret token
and is treated as a credential.
*Stored locally:* yes — `subscriptions.enc`, encrypted as above, together with
the fetch time, quota headers returned by the server and a redacted last-error
label. *Transmitted:* the app fetches the URL over HTTPS **from the
subscription provider you chose**, on demand and, if you leave auto-update on,
periodically (WorkManager, default interval defined by the provider or in
Settings). The request carries a `User-Agent` of the form
`UniversalConnectionClient/1.0.0 (sing-box/v1.13.21)`; no device identifiers.
Plain `http://` subscription URLs are refused.

### 2.3 Server health data (Smart selection)
*What:* per server, keyed by a hash of its configuration: last measured TCP
latency, moving average, success/failure counters, timestamps of the last
success/failure, the failure category (e.g. timeout, DNS) and the network
transport type at the time (`wifi`/`cellular`/…). No addresses, names or
credentials are in these records.
*Stored locally:* yes — `health.enc`, encrypted. *Transmitted:* no. Health is
gathered only from connection tests you start (Test / Test all / Smart
connect) and from the outcome of tunnels you start. There is no background
polling.

### 2.4 Connection tests
When you run a test, the app opens a TCP connection to the configured
server's address and port over your current network (not through the tunnel)
and measures the handshake time. Nothing is sent inside that connection. The
server operator can observe the connection attempt like any other.

### 2.5 Network traffic while connected
When the VPN is on, traffic from your apps (or from the apps you selected in
per-app routing) is routed through the local TUN interface into the sing-box
core and on to the proxy server. **The proxy server operator can see your
traffic to the extent the protocol allows; the app publisher cannot**, because
the app never relays traffic through publisher infrastructure. DNS queries are
resolved according to your DNS settings (through the tunnel by default).
Traffic content is processed locally in memory and is not stored.

After a tunnel is established the app performs a connectivity probe (HTTP
request to the probe URL configured in `ReconnectPolicy`) through the tunnel to
confirm it works; the probe reveals to that endpoint that a client is online,
as any web request would.

### 2.6 Logs and diagnostics
*What:* an in-memory ring buffer (last 2 000 lines) of core and connection
events. Core log lines are redacted by the engine adapter before they reach
the buffer; connection events are written without secrets by design.
*Stored locally:* no persistent log file. Logs disappear when the process ends.
*Transmitted:* only if **you** tap Share/Copy in the Logs screen; the text then
goes to the app you choose. Diagnostics (Settings → Diagnostics) show live
state (TUN, network, capabilities) on screen only.

### 2.6a Core cache file
The sing-box core keeps a small unencrypted cache database
(`files/singbox/cache.db`) for its own state (e.g. fake-IP mappings, rule-set
cache). It contains no credentials or server configuration. It is private to
the app and deleted with it.

### 2.7 Settings and preferences
Routing rules, DNS choice, per-app routing lists (package names of apps you
selected), theme, language, selected server, Smart-mode flag. Stored locally in
SharedPreferences (not encrypted — none of these are secrets; the per-app list
is package names only) and excluded from backup.

### 2.8 Camera and images (QR import)
The camera preview is analysed on-device by Google ML Kit Barcode Scanning
(bundled model, no network). Frames are processed in memory and discarded;
**no image is stored or transmitted**. Gallery import decodes the image you
picked in the same way. The `CAMERA` permission is requested only when you
open the scanner and can be denied; gallery import works without it.

### 2.9 Installed apps (per-app routing)
To let you include/exclude apps from the VPN, the app lists installed apps
that hold the INTERNET permission (`QUERY_ALL_PACKAGES`). The list is shown on
screen; only the package names you tick are stored (see 2.7). The list is
never transmitted.

### 2.10 Clipboard
The app reads the clipboard **only** when you tap "Paste"/import from
clipboard. It never reads the clipboard in the background. Export/Share
writes to the clipboard or share sheet only on your explicit action; exported
links contain credentials.

## 3. Data the app does **not** collect
No account or e-mail, no advertising ID, no analytics or usage statistics, no
crash reports, no location, no contacts, no device identifiers sent anywhere,
no telemetry to the publisher. There is no publisher server.

## 4. Third-party components
| component | role | data it can see |
|---|---|---|
| sing-box / libbox v1.13.21 (GPL-3.0-or-later) | proxy/VPN core, runs entirely on-device | your traffic and server credentials, in memory, to forward them to **your** server |
| Google ML Kit Barcode Scanning 17.3.0 (bundled model) | QR decoding on-device | camera frames / picked image, in memory |
| AndroidX / Jetpack Compose / CameraX / WorkManager / Kotlin | UI, camera, scheduling | nothing beyond the above |

ML Kit's bundled barcode model does not require a network connection; the
library is subject to Google's ML Kit Terms of Service. `[[Publisher to
confirm with legal whether any ML Kit telemetry clause applies to the bundled
SKU; the app itself sends nothing.]]`

## 5. Where data goes over the network
| destination | when | what |
|---|---|---|
| your proxy server(s) | connect, connection test, failover | tunnel traffic, handshake with credentials |
| your subscription provider(s) | add / refresh | HTTPS GET of the URL you added |
| connectivity probe URL | after a tunnel is up, and during reconnect | one small HTTP request through the tunnel |

No other destinations exist in the code.

## 6. Retention and deletion
Everything is stored in the app's private storage and removed when you
uninstall the app or clear its data (Android Settings → Apps → Storage).
Inside the app you can delete individual servers and subscriptions (Servers
screen). Health records of deleted servers are pruned automatically. Logs are
not retained across restarts. `[[If a "reset all data" control is added later,
document it here.]]`

## 7. Security
Profiles, subscriptions and health are encrypted at rest (AES-256-GCM, key in
Android Keystore, not exportable). Backups and device transfer are disabled.
App-originated HTTP is HTTPS-only (network security config). The app never
logs secrets. See `docs/SECURITY.md`.

## 8. Children
The app is a networking tool and is not directed at children. `[[Publisher to
confirm target audience declaration.]]`

## 9. Changes
`[[Describe how users are informed of policy changes — e.g. in-app About screen
and the store listing.]]`

## 10. Open source
The app is licensed GPL-3.0-or-later; the source is at
`https://github.com/OpsyCore/universal-connection-client` `[[confirm public
URL before publishing — the repository is private at the time of writing]]`.
