# Smart Server Selection

Status: implemented (Feature Completion Pass), CI-verified, **not device-verified**.

## Architecture

```
app/ui/smart  SmartScreen, SmartViewModel, HealthLabels   ── strings, colours+labels
app/ui        HomeScreen "Smart" row + hero slot, ServersScreen health badges
app/data      SmartConnectionCoordinator   ── glue: observes ConnectionManager, issues connect()
              JsonServerHealthStore        ── encrypted persistence (health.enc)
core/smart    (pure Kotlin/JVM, no Android, no engine)
              ServerHealth / ConnectionTestResult / TestFailure   health model
              ServerHealthStore (+ InMemory impl)                  fingerprint-keyed records
              ConnectionTester / TcpConnectionTester               real probe
              HealthCheckRunner                                    bounded concurrency + recording
              ServerHealthEvaluator / HealthStatus                 thresholds → Healthy/Degraded/Offline/Unknown
              SmartServerSelector / Candidate / Selection          deterministic ranking
              SmartFailoverPolicy                                  bounded failover decisions
```

Dependency direction: `app → core:smart → core:engine-api → core:model`.
`core:smart` never touches sing-box; it is covered by `tools/check-core-boundary.sh`.
There is **one** state machine (`DefaultConnectionManager`); the coordinator only
observes `transitions` and calls `connect()/disconnect()` like the UI does.

## Health model (`ServerHealth`)

Per server (keyed by **profile fingerprint**, i.e. content identity):

| field | meaning |
|---|---|
| `latencyMs` | last successful TCP connect time |
| `rollingLatencyMs` | EMA, α = 0.3 (recent dominates, single outliers do not flip the ranking) |
| `latencySampleCount` | successes with a latency sample |
| `lastSuccessAtEpochMs`, `lastFailureAtEpochMs`, `lastFailure` | most recent evidence |
| `consecutiveFailures` | server-attributable failures since the last success |
| `successCount`, `failureCount` | sliding window capped at 20 so availability tracks the recent past |
| `lastNetworkTransport` | `wifi` / `cellular` / … at the last observation |
| `availability` (derived) | `success/(success+failure)`, `null` below 3 samples ("Not enough data") |

Nothing in a record identifies the server (no host, port, name, credential); the
file is still encrypted like profiles because the fingerprint set reveals *which*
servers exist.

**Not attributable to the server** — recorded for display, never counted against it:
`NETWORK_UNAVAILABLE`, `CANCELLED`, `UNSUPPORTED` (UDP-only protocols under a TCP test).

Evidence sources: (1) explicit tests, (2) the tunnel reaching `Connected`
(`TUNNEL_UP`, success without latency), (3) a terminal `ConnectionState.Error`
mapped through `SmartFailoverPolicy.toTestFailure` (reuses the existing
`ConnectionError` classification). Manual connections feed the same store.

## Connection test — real delay (`HttpDelayConnectionTester`, v1.0.2, default ON)

Settings → Servers → *Real delay test (HTTP)*. Measures a full HTTP round-trip
**through the server** — the same code path user traffic takes — so a wrong
password, a blocked protocol or a dead upstream all show as failures and the
number is an end-to-end latency, not a handshake time.

How: `CoreDelayProbe` (engine-api) is implemented by `SingBoxDelayProbe`
(engine-singbox). `HealthCheckRunner.testAll` opens **one** probe session for the
whole batch: `SingBoxConfigGenerator.generateProbe` builds a config with one
outbound per profile (tag = profile id; WireGuard skipped — endpoints are not
addressable via the Clash API), no TUN, no `cache_file`, `route.final = direct`,
`auto_detect_interface`, and `experimental.clash_api` bound to `127.0.0.1:<random
free port>` with a 32-hex random per-session secret. A second `libbox
CommandServer` hosts it (never `start()`ed — no unix socket), then
`GET /proxies/<id>/delay?url=&timeout=` with `Authorization: Bearer <secret>`
is issued per profile (200 → `{"delay":n}`; 504 → `TIMEOUT`; 503 → server
failure; 404/401 → internal error). The session is closed in `finally` under
`NonCancellable`. Runs while the tunnel is active too: outbound sockets are
protected via the platform interface (`protectOptional`), loopback is exempted
from the cleartext ban only for `127.0.0.1` (`network_security_config.xml`).

Errors are classified from `ConnectionError` (DNS/TLS/auth/refused/timeout) with
the usual `attributableToServer` rules. Profiles the probe cannot host fall back
to the TCP tester. Default URL `http://www.gstatic.com/generate_204` (5 s); the
URL is editable (must be `http(s)://`; invalid → default, never blocks connect).
Off → previous behaviour (TCP handshake only).

### Servers screen tools (v1.0.2)

- **Sort by ping** — `LATENCY_ORDER`: measured (session result beats stored EMA)
  → untested/unsupported → failed; name breaks ties. Persisted (`ServerListPrefs`).
- **Hide failed** — hides rows whose latest evidence is a server-attributable
  failed test this session, or a persisted `OFFLINE` status; the active and the
  selected server are never hidden; chip shows the hidden count.
