# Google Play submission checklist — v1.0.0

Legend: ✅ technically done in the repo · ☐ human/store action · ⚠ policy-sensitive, depends on Play review.
Policy statements cite the official page they come from; where a decision depends on
Play's interpretation this is said explicitly. **Nothing here claims Play approval.**

## Identity & build
| item | state | detail |
|---|---|---|
| Package name | ✅ | `io.ucc.app` (debug builds use `io.ucc.app.debug`) |
| Version | ✅ | versionName `1.0.0`, versionCode `1` |
| Target / min SDK | ✅ | target 36, min 24 (`gradle/libs.versions.toml`) — meets Play's current target-API requirement |
| App bundle | ✅ | `:app:bundleSingboxRelease` produces an AAB; ABIs arm64-v8a, armeabi-v7a, x86_64 |
| Release signing | ☐ | Upload key + Play App Signing. No keystore in repo; see `docs/RELEASE.md`. Artifacts built without secrets are **unsigned** and cannot be uploaded. |
| Minified | ✅ | R8 + resource shrinking; mapping uploaded as CI artifact (upload to Play for de-obfuscation) |
| App icon | ✅ minimal | adaptive vector icon exists (`mipmap-anydpi-v26`). ☐ Store listing needs a 512×512 PNG and feature graphic (not in repo). |
| Screenshots | ☐ | not in repo; capture from a release build, en + fa |
| Category | ☐ | suggested: Tools (VPN clients are usually Tools/Communication) — publisher decision |
| Content rating | ☐ | IARC questionnaire; the app has no user-generated content, no ads |
| Ads declaration | ✅ fact / ☐ declare | contains no ads SDK → "No ads" |
| Account requirement | ✅ | none |

## Permissions & sensitive APIs
| item | state | detail / source |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | ✅ | normal permissions; core function |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE`, `foregroundServiceType="specialUse"`, `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="vpn"` | ✅ code / ⚠ declare | Android 14+ requires a type; VPN is not a dedicated type so `specialUse` with a manifest `<property>` use-case is used. Play requires declaring FGS types in Console → *Policy → App content* for apps targeting 14+ and reviews the specialUse justification ([developer.android.com/about/versions/14/changes/fgs-types-required](https://developer.android.com/about/versions/14/changes/fgs-types-required)). ☐ Fill the declaration: "VPN tunnel service; VpnService must run as long as the tunnel is up". |
| `POST_NOTIFICATIONS` | ✅ declared / ⚠ | Foreground-service notification on Android 13+. The app does **not** currently show a runtime request dialog; the FGS notification still appears in the shade as required by the system, but the user may see it collapsed if they deny. ☐ Product decision whether to add an explicit prompt (see Future Work). |
| `CAMERA` | ✅ | requested at runtime only when the QR scanner opens; `uses-feature required=false` so non-camera devices can install |
| `QUERY_ALL_PACKAGES` | ✅ used / ⚠ **declaration required** | Used by per-app routing (`InstalledApps.kt` → `getInstalledApplications`, results fed to `VpnService.Builder.addAllowed/DisallowedApplication`). Play permits broad visibility only when core functionality needs it and a targeted `<queries>` cannot work; a **Permissions Declaration Form** with written justification and a video is required, and Play may reject it ([support.google.com/googleplay/android-developer/answer/16558241](https://support.google.com/googleplay/android-developer/answer/16558241)). Fallback if rejected: replace with `<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>` (launcher apps only) — a functional reduction, not implemented in this pass. |
| `RECEIVE_BOOT_COMPLETED` | ✅ removed | no boot receiver exists; always-on VPN is handled by the system (`SUPPORTS_ALWAYS_ON`) |
| VpnService | ✅ code / ⚠ declare | Play's VpnService policy: allowed for apps whose core function is VPN; must not collect/redirect traffic for monetisation; needs prominent disclosure if traffic is collected (it is not). ☐ Complete the VPN declaration in Console → App content. Source: same policy page as above, section "VPN Service". |
| Clipboard | ✅ | read only on explicit user action |

## Privacy & data
| item | state |
|---|---|
| Privacy policy URL | ☐ — `docs/PRIVACY_POLICY.md` is a **draft with placeholders** (publisher identity, contact, date); must be finalised, legally reviewed and hosted at a public URL entered in Console |
| Data Safety form | ☐ — fill from `docs/DATA_SAFETY.md`; three interpretation questions listed there need a human answer |
| Families / target audience | ☐ — declare "not designed for children" (confirm) |
| Data deletion | ✅ fact — local-only data, deleted with the app; state this in the form |

## Open source / licences
| item | state |
|---|---|
| GPL-3.0-or-later app licence, `LICENSE` | ✅ |
| Third-party notices (`THIRD_PARTY_NOTICES.md`, in-app Settings → About → Open-source licences) | ✅ present; CI `tools/check-notices.sh` |
| **GPL distribution obligation** | ⚠ ☐ — distributing the APK/AAB (which links sing-box, GPL) obliges the distributor to offer the complete corresponding source, including the exact sing-box tag and build script. The app repo is **private** today → ☐ make it public or set up a written source offer before publishing. Legal review item (see `docs/CORE_LICENSE_AUDIT.md`). |
| sing-box naming clause ("no derivative work may use the name or imply association") | ⚠ ☐ — the app name and icon do not use "sing-box"; attribution text states non-affiliation. Legal review still recommended. |
| ML Kit terms | ⚠ ☐ — bundled model; usage bound by ML Kit ToS; confirm compatibility with GPL distribution (ML Kit is a proprietary binary linked into a GPL app — legal review). |

## Release process
| item | state |
|---|---|
| Internal testing track first | ☐ recommended |
| Pre-launch report | ☐ review after upload (VPN apps often show permission warnings in the report) |
| Release notes en/fa | ☐ |
| Crash de-obfuscation | ✅ mapping artifact `r8-mapping` per CI run — upload with each release |

## Known policy-sensitive blockers (cannot be resolved in the repo)
1. `QUERY_ALL_PACKAGES` declaration approval.
2. VPN + specialUse FGS declarations.
3. Public privacy-policy URL and finished Data Safety form.
4. GPL corresponding-source availability (repo currently private).
5. Signing key / Play App Signing enrolment.
