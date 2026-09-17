# DEVICE TEST PLAN — Universal Connection Client (debug build)

Build under test: **`app-singbox-debug.apk`** from CI artifact `app-debug`
(workflow "Android CI", branch `arena/01a0aa91-universal-connection-client`).
Application ID **`io.ucc.app.debug`** (debug suffix), versionName `0.1.0`,
versionCode 1, minSdk 24, targetSdk 36, core sing-box/libbox v1.13.21.

The debug variant differs from release only in: `applicationIdSuffix=".debug"`,
`isDebuggable=true`, no R8, and `libbox` `debug=true` (extra logcat lines, tag
`SingBoxCore`/`UcVpnService`). **No VPN, routing, DNS or storage code path
branches on the debug flag** (verified by grep: `BuildConfig.DEBUG` is only passed
to `AndroidCorePlatform.debug`).

## How to record results

For every step write one of: **PASS / FAIL / BLOCKED / N-A** + device model,
Android version, network type. On FAIL attach: Settings → Diagnostics → *Copy*
output, Logs → *Share* (Error filter first, then All), and `adb logcat -s
UcVpnService SingBoxCore AndroidRuntime`. Both exports are secret-free by design;
still glance over them before posting.

Useful adb:
```
adb install -r app-singbox-debug.apk
adb shell dumpsys package io.ucc.app.debug | grep -A20 "requested permissions"
adb shell dumpsys connectivity | grep -i vpn
adb shell am kill io.ucc.app.debug              # process death with tunnel up
adb shell dumpsys deviceidle force-idle         # Doze
adb shell cmd connectivity airplane-mode enable|disable
adb logcat -s UcVpnService SingBoxCore AndroidRuntime
```

Test configs: use **your own known-working servers** for each protocol. Public
"free" links are unreliable and invalidate FAIL results. For SOCKS/HTTP you can
run `ssh -D 1080` / `tinyproxy` on a LAN host. For WireGuard use a wg-quick
server whose `.conf` you convert to `wireguard://privateKey@host:port?publickey=…&address=…`.

---

## A. Installation

| # | Step | Expected | Physical only |
|---|---|---|---|
| A1 | `adb install -r app-singbox-debug.apk` | success; package `io.ucc.app.debug` | |
| A2 | `dumpsys package … requested permissions` | exactly: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, QUERY_ALL_PACKAGES, CAMERA (+ system-added ones like DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION). Nothing else. | |
| A3 | Launch | Home: status "Disconnected", "No server selected", Connect disabled, no crash | |
| A4 | System language fa → relaunch | UI Persian, layout mirrored (back arrow on right, text right-aligned) | |
| A5 | System dark/light; Settings → Appearance override | theme follows each choice | |
| A6 | Android 13+: notification permission | requested before first connect; deny → connection still works, but no persistent notification (record behaviour) | ✔ |

## B. Config import

For each protocol: Add → Paste link → **Preview** shows protocol/host/port/transport/TLS and **no credential** → Save → appears in Servers.

| # | Protocol | Also check | Physical only |
|---|---|---|---|
| B1 | VLESS (tcp+tls, ws, grpc, REALITY+vision) | preview shows "REALITY"; flow noted | |
| B2 | VMess (base64 JSON `vmess://eyJ…` and URI form) | alterId/security parsed | |
| B3 | Trojan | | |
| B4 | Shadowsocks (SIP002, `2022-blake3-*` and aes-gcm) | | |
| B5 | Hysteria (v1) | up/down Mbps shown | |
| B6 | Hysteria2 (obfs salamander, `mport`) | | |
| B7 | TUIC v5 | congestion control shown | |
| B8 | WireGuard `wireguard://` | preview shows **public key only** | |
| B9 | SOCKS5 with user/pass | | |
| B10 | HTTP CONNECT | | |
| B11 | Same link again | "duplicate" — not saved twice | |
| B12 | Multi-line paste: 3 valid + 1 garbage + 1 unsupported (`ssr://`) | 3 importable, 1 malformed, 1 unsupported, all listed with reasons; partial save works | |
| B13 | Clipboard button with link copied | same as B1 | |
| B14 | Clipboard empty | friendly "clipboard is empty" | |
| B15 | File: `.txt` of links; then base64-encoded file; then 3 MB file | first two import; third rejected "too large" | |
| B16 | QR: scan a link QR from another screen | preview → save. Deny camera → clear message, no crash. | ✔ |
| B17 | QR: scan a non-config QR (a URL) | "nothing recognised" | ✔ |
| B18 | sing-box JSON with 2 outbounds pasted | 2 profiles | |

