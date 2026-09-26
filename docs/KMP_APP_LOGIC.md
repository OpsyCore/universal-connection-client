# core/app-logic — shared application layer (Phase 3)

```
Android Compose UI (app/ui, ViewModels)          iOS UI (later)
            │                                          │
            └──────────────► core/app-logic ◄──────────┘
                                   │
               ┌───────────┬───────┴────────┬───────────┐
          core/config  core/smart   core/engine-api  core/model   (+ core/platform)
```

## What moved (git-mv, behaviour and package-private logic unchanged; package `io.ucc.app.data` → `io.ucc.applogic`)

| class | role |
|---|---|
| `ImportRepository`, `SubscriptionPlan`, `CommitResult` | add/import orchestration (detect→parse→plan→persist) |
| `ServerRepository` | rename/favourite/delete/export/grouping use-cases |
| `SubscriptionRefresher` | refresh + `SubscriptionMerger` orchestration, serialised, redacted errors |
| `SmartConnectionCoordinator` | Smart connect / evidence / failover glue over `ConnectionManager` (policy stays in `core/smart`) |
| `ConnectionSettings` (+ `Rule`, `LogLevel`, validation, `toStartOptions`) | settings/routing domain state |
| `LogBuffer`, `LogSanitizer` | in-memory log ring + last-line-of-defence redaction |
| `AppLanguage`, `LanguageStore` | language domain state |
| `ProfilePreview` | secret-safe presentation model |
| `ProfileStore`, `SubscriptionStore`, `SelectionStore`, `SettingsStore`, `InMemorySettingsStore` | **platform boundaries** (moved to `Stores.kt`) |

## Intentionally Android-only (app/data, app/ui, app/work, core/vpn)
`JsonProfileStore`, `JsonSubscriptionStore`, `JsonServerHealthStore` (AES-GCM files, Android Keystore), `Preferences`/`PrefsSettingsStore`
(SharedPreferences), `AppCompatLanguageStore`, `Notices` (mirrors Android dependency list), `SubscriptionRefreshWorker` (WorkManager),
all ViewModels/Compose/theme/QR/CameraX, `core/vpn`, `core/engine-singbox`.

## Platform-specific replacements inside the moved code (the only source edits)
* `System::currentTimeMillis` → `io.ucc.core.platform.currentTimeMillis` (default clock parameter; every call site still injects `now`).
* `e.javaClass.simpleName` → `e::class.simpleName` (same string on the JVM).
* `LogBuffer.iso()` — `SimpleDateFormat` → `isoUtc()` (pure arithmetic; `IsoTimeTest` pins vectors generated with the old formatter).

## Serialization
`ConnectionSettings` keeps its field names and `@Serializable` shape; it is stored as JSON under the same SharedPreferences key
(`connection_settings_v1`). `@SerialName`/class-discriminator behaviour is unchanged because kotlinx.serialization uses property
names, not the Kotlin package. ProGuard keeps `io.ucc.**$$serializer` for every package. Profile/subscription/health JSON is
produced by `core/config` / `core/smart` types that did not move.

## Tests
All `app/src/test/.../data/*` tests for the moved classes moved to `commonTest` with semantics/count preserved
(Base64/URLEncoder test helpers replaced by `core/platform` codecs; `java.io.IOException` in the fake by `StorageFailure`).
New: `IsoTimeTest`, `StoreContractTest` (atomic apply, error propagation, non-sticky write failure, cancellation).
Android keeps `EncryptedStoresTest`, crypto tests and ViewModel tests; view-model fakes live in `app/src/test/.../testing/Fakes.kt`.