- **Delete failed (n)** — appears when `failedIds` is non-empty; confirmation
  dialog; goes through `ServerRepository.delete`, so the active profile is still
  blocked and subscription re-import rules apply. Nothing is deleted
  automatically without the dialog.

## Connection test — TCP handshake (`TcpConnectionTester`, fallback / "Real delay test" OFF)

What is measured: DNS resolution + TCP handshake to `address:port`, timed, 4 s timeout.
Classified from JDK exceptions: `TIMEOUT`, `DNS_FAILURE`, `CONNECTION_REFUSED`,
`NETWORK_UNAVAILABLE`, `UNKNOWN`. `TLS_FAILURE`/`AUTH_FAILURE` cannot be produced at
this layer — they come only from real tunnel outcomes. It does not speak the proxy
protocol, so it cannot detect a wrong password; the UI text says so. It runs on
the default network (not through the tunnel) and does not touch the VPN.
Hysteria/Hysteria2/TUIC/WireGuard → `UNSUPPORTED` (no fake ping).

`HealthCheckRunner`: parallelism **4** (a 60-server subscription at 4 s timeout
finishes in ≤ ~60 s worst case, ~few seconds typically, without saturating a
mobile radio or tripping carrier connection-rate limits); one in-flight test per
profile (duplicates join); cancellation propagates and records `CANCELLED`
(non-punitive) via `NonCancellable`.

## Status thresholds (`ServerHealthEvaluator`)

| constant | value | why |
|---|---|---|
| `OFFLINE_AFTER_CONSECUTIVE_FAILURES` | 3 | one blip + one retry is not "offline"; a third failure is |
| `STALE_AFTER_MS` | 30 min | survives an app restart, but yesterday's data is not truth |
| `LATENCY_BAND_MS` | 50 | differences below mobile jitter should not beat stability |

Status: no/stale evidence → **Unknown**; different transport than now → **Unknown**
(history kept); streak ≥ 3 → **Offline**; streak > 0 with a past success →
**Degraded**; streak > 0 and never succeeded → **Offline**; otherwise **Healthy**.

## Selection policy (`SmartServerSelector`)

Deterministic comparator, in order:
1. unsupported (capability model) — excluded entirely;
2. status class Healthy > Degraded > Unknown > Offline;
3. rolling latency in 50 ms bands (Healthy/Degraded only);
4. fewer consecutive failures;
5. higher availability (`null` sorts last);
6. more recent success;
7. profile id (stable tie-break).

Outcomes: `Chosen` (with the full ranking), `NeedsMeasurement` (no fresh data →
test at most 12 servers, best history first, then choose), `AllUnhealthy`
("No healthy server found", manual choice), `NoCandidates`.

**Favourites** are not a ranking input: a favourite is the user's manual pick and
Smart is the alternative to a manual pick. Picking a server by hand on Home turns
Smart mode off; selecting the Smart row turns it on (persisted `smart_selection`).

UI never shows a numeric score or stars — only *Recommended*, latency, status and
the raw evidence on the Smart details screen.

## Failover (`SmartFailoverPolicy` + coordinator)

* Manual mode: unchanged — the manager's own `ReconnectPolicy` retries the same
  server, then reports `Error`. No switching.
* Smart mode: same first. Only when the manager reaches a terminal `Error` with a
  *server-related* cause (`ConnectionTimeout`, `DnsFailure`, `TlsFailure`,
  `AuthenticationFailure`, `CoreFailure`, `Unknown`) does the coordinator call
  `connect()` on the next non-offline candidate in the ranking, never revisiting
  a server tried in this session, at most **3** switches per user-initiated connect.
  `VpnPermissionDenied`, `VpnRevoked`, `NetworkUnavailable`, `InvalidConfiguration`
  never switch. A user disconnect ends the session. Backoff is the manager's own.

## Staleness and networks

Transport changes come from the existing `NetworkMonitor`. Evidence from another
transport is treated as Unknown (re-measure) but **never erased**.

## Subscriptions and deletes

Health is keyed by fingerprint, so a refresh that keeps the endpoint keeps the
history, a renamed profile keeps it, and duplicates share one record (no
double counting, no duplicate rows). When the profile set changes, records whose
fingerprint no longer exists are pruned (`SmartConnectionCoordinator` observes
`ProfileStore.profiles`; nothing in `SubscriptionRefresher`/`SubscriptionMerger`
changed). An empty profile list is *not* pruned (indistinguishable from
"store not yet loaded").

## Battery and security

On demand only: tests run when the user taps Test/Test all or on a cold Smart
connect; no background polling, no WorkManager job, no network calls except the
TCP handshakes. No new permissions. No secrets in health records or logs.

## Limitations

* TCP reach ≠ proxy works (wrong credentials/blocked protocol only show up as
  tunnel failures).
* UDP-only servers are rated only from real connections.
* Health is a local cache; a quarantined/unreadable `health.enc` starts empty.
* Not device-verified in this pass (no device/emulator available); see PROJECT_STATUS.