## C. VPN (core path — run this before anything in D–J)

| # | Step | Expected | Physical only |
|---|---|---|---|
| C1 | Select a working VLESS/Trojan server → Connect | Android VPN consent dialog | ✔ |
| C2 | Deny consent | Home: error "VPN permission was not granted" + hint; Connect re-enabled | ✔ |
| C3 | Connect → Allow | Starting → Connecting → **Connected** in < 10 s; key icon in status bar; persistent notification with profile name | ✔ |
| C4 | Diagnostics | TUN: fd>0, mtu 9000 (or setting), addresses `172.19.0.1/30` (+v6), routes `0.0.0.0/0`, dns `172.19.0.2`; network: `wlan0`/`rmnet…` | ✔ |
| C5 | Browser → `https://ifconfig.me` | shows **server** IP | ✔ |
| C6 | Stream a video 3 min | Home ↑↓ rates move; Diagnostics totals grow; connections >0; no Reconnect events | ✔ |
| C7 | Disconnect | Stopping → Disconnected; notification gone; TUN "closed"; ifconfig.me shows real IP | ✔ |
| C8 | Connect again with the same profile | Connected; Diagnostics reconnect=0 | ✔ |
| C9 | Connected → Home → back to launcher → wait 5 min → reopen | still Connected; stats still updating | ✔ |
| C10 | Swipe app from recents while connected | tunnel stays up (service is foreground); reopening re-attaches ("Re-attached to running tunnel" in Logs) | ✔ |
| C11 | Notification tap | opens app | ✔ |
| C12 | Switch to a different server while connected | old tunnel stops, new one connects; only one VPN session in `dumpsys connectivity` | ✔ |
| C13 | Server with wrong password | Error class Authentication or Timeout (record which; sing-box often just times out) + hint | ✔ |
| C14 | Unsupported profile (e.g. imported with `Transport.Unsupported`) | Connect blocked/explains unsupported; no crash | |
| C15 | Each protocol B1–B10 | Connected + C5 passes. Record per protocol. UDP protocols (Hy/Hy2/TUIC/WG) also test a UDP app (voice call / `dig @1.1.1.1`). | ✔ |

## D. Network changes (stay Connected, watch Logs with Warning filter)

| # | Step | Expected | Physical only |
|---|---|---|---|
| D1 | Wi-Fi → disable Wi-Fi (mobile data on) | Logs: NETWORK "Default network changed"; state Reconnecting → Connected < 10 s; RECONNECT "Reconnected after 1 attempt(s)"; core **not** restarted (Diagnostics: same fd); browsing continues | ✔ |
| D2 | Mobile → enable Wi-Fi | same | ✔ |
| D3 | Both off 30 s → Wi-Fi on | NETWORK "lost; waiting" → Reconnecting(attempt 0) → Connected after network returns | ✔ |
| D4 | Airplane mode 60 s → off | as D3; no Error state unless > maxReconnectAttempts | ✔ |
| D5 | Weak Wi-Fi (walk away) | may show Reconnecting; must return to Connected or reach a clear Error after retries, never hang in Connecting | ✔ |
| D6 | After D1–D5 | Diagnostics counters match what you saw | ✔ |

## E. DNS

