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

`SubscriptionRefreshWorker` — WorkManager periodic job, unique name `subscription-refresh`, period **12 h**, constraint `NetworkType.CONNECTED`, `ExistingPeriodicWorkPolicy.KEEP`, enqueued from `AppGraph` at startup. Inside the job, `refreshAllDue(minInterval = 12 h)` picks subscriptions with `autoUpdate = true` whose last fetch is older than `max(12 h, profile-update-interval)`. The job returns `retry()` only when *every* due subscription failed with a fetch error. Logging is limited to counts. Android decides the exact run time (Doze, battery); there is no exact-time guarantee, and this is documented in the UI hint on the Add-configuration screen.

Manual refresh: Servers screen → group header → ↻. Auto-update per subscription can be toggled from the group menu.

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
