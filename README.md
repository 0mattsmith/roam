# Roam

A personal streaming music player for the car. Your library lives on Google
Drive; Roam catalogues it, streams it, and serves it to Android Auto — with
proper metadata and embedded album art.

Dark, teal-accented. Sideloaded, single user, self-updating from GitHub Releases.

- **Design document:** [`docs/SPEC.md`](docs/SPEC.md)
- **UI mockups:** [`docs/mockups.html`](docs/mockups.html)
- **Working notes:** [`CLAUDE.md`](CLAUDE.md)

---

## First-time setup

### 1. Gradle wrapper

The wrapper JAR is binary and is not in this scaffold. Generate it once:

```bash
gradle wrapper --gradle-version 8.9
```

Or just open the folder in Android Studio and accept the prompt.

### 2. Signing key + CI secrets

Releases are built and signed by GitHub Actions, so the key has to live in the
repository's secrets. One command does both:

```powershell
./setup-secrets.ps1 -Create      # generate the keystore, then upload
./setup-secrets.ps1              # upload an existing one
```

That writes four secrets — `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` — which `.github/workflows/release.yml` decodes at build time and
deletes afterwards.

**Back up `roam-release.jks` and its password somewhere offline.** If the key
changes, Android refuses to update an installed Roam; recovering means
uninstalling, which takes your database with it. `keystore.properties` and
`*.jks` are gitignored.

### 3. Google Cloud OAuth client

Roam needs the full `https://www.googleapis.com/auth/drive` scope. `drive.file`
only sees files the app itself created, so it cannot enumerate your existing
`Music/` tree or create folders inside it.

1. [Cloud Console](https://console.cloud.google.com) → new project
2. Enable the **Google Drive API**
3. OAuth consent screen → **External** → app name + support email
4. Add scope `https://www.googleapis.com/auth/drive`
5. **Set publishing status to "In production."** Do *not* submit for
   verification — Testing status expires refresh tokens after 7 days, and
   verification for a restricted scope means a paid annual CASA audit. You will
   see a "Google hasn't verified this app" screen once; Advanced → Continue.
6. Credentials → OAuth client ID → **Android**, with package `app.roam.player`
   and the SHA-1 of **both** your debug and release signing keys:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
keytool -list -v -keystore roam-release.jks -alias roam
```

Forgetting the release SHA-1 is the classic way to have sign-in work in debug
and fail in release.

### 4. Point the updater at your repo

Set `OWNER` in `update/src/main/java/app/roam/update/UpdateChecker.kt`.

```bash
gh repo create roam --private --source=. --remote=origin --push
```

A **public** repo is simpler — the updater then needs no personal access token,
and the APK is not a secret.

---

## Releasing

```powershell
./push.ps1 "Fixes album art on FLAC, adds shuffle to artist rows"
./push.ps1 -Bump minor "Network drive support"
./push.ps1 -Verify "Risky refactor"     # compile locally first
./push.ps1 -DryRun                      # show the plan, change nothing
```

`push.ps1` bumps `versionCode`/`versionName`, commits, creates an **annotated
tag whose message becomes the release notes**, pushes, then follows the Actions
run and prints the release URL. No local build, no local keystore, no Android
SDK required on the machine you release from.

GitHub Actions does the rest:

1. checks out the tag and reads `versionCode` from `app/build.gradle.kts`
2. decodes the keystore from secrets
3. `./gradlew assembleRelease` — ABI splits for arm64-v8a, armeabi-v7a, universal
4. renames to `roam-<version>-<abi>.apk` and writes a `.sha256` sidecar for each
5. reads the tag annotation for the notes and appends
   `<!-- roam:versionCode=N -->` for the updater to parse
6. publishes with **`--latest`** and **never** as a pre-release

That last point matters: the in-app updater polls the single
`/repos/:owner/:repo/releases/latest` endpoint, which excludes drafts and
pre-releases. Every build is Latest, so every build reaches every device.

If the build fails, the tag is already pushed — fix the code and re-run the same
tag without burning a version number:

```powershell
gh workflow run release.yml -f tag=v1.4.2
```

Cut a throwaway `v0.0.1` before writing any real code. Proving the pipeline
while the app is empty is much cheaper than discovering a signing problem at
v1.0 with a database you care about.

## Continuous integration

`.github/workflows/ci.yml` runs `assembleDebug` and `lint` on every push to
`main` and every pull request. It needs no secrets — debug builds use the
auto-generated debug key — so it is the fastest way to find out whether the
scaffold compiles on a clean machine.

---

## Testing the car surface

Android Studio → Tools → Android → **Run Desktop Head Unit**. This renders the
real Android Auto UI against your app on your desktop, and catches manifest and
browse-tree mistakes in seconds rather than in a car park.

---

## A note on the downloader

Bundling yt-dlp breaks YouTube's Terms of Service and cannot be distributed on
Google Play. Every app that does this is sideloaded or F-Droid only. For a
personal build on your own phone that is your call — it is flagged here so it is
not a surprise later.
