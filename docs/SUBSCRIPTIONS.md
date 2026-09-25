# Subscriptions & Server Management (Phase 3)

## Data model

| Piece | Where | Notes |
|---|---|---|
| `Subscription` record | `core:config` `subscription/Subscription.kt`, persisted by `JsonSubscriptionStore` | `id = sha256(normalised url)[0:24]`, `name`, `lastFetchedAt`, `lastInfo` (`subscription-userinfo`), `autoUpdate`, `updateIntervalHours` (`profile-update-interval`), `lastError` (redacted label) |
| Membership | `ConnectionProfile.metadata.groupId == subscription.id`, `metadata.source = Subscription(id)` | Grouping in the Servers screen is derived from this; nothing else is stored |
| User edits that must survive refresh | `metadata.userRenamed`, `metadata.favorite`, `routing`, `dns`, `lastUsedAt` | Set by `ServerRepository.rename/setFavorite` |

## Merge rules (`SubscriptionMerger`, pure, unit-tested)

Identity is the content **fingerprint** (never the name). For one refresh of subscription *S* with fetched list *F*:

| Case | Action |
|---|---|
| fingerprint in *F* **and** already in group *S* | keep the stored row: same `id`, `createdAt`, favorite, routing/DNS overrides, `lastUsedAt`. Name follows upstream **unless** `userRenamed`. Tags / `rawSource` follow upstream. No write if nothing changed. |
| fingerprint in *F*, not in *S*, but present elsewhere (manual or other subscription) | **skipped** — a server is never stored twice |
| fingerprint in *F*, unknown | inserted, `source = Subscription(S)`, `groupId = S` |
| in *S* but not in *F* | **deleted**, except favorites and the currently active profile (pinned), which are kept and counted as `keptFavorites` |
| duplicates inside *F* | counted once |

Safety rails:

- A body that parses to **zero** profiles is treated as an error (`Outcome.EmptyBody`); the group is never wiped by a broken/expired endpoint.
- Any fetch error leaves the profile store untouched; only `lastError` on the record changes (`http:503`, `network`, `too_large`, `invalid_url` — never the URL or body).
- Upserts and deletes of one refresh are applied in **one atomic store write** (`ProfileStore.apply`).
- Refreshes are serialised by a mutex (manual + background never interleave).
- `SubscriptionRefresher` never connects or disconnects.

## Scheduling

`SubscriptionRefreshWorker` — WorkManager periodic job, unique name `subscription-refresh`, constraint `NetworkType.CONNECTED`. **v1.0.2:** the period follows Settings → Servers → *Auto-update subscriptions* (`ConnectionSettings.subscriptionUpdateIntervalHours`, choices Off/6/12/24 h, default **24 h**). `AppGraph` observes the setting and calls `schedule(context, hours)`: `ExistingPeriodicWorkPolicy.UPDATE` (period changes keep the next-run time; no immediate refresh on toggle) or `cancelUniqueWork` when 0. Inside the job, `refreshAllDue(minInterval = <hours>)` picks subscriptions with `autoUpdate = true` whose last fetch is older than `max(interval, profile-update-interval)`; when the setting is 0 the job (if still scheduled) returns success without work.

**Refresh on open (v1.0.2, default OFF):** `subscriptionUpdateOnOpen` — a `ProcessLifecycleOwner` observer in `AppGraph` runs `refreshAllDue(minInterval = 15 min)` on every `onStart` (app to foreground). The 15-minute floor (`ConnectionSettings.ON_OPEN_MIN_INTERVAL_MS`) is applied per subscription against `lastFetchAt`, so rapid app switching does not hammer providers; the provider's `profile-update-interval` still wins if longer. Per-subscription `autoUpdate = false` excludes it from both paths. The job returns `retry()` only when *every* due subscription failed with a fetch error. Logging is limited to counts. Android decides the exact run time (Doze, battery); there is no exact-time guarantee, and this is documented in the UI hint on the Add-configuration screen.

Manual refresh: Servers screen → group header → ↻. Auto-update per subscription can be toggled from the group menu.

## Default free subscription (v1.0.3)

`DefaultSubscription` (app-logic, unit-tested) + `AppGraph`: when `Preferences.freeSubscriptionSeededVersion < SEED_VERSION` (2) and no record with the same id exists, the app inserts the publisher-chosen list (`DefaultSubscription.URL` = MatinGhanbari/v2ray-configs `super-sub.txt`, name `free_subscription_name` fa/en) and immediately runs `SubscriptionRefresher.refresh`. Upgrade: the v1 list (mahdibland aggregator) and the dead v2 URL (yebekhe/TV2Ray, 404) and their servers are deleted via `ServerRepository.deleteSubscription` (active profile never deleted). The id is `Subscription.idFor(URL)`, identical to a manual add. It is an ordinary subscription afterwards; deleting it sticks for that seed version.

**Curation (this subscription only — user subscriptions are never filtered):** `SubscriptionRefresher(curate=…)` applies `DefaultSubscription.curate` to the parsed list before merging: VLESS/VMess only (Trojan, Shadowsocks, SOCKS, HTTP, Hysteria, TUIC, WireGuard dropped), unsupported transports dropped, one entry per address:port, ranked REALITY → TLS+WS/gRPC/H2 → TLS → modern transport → plain, capped at `MAX_SERVERS` = 25. "Working" is not knowable at import time; the real delay test / Smart selection decide afterwards. Disclosure: `docs/PRIVACY_POLICY.md` §5.

## Servers screen

- Grouped list: *Manually added* first, then one group per subscription (record subtitle: last update, usage/expiry, last error, auto-update off). Profiles whose subscription record vanished are shown under *Manually added* rather than hidden.
- Search over name / host / protocol / tags; favorites filter; favorites sort first, then name.
- Tap = select for Home; long-press = multi-select (share, delete, select all).
- Per-item menu: rename (sets `userRenamed`), favorite, share link, delete.
- Delete asks for confirmation. The **active** profile (any non-Disconnected state) is refused with a snackbar — disconnect first; deleting a subscription is refused entirely while one of its members is active.
- Share = `ShareLinkExporter` output → `ACTION_SEND` chooser. The text contains credentials, so it is an effect consumed once, never part of the state object and never logged.

## Not implemented (yet)

- Custom user-defined groups/folders (only subscription groups + ungrouped).
- Per-profile field editor (server/port/UUID…): rename only. Editing anything that changes the fingerprint would break dedupe/merge semantics and is deferred to Phase 4 with a proper "detach from subscription" flow.
- Moving a profile between subscriptions.
- Import/export of the whole database file (Phase 4 with encrypted storage).
