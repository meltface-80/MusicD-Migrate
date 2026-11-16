# Mandarin Migration

An Android app that takes a **library file** — an export from
[Mandarin](https://github.com/meltface-80/Mandarin) or any other music
software — and adds its albums to your **Qobuz favourites**. It can also
**clear a Qobuz library out**: favourite tracks, favourite albums, followed
artists or playlists.

Everything runs on the phone. The app serves its own page from a loopback
HTTP server inside the same process and shows it in a WebView: no server,
nothing else running.

*(Formerly MusicD Migrate; the repository keeps its old name. Spotify was
removed in 0.5.0 and Roon and the Docker build in 0.4.0 — it is Qobuz only
until decided otherwise.)*

> **Upgrading from MusicD Migrate (0.3.x or earlier): uninstall it first.**
> From 0.4.0 the app is signed with a new key, and Android will not install an
> update whose signing key changed. Uninstall MusicD Migrate, install Mandarin
> Migration, and sign in to Qobuz again. From 0.4.0 on, updates install over
> the top.

---

## ⚠️ Read this before you install it

**Qobuz has no public API.** This app uses the same unofficial one that every
open-source Qobuz client and the Lyrion/LMS "Qobuz" plugin use. That is
**against Qobuz's terms of service**, it can stop working without notice, and
you use it at your own risk.

**It writes to your library.** An import only ever adds favourites and is safe
to run twice. A **clear-out removes, and cannot be undone**: Qobuz keeps no
copy. Both start as a **preview**, which is the default, and a clear-out will
not run until you have previewed it and typed "Qobuz".

---

## Importing a library file

1. **Export your library** as a CSV. Mandarin's export (when it has one) and
   most music software can do this. One row per track, with the album's
   barcode where the tags have it, gives by far the best results. The exact
   format is in **[docs/library-csv.md](docs/library-csv.md)**.
2. On the phone, open the **Import a library file** card and **Choose a
   file…**. The app reads it and shows what it found before anything is looked
   up: how many albums, how many with a barcode, how many with a track listing,
   which columns it used and which it ignored, and any warnings (rows it
   skipped, barcodes a spreadsheet mangled, library notes like "(12 tracks)"
   or "Disc 1" that it left out of album titles before searching).
3. **Preview.** Every album is looked for on Qobuz and nothing is written. The
   result is the real answer, not an estimate. **What was not found** lists
   every album that did not match, with the reason.
4. Press **Import N albums into Qobuz** in the preview's results. Matched
   albums are added to your Qobuz favourite albums; ones already there are
   skipped. Download the full report as a CSV if you want to work through the
   misses.

The file is held in memory until you import it or choose another. If Android
reclaims the app in between, choose it again.

### How an album is matched

This is the one thing in the app that can be wrong in a way you would not
notice: an import that quietly favourited the live album, a different edition
or a covers band's record **looks like it worked**. So the rule is: **where the
evidence is not decisive, it matches nothing and says why.**

1. **Barcode.** The album's UPC/EAN, searched for on Qobuz. The same code means
   the same release.
2. **Title, artist and track listing.** With no barcode, a title and an artist
   are not enough: "Greatest Hits" by almost anybody is several different
   records. The candidate must also contain at least **70% of the tracks you
   own** (by title, ignoring "- Remastered"-style suffixes). A deluxe edition
   containing all of your standard one is accepted, and the report says which
   edition you got.
   This applies to any album matched by name: one with no barcode, or one
   whose barcode Qobuz doesn't have. A remaster or deluxe edition with bonus
   tracks is judged the same way. And for a live album whose title matches
   exactly, a "(Live)" that Qobuz puts on *every* track is ignored, since your
   tags rarely carry it.
   If Qobuz only has a *shorter* edition (you own the deluxe), it is accepted
   when every one of its tracks is on yours, it has at least 5, and that is at
   least a third of your album. And a title that finds nothing at all is
   searched for once more without a trailing "(Original)" or catalogue number
   such as "(395 136-2)"; whatever that finds still has to pass the track
   listing.
3. **Nothing else.** An album with no barcode *and* no track listing in the
   file is not searched for at all, and the report says so. There is no "best
   guess" tier, because a best guess is indistinguishable from a real match once
   it is in your favourites.

Every refusal carries its reason, and the reasons differ on purpose. "The
closest that artist has is X" means you own a different edition. "Nothing
called that" means it is not on Qobuz.

**Only certain matches** (under *Options*) accepts barcode matches and nothing
else. **Check an album's track listing** is on by default. Turning it off lets
a barcode-less album match on title and artist alone, which is weaker evidence.

Lookups are cached, including the misses, so importing the same file again is
fast. **Forget cached lookups** clears that, for when Qobuz's catalogue has
changed.

---

## Clearing a library

The **Clear a library** card empties your Qobuz library — to start again, or to
clean up before importing into it. Tick any of:

* **Favourite tracks**
* **Favourite albums**
* **Followed artists**
* **Playlists** — the ones **you made are deleted**. Ones you merely
  **follow are left alone** unless you also tick *Also unfollow playlists you
  don't own*, and the report says which were kept and why.

Nothing is ticked to begin with, and it happens in three steps:

1. **Preview what would be removed.** This reads the library and lists every
   item that would go — with its id — and removes nothing.
2. **Download the preview.** The CSV is the only record of what was there once
   it is gone; nothing in this app can put it back.
3. **Type the service's name and press *Remove for good*.** The confirmation
   only appears for exactly the selection you previewed; change anything and
   you preview again. The name is checked by the app's server as well as by the
   page, so nothing else can trigger a removal.

Removals go in batches of fifty with a row
per item in the report: *removed*, or *failed* with the service's own reason.
A failed batch does not stop the rest, but if the service refuses the first
few batches outright, or the sign-in has expired, the run stops and says why
rather than painting your whole library red. Stopping part-way leaves
everything already removed removed; run it again to finish.

---

## Install

**Download: [`dist/mandarin-migration-0.5.1.apk`](dist/mandarin-migration-0.5.1.apk)**
— open it on the phone and Android will ask you to allow installing from that
source once. (That is the last build published before the rename; CI replaces
it with `mandarin-migration-<version>.apk` and rewrites this link when the new
version is built.)

— open it on the phone and Android will ask you to allow installing from that
source once.

`dist/latest.json` carries the version and the APK's SHA-256 if you want to
check the download.

The `apk` job in CI rebuilds, signs and republishes the file whenever
`versionName` is bumped, so updates install over the top.

It runs as a foreground service with an ongoing notification while an import
or a clear-out is working — and only then. A real library takes minutes, you
will lock your phone during it, and a plain activity's process is killable the
moment it leaves the screen.

### Building it yourself

```bash
cd android
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleRelease
```

Needs JDK 17 and the Android SDK (platform 36, build-tools 36). With no signing
key configured this produces `app-release-unsigned.apk`, which **will not
install** — see below. `./gradlew :app:assembleDebug` gives a debug-signed APK
you can try, but a release build cannot later install over it.

---

## Signing the APK

Android refuses to install an unsigned APK, so CI will not publish one: the
`apk` job builds and checks everything, warns, and skips only the publish until
a key is available.

**The key for this project is `mandarin-release.jks`**, made for 0.4.0
(`CN=Mandarin Migration`, alias `musicd`). Its fingerprint is pinned in
`tools/release-key.sha256`. Every later build has to use the **same** key or
Android will refuse to install it over the copy already on the phone — that is
the whole reason this is a stored secret rather than a key made per run.

(The key that signed MusicD Migrate up to 0.3.4 was not kept, which is why
0.4.0 needs the old app uninstalled once.)

To let CI do the publishing, add the keystore as two repository secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `MUSICD_KEYSTORE_BASE64` | the base64 blob from above |
| `MUSICD_KEYSTORE_PASSWORD` | the password you chose |

The alias is `musicd`, which the workflow passes as `MUSICD_KEY_ALIAS`.

To get the base64 blob from the keystore file:

```bash
base64 -w0 mandarin-release.jks   # macOS: base64 -i mandarin-release.jks
```

With those two secrets set, bumping `versionName` and `versionCode` in
`android/app/build.gradle.kts` is all it takes: CI builds, checks the signature
against the pinned fingerprint, writes the APK and `dist/latest.json`, and
rewrites the download link in this README.

To publish without a code change — for instance right after adding the
secrets — open **Actions → CI → Run workflow** on `main`.

### Starting over with a new key

Only if the keystore is lost. Make one with:

```bash
keytool -genkeypair -v \
  -keystore mandarin-release.jks \
  -alias musicd \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12 \
  -dname "CN=Mandarin Migration"
```

Then delete `tools/release-key.sha256` (or replace it with the new
fingerprint), and understand that **everyone with the old APK installed has to
uninstall before they can install the new one** — Android treats a changed
signing certificate as a different app.

**Keep the keystore somewhere safe and do not commit it.** If it is lost,
every future build is a different app as far as Android is concerned, and
everyone with the old one installed has to uninstall before they can update.

### The key is pinned

An APK signed with the *wrong* key installs fine and can then never be updated
— Android refuses an update whose certificate changed, and says nothing useful
about why. It stays invisible until somebody tries.

`tools/release-key.sha256` holds the expected fingerprint, so the `apk` job
fails if a build is ever signed with anything else. It was produced with:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk \
  | sed -n -E 's/^.*Signer.*certificate SHA-256 digest: *//p' | head -1 > tools/release-key.sha256
```

If the file is ever removed the key is simply not pinned, and the job says so in
a notice rather than failing — it is a guard, not a prerequisite.

---

## Signing in to Qobuz

Press **Sign in with Qobuz**. You sign in on qobuz.com in your browser and it
sends you back. No password is typed into this app and none passes through it.

There is an email-and-password fallback for when the redirect cannot get back
to the app. It is the fallback on purpose: the password is hashed before it is
sent, the way Qobuz's API wants it, and the plaintext is never stored.

---

## Who can reach it

**The app's server binds `127.0.0.1` and there is deliberately no switch to
widen it.** Behind that socket are your Qobuz sign-in and the ability to write
to — and empty — your library.

Android's automatic backup is **off**. The database holds an access token, and
copying it to cloud storage and restoring it onto a different device is not
something anyone asked for when they signed in on this phone.

---

## How it fits together

```
 ┌──────────────────────────────────────────┐
 │ MainActivity — a WebView (+ FilePicker)  │
 │   public/ — index.html, app.js,          │
 │   import.js, run.js, clear.js — as-is    │
 └────────────────────┬─────────────────────┘
                      │ /api/… over http://127.0.0.1
 ┌────────────────────▼─────────────────────┐
 │ MigrateService (foreground only per run) │
 │   HttpServer → MigrateApi                │
 │                                          │
 │ :core — the decisions, all unit-tested   │
 │   Csv  CsvLibrary   (the file → albums)  │
 │   Canon  Match  Tracklist  TitleTags     │
 │   Migration  Lookups  Writes  Clearance  │
 │   QobuzClient  QobuzOAuth                │
 └────────────────────┬─────────────────────┘
                      │
                SqliteStore (sign-in, match cache, job history)
```

Nothing that makes a decision lives in the Android module. Everything that
could be wrong is in `:core`, a plain Kotlin/JVM module tested without a phone,
a network or an account.

---

## Verification

```bash
npm run check        # all of the below, in order
```

```bash
for f in public/*.js; do node --check "$f"; done
npx eslint@9 --config tools/eslint.config.mjs public/*.js   # no-undef is the point
python3 tools/check-android-cleartext.py
python3 tools/check-file-length.py                          # no source file over 500 lines
node tools/make-icons.js && git diff --exit-code public/icons/
cd android && ./gradlew :core:test                          # 211 tests
```

`ContractTest` reads the page's scripts and fails when a route the page calls
is not served, or an option it sends is never read by the server. That covers
a clear-out's `dryRun` and `confirm`, for example.

### What is not tested

- **The file picker on a phone.** `FilePicker` hands the WebView a picked file,
  and nothing but the compiler stands behind it. The page and the server were
  driven end to end in a desktop browser, with a fake Qobuz behind the real
  route table: choose a file, read the summary, preview, import, report.
- **Against a real Qobuz account:** the import's searches and favourite
  writes, and the clear-out's removal endpoints. The tests pin exactly what is
  sent. Preview first, and try a clear-out on a playlist or two before
  emptying anything.

---

## Licence

Mandarin Migration is copyright (c) 2026 Lewis Menzies (Music Duck / MusicD) and is
released under the MIT License — the full text is in [LICENSE](LICENSE). In
short: do what you like with it, as long as the copyright notice and the licence
travel with it. It comes with no warranty.

The Qobuz sign-in in `android/core/…/QobuzOAuth.kt` is ported from
[MusicD Remote](https://github.com/meltface-80/MusicD-Remote), and
`android/core/…/http/HttpServer.kt` is adapted from
[MusicD Remote Lite](https://github.com/meltface-80/Android-Random-Remote) —
same copyright holder, same licence.
