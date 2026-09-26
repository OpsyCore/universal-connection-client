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

## Localization architecture

- All user-facing text lives in Android resources: `app/src/main/res/values/strings.xml` (en, default)
  and `values-fa/strings.xml`; the foreground-service notification strings live in
  `core/vpn/src/main/res/values{,-fa}/strings.xml`. Compose screens only use `stringResource` /
  `getString`; technical identifiers (protocol names, host:port, DNS specs, TUN/MTU, counters)
  are intentionally not translated and are rendered LTR (`TechnicalText`, Logs rows, Diagnostics values).
- Default behaviour: follow the Android system language. `resourceConfigurations = [en, fa]`.
- In-app selector: Settings → Appearance → Language (System default / English / فارسی), backed by
  `AppCompatLanguageStore` → `AppCompatDelegate.setApplicationLocales`. On API 33+ the OS persists
  the choice (also shown in system App languages); on API 24–32 AppCompat persists it
  (`AppLocalesMetadataHolderService` + `autoStoreLocales`). Changing it recreates the activity so
  layout direction flips immediately.
- Adding a language: `values-xx/strings.xml` (app + core:vpn), `<locale>` in
  `res/xml/locales_config.xml`, one entry in `AppLanguage`, tag in `resourceConfigurations`.
  No screen code changes.
- Tests: `AppLanguageTest` (tag parsing, store), `SettingsViewModelTest` (selection persisted).

## QR import from gallery
`QrScanScreen` offers "Choose from gallery" (top-bar icon + footer button) via the
Android Photo Picker (`PickVisualMedia`, no storage permission). `QrImageDecoder`
runs the same on-device ML Kit QR model as the camera path; `QrImageResult`
(unit-tested) turns the decoded values into one payload — several codes are joined
one-per-line — which is handed to the **same** `AddConfigViewModel.importQr` →
parse → validate → capability check → secret-free preview → confirm → atomic save
pipeline. No-QR and unreadable-image cases surface as snackbars; cancel returns to
the scanner. Nothing is auto-connected.


## v1.0.4 — visual and performance notes

- **Palette:** dark background is the brand navy `#081420` (identical to the launcher background); surfaces are tinted navy steps (`UccColors`). Window background (`ucc_window_dark`) matches so there is no flash before Compose draws. Light theme unchanged.
- **Glass cards:** `Modifier.glass()` (`ui/components/Glass.kt`) — translucent veil + top-left highlight + hairline border, all drawn in `drawBehind`. No `RenderEffect` blur: the background is flat, so blur would cost GPU time for nothing and exclude API < 31. Used on the Home hero card and the channel card.
- **Home button animations:** the spinner sweep, ring alpha and status-dot pulse are `State` objects read only inside draw lambdas (`Canvas` / `drawBehind`), so each animation frame invalidates the draw phase only; `PowerButton`, `StatusPill` and their text are not recomposed per frame. Frame rate itself is device-bound (Compose animates on the display's vsync); "60 FPS" is not something the app can assert — verify with the Android Studio layout-inspector recomposition counts / `adb shell dumpsys gfxinfo io.ucc.app`.
