# Home screen layout contract

The Home screen (`app/.../ui/HomeScreen.kt`) is a single Material 3 `Scaffold`:

| Slot | Content | Inset handling |
|---|---|---|
| `topBar` | Compact header: app mark, app name, core name/version, **Settings** icon button (48 dp) | `WindowInsets.statusBars` |
| content | `LazyColumn` — hero card (status pill, power button, selected server, detail slot), download/upload tiles, server list (max 6, "All (n)" opens Servers) | Scaffold `padding` + 96 dp bottom `contentPadding` so the last row scrolls clear of the FAB |
| `bottomBar` | `NavigationBar` Home / Servers / Logs / Settings | `WindowInsets.navigationBars` (gesture or 3‑button bar) |
| `floatingActionButton` | Add (+) | Placed by Scaffold **above** the bottom bar; never overlaps a nav item |

Why this fixes the "Settings hidden behind +" bug: previously Settings was a plain
`TextButton` in the last row of the content column, right‑aligned, i.e. exactly at
the FAB's anchor; when Connected the status card grew and pushed that row under
the FAB. Now every navigation target lives in a Scaffold slot with its own inset
handling, so state changes cannot move them, and RTL only mirrors the whole layout
(FAB moves to the start, Settings icon to the start of the header) without any
overlap.

## State matrix (all rendered by the same fixed slots — no layout jumps)

| State | Pill | Ring | Button | Detail slot | Metrics |
|---|---|---|---|---|---|
| No server | Disconnected (grey) | dim | disabled | "Add or select a server" | — |
| Selected, disconnected | Disconnected | dim | Connect | "Tap the power button" | — |
| Starting / Connecting | Connecting… (teal, pulsing dot) | rotating arc | Disconnect | last event message | — |
| Connected | Connected (green) | full | Disconnect | "Connected for mm:ss" | live rate + totals |
| Stopping | Stopping… | rotating arc | disabled label | last event | — |
| Error | Error (red) | dim | Retry | redacted user message + hint + "View logs" | — |
| Reconnecting | Reconnecting (amber) | rotating arc | Disconnect | reason | last known rates |
| Multiple servers | list shows up to 6 rows; selected = teal border, active = green dot | | | | |

Latency is **not** shown on Home: `HomeUiState` has no measured latency and the
UI does not fabricate values.

## Theme

`ui/theme/Theme.kt` — `UccTheme(dark)`: charcoal `#0E1114` background, graphite
surfaces, single teal accent `#3CC8C2`, rounded shapes. Light variant kept.
Dynamic (Material You) colour was removed so the product looks identical on
every device; the Appearance setting (System/Light/Dark) is still honoured.