| # | Step | Expected | Physical only |
|---|---|---|---|
| E1 | Default (remote `https://1.1.1.1/dns-query`) connected → `https://dnsleaktest.com` extended | resolvers belong to Cloudflare *or* the proxy server's exit network. Record exactly what is shown. **Do not write "no leak" unless ISP resolvers are absent.** | ✔ |
| E2 | Settings → Remote DNS `tls://dns.quad9.net` → reconnect → E1 | Quad9 | ✔ |
| E3 | Remote DNS `192.168.1.1` | Settings refuses (LAN address); cannot save invalid state | |
| E4 | Direct DNS `udp://192.168.1.1`, LAN bypass on | `nslookup nas.local` (LAN name) resolves | ✔ |
| E5 | Android Private DNS = "automatic" vs "off" vs hostname | record dnsleaktest for each; with a Private-DNS hostname Android may bypass the tunnel resolver — record, don't fix | ✔ |
| E6 | IPv6 on, on an IPv6-capable network → `test-ipv6.com` | record score; site loads | ✔ |
| E7 | IPv6 off → `test-ipv6.com` | no v6; no long stalls on dual-stack sites | ✔ |
| E8 | `dig @8.8.8.8 example.com` from Termux while connected | answered (hijack-dns intercepts port 53) — check core log shows the query | ✔ |

## F. Routing (Settings → Routing; reconnect after each change)

| # | Step | Expected | Physical only |
|---|---|---|---|
| F1 | Rule `*.ir → Direct` | ifconfig.me = server IP, but `https://ip.sb`-style check on an .ir host / `curl -I https://www.aparat.com` succeeds and core log (Logs, All) shows `direct` for that domain | ✔ |
| F2 | Rule `keyword:ads → Block` | `https://ads.example.net` (or any host containing "ads") fails immediately; core log `reject` | ✔ |
| F3 | Rule `1.1.1.1 → Direct`, `curl https://1.1.1.1` | core log shows direct | ✔ |
| F4 | Rule `example.com → Proxy` placed above a `*.com → Direct` | example.com proxied, other .com direct (order = first match) | ✔ |
| F5 | LAN bypass on | `http://<router-ip>` reachable; off → unreachable or proxied (record) | ✔ |
| F6 | Disable a rule with the switch | behaves as if absent after reconnect | ✔ |
| F7 | Per-app "Only selected" = browser | browser = server IP; another app (e.g. Termux `curl ifconfig.me`) = real IP | ✔ |
| F8 | Per-app "All except selected" = Termux | reverse of F7 | ✔ |
| F9 | Per-app mode selected but zero apps | warning text shown; behaves as "All apps" | |
| F10 | Diagnostics after F7 | TUN apps included=1 | ✔ |

## G. Kill switch — behaviour, not the card

| # | Step | Expected | Physical only |
|---|---|---|---|
| G1 | Fresh install, Settings → VPN | card "not yet known" until first connect; after connect: **OFF** (red) | ✔ |
| G2 | Connected → Disconnect → browser | internet works via real IP (**expected leak**; app makes no kill-switch claim) | ✔ |
| G3 | Android Settings → VPN → this app → Always-on ON, Block connections without VPN ON → open app | card **ON** after the service (re)starts | ✔ |
| G4 | With G3: Disconnect in app | Android keeps blocking: browser has **no** internet; notification "Connected to VPN? / Blocked" from system | ✔ |
| G5 | With G3 connected: `adb shell am kill io.ucc.app.debug` | traffic blocked during the gap; service restarted by system; reconnects to last profile; browser works again | ✔ |
| G6 | With G3: Wi-Fi ↔ mobile | zero packets leak — verify with a continuous `ping 1.1.1.1` in Termux **excluded via per-app? no — keep all apps** and watch for replies during reconnect (should be none while tunnel is down) | ✔ |
| G7 | Always-on ON, Block OFF | card "always-on only"; G2 behaviour (leaks) | ✔ |
| G8 | Android 9 device (if available) | card "not available on this Android version" | ✔ |
| G9 | Strict route toggle OFF vs ON, connected | with ON: `ping` bound to Wi-Fi interface (`ping -I wlan0 1.1.1.1` in Termux) fails; with OFF it may succeed. Record. | ✔ |

## H. Storage

