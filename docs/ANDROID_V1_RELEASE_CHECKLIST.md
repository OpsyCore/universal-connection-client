# Android v1.0.0 — release closure checklist

Code state: commit `64563ba`, CI run 35463471779 green (419 tests / 0 failed,
release APK+AAB built, iOS klibs compile). Everything below is what remains
**outside** the code. Tick items in this file as they are completed (commit the
tick with the evidence noted).

## Status board

- [ ] **Production upload key generated** (offline; §Signing below)
- [ ] **Play App Signing enrolled** (upload key registered; Google holds the app-signing key)
- [ ] **GitHub signing secrets replaced** — `UCC_KEYSTORE_BASE64`, `UCC_KEYSTORE_PASSWORD`, `UCC_KEY_ALIAS`, `UCC_KEY_PASSWORD` now hold the production upload key (current values = `CN=Ucc Test`, TEST ONLY)
- [ ] **Privacy policy hosted** at a public HTTPS URL (fields filled; §Hosting) — URL: `__________`
- [ ] **Data Safety submitted** (`docs/DATA_SAFETY.md`, §F decisions recorded)
- [ ] **VPN declaration submitted** (`docs/GOOGLE_PLAY_RELEASE.md` §8)
- [ ] **FGS specialUse declaration submitted** (§9)
- [ ] **QUERY_ALL_PACKAGES declaration submitted** (§10)
- [ ] **GPL source availability** — option A (public repo) or B (written offer) executed (`docs/SOURCE_RELEASE.md` §5)
- [ ] **Store listing** en + fa (title, descriptions, contact e-mail, category, content rating)
- [ ] **Screenshots** ≥ 2 per language from a release build
- [ ] **Feature graphic** 1024×500 + 512×512 icon
- [ ] **Internal test** track upload of the production-signed AAB, testers installed from Play
- [ ] **Physical device test** — `docs/DEVICE_TEST_PLAN.md` A–J on the store-delivered build, results recorded
- [ ] **Final AAB verification** — CI `PROVENANCE.txt` shows the production signer DN (NOT `CN=Ucc Test`), `apksigner verify` v2+v3 true, package/version/targetSdk/debuggable checked (`tools/inspect-release.sh` output in the run)
- [ ] **SHA-256 recorded** — AAB: `__________` APK: `__________` (from `SHA256SUMS.txt` of the final run; run id `__________`)
- [ ] **v1.0.0 tag** on the exact commit of the final run (only after all items above)
- [ ] **GitHub release** `v1.0.0` with AAB/APK SHA-256, `mapping.txt`, `libbox.aar` + hash, sing-box source tarball, notices

## Signing — generating the production upload key

Do this on a device you control, offline, never inside the repository. Choose
strong passwords and store keystore + passwords in two separate secure places.
The key below is an **upload key** for Play App Signing; if it is ever lost,
Google can reset it, but treat it as precious anyway.

### On a PC/Mac with a JDK
```bash
mkdir -p ~/ucc-release && cd ~/ucc-release
keytool -genkeypair -v -keystore ucc-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=<publisher name>, O=<publisher name>, C=<country code>"
# prompts for keystore password and key password (may be the same)
keytool -list -v -keystore ucc-upload.jks -alias upload | grep -E "Owner|SHA256"
```

### On Android with Termux (no PC needed)
```bash
pkg update && pkg install -y openjdk-17 github-cli
mkdir -p ~/ucc-release && cd ~/ucc-release
keytool -genkeypair -v -keystore ucc-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=<publisher name>, O=<publisher name>, C=<country code>"
keytool -list -v -keystore ucc-upload.jks -alias upload | grep -E "Owner|SHA256"
# back the file up NOW (e.g. termux-setup-storage; cp ucc-upload.jks ~/storage/shared/...) — then continue
```

### Installing the key into GitHub Actions (either environment; never paste values into chat or commits)
```bash
gh auth login                                   # browser flow; needs repo admin
R=OpsyCore/universal-connection-client
base64 -w0 ucc-upload.jks | gh secret set UCC_KEYSTORE_BASE64 -R $R
gh secret set UCC_KEYSTORE_PASSWORD -R $R       # prompts, input hidden
gh secret set UCC_KEY_ALIAS -R $R --body upload
gh secret set UCC_KEY_PASSWORD -R $R            # prompts, input hidden
gh workflow run "Android CI" -R $R --ref arena/01a0aa91-universal-connection-client   # or push a commit
```
(`base64 -w0` is GNU coreutils; on macOS use `base64 -i ucc-upload.jks | tr -d '\n'`.)

Then confirm in the run's `PROVENANCE.txt` / `apksigner-verify.txt` that the
signer DN is your publisher name, not `CN=Ucc Test`. In the Play Console,
*Setup → App signing*, register this certificate as the upload key (Play App
Signing). Why this is not automated: the CI integration used for this
repository has no permission to write Actions secrets (verified: HTTP 403 on
`gh secret list`), and generating a key anywhere but on the publisher's own
device would expose it.

## Hosting the privacy policy

Facts checked: the repository is private; GitHub Pages is not enabled and the
integration cannot enable it (HTTP 403 on the Pages API); no Netlify or other
hosting is referenced anywhere in the repo. Therefore hosting is a user action.
Free options, in order of least effort:

1. **GitHub Pages from this repository** (needs the repo to be public — which
   option A of `SOURCE_RELEASE.md` §5 requires anyway): Settings → Pages →
   Source "Deploy from a branch", branch `main`, folder `/docs`. The policy is
   then at `https://opsycore.github.io/universal-connection-client/PRIVACY_POLICY`
   (GitHub renders the Markdown). Fill the `[[…]]` fields first.
2. **A separate public repo** (`OpsyCore/ucc-privacy` with `index.md`) + Pages,
   if the code repo must stay private for now.
3. Any static host the publisher already pays nothing for.

Record the final URL in the status board and in `GOOGLE_PLAY_RELEASE.md` §7.

## Final-build acceptance criteria (must all hold on the *same* CI run)
| check | expected | where |
|---|---|---|
| package / versionName / versionCode | `io.ucc.app` / `1.0.0` / `1` | `release-inspection.txt` |
| targetSdk / minSdk | 36 / 24 | badging |
| debuggable | false (APK and AAB) | inspection |
| signature | `Verified using v2 … true`, `v3 … true` | `apksigner-verify.txt` |
| signer DN | publisher's upload certificate — **not** `CN=Ucc Test` | `PROVENANCE.txt` |
| permissions | exactly the documented set, `AD_ID` absent | inspection |
| R8 | `*ScreenKt` names in dex = 0; `mapping.txt` present | inspection / `r8-mapping` artifact |
| libbox pin | `libbox.sha256` match | "Verify libbox checksum pin" step |
| tests | all modules 0 failed | `test-summary.txt` |

Artifact retention note: the account currently cannot store Actions artifacts
(billing limit). The signed AAB therefore has to be produced where it can be
retrieved — either after the artifact quota is restored, or by running the same
Gradle command locally/in Termux-incompatible environments (needs JDK 17 +
Android SDK; not possible on a phone). If neither is available, the release is
blocked at "Final AAB verification".