| # | Step | Expected | Physical only |
|---|---|---|---|
| H1 | `adb shell run-as io.ucc.app.debug ls files/` | `profiles.enc`, `subscriptions.enc`; **no** `.json` | |
| H2 | `run-as … xxd files/profiles.enc | head` | starts with `UCC1`, no readable host names/passwords | |
| H3 | Force-stop app → reopen | servers, favorites, selection, settings intact | |
| H4 | Reboot device → open app | same as H3 (Keystore key survives; no user-auth prompt) | ✔ |
| H5 | Change device lock from none → PIN → none, reopen | data still decrypts (key is not auth-bound) | ✔ |
| H6 | Migration: on a clean install, `run-as` write a plaintext `files/profiles.json` (JSON array of profiles; take format from a Phase-1 build or ask) → launch | profiles appear; `.json` deleted; `.enc` created | |
| H7 | Corrupt: `run-as … sh -c 'head -c 200 /dev/urandom > files/profiles.enc'` → launch | Home notice about unreadable store with file name `profiles.enc.corrupt-<ts>`; list empty; import works; notice dismissible | |
| H8 | Corrupt subscriptions.enc same way | same behaviour for subscriptions; profiles unaffected | |
| H9 | Airplane + import 200 profiles (paste large list) | UI responsive; save < 3 s | |

## I. Subscription

Set up your own endpoint (e.g. a static file behind nginx you control) so you can change the body.

| # | Step | Expected | Physical only |
|---|---|---|---|
| I1 | Add valid https URL (base64 body, `subscription-userinfo` header) | group created with name from `content-disposition`/host; expiry/traffic shown if header present | |
| I2 | `http://` URL | rejected: "Enter a valid https:// URL" | |
| I3 | URL returning 403 | error "server answered HTTP 403"; nothing created | |
| I4 | URL returning 200 empty body | "no servers in the response"; nothing created | |
| I5 | Rename one profile, favorite another; change upstream (edit 1 profile's port, add 1, remove 1) → Refresh | renamed name kept (`userRenamed`), favorite kept, port updated, new added, removed gone; snackbar "1 added, 1 updated, 1 removed" | |
| I6 | Upstream now returns 500 → Refresh | "update failed … Existing servers were kept"; group unchanged; `lastError` label shown | |
| I7 | Upstream removes the profile that is currently **connected** → Refresh | connected profile is **kept** (delete blocked) or clearly reported — record | ✔ |
| I8 | Auto-update on, interval 1 h; leave device overnight | `lastFetchedAt` advanced at least once (WorkManager ran); check with `adb shell dumpsys jobscheduler | grep io.ucc` | ✔ |
| I9 | Delete subscription | group + members gone; manual profiles untouched | |
| I10 | Subscription URL with `user:pass@` in it | works; URL never appears in Logs/Diagnostics | |

## J. Logs

| # | Step | Expected | Physical only |
|---|---|---|---|
| J1 | After C3 | Logs (All) show: Starting tunnel for `<PROTO host:port>`, "Core running; probing", "Probe ok (N ms)", "Connected" + core lines | |
| J2 | After D1 | NETWORK line (warning colour) + RECONNECT line | |
| J3 | After C13 | ERROR line "Failed: error_… — <redacted detail>" | |
| J4 | Filter Info / Warning / Error | each hides lower levels; Error shows only J3-type lines | |
| J5 | Clear | empty; Settings → Data count = 0 | |
| J6 | Copy (Error filter) → paste in Notes | ISO timestamps, level, source, category, message; only error lines | |
| J7 | Share (All) | share sheet; text identical to Copy | |
| J8 | **Leak check**: grep the shared text for: your password, UUID, private key, subscription URL, `user:pass@`, share links | **zero matches**. Also check `adb logcat` for the same strings while connecting with core log level **debug**. | ✔ |
| J9 | Core log level = debug, connect, browse | Logs fill quickly, UI stays responsive, buffer caps at 2000 | |

---

## Result template

```
Device: <model> / Android <ver> / build <security patch>
Network: <ISP> Wi-Fi + <carrier> LTE/5G
APK: app-singbox-debug.apk from run <id>, sha256 <…>

A1 PASS  A2 PASS …
C3 FAIL – stuck in Connecting; Diagnostics: …; logs attached
```
