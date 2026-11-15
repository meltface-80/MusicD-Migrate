# Mandarin Migration

An Android app that moves playlists, favourite albums, followed artists and
favourite tracks **between Qobuz and Spotify, in either direction** — and that
can **clear a library out**: remove every favourite track, favourite album,
followed artist or playlist from Qobuz or Spotify.

Everything runs on the phone. The app serves its own page from a loopback
HTTP server inside the same process and shows it in a WebView: no server, no
Docker, nothing else running.

*(Formerly MusicD Migrate; the repository keeps its old name.)*

> **Upgrading from MusicD Migrate: uninstall it first.** 0.4.0 is signed with
> a new key — the original one was not kept — and Android will not install an
> update whose signing key changed. Uninstall MusicD Migrate, install Mandarin
> Migration, and sign in to Qobuz and Spotify again. This happens once: every
> update after this one installs over the top.

---

## ⚠️ Read this before you install it

**Qobuz has no public API.** This app uses the same unofficial one that every
open-source Qobuz client and the Lyrion/LMS "Qobuz" plugin use. That is
**against Qobuz's terms of service**, it can stop working without notice, and
you use it at your own risk. The Spotify side is the official Web API.

**It writes to your library.** A migration only ever adds — it creates
playlists and favourites, never deletes — and it is safe to run twice. A
**clear-out removes, and cannot be undone**: neither service keeps a copy. Both
start as a **preview**, which is the default, and a clear-out will not run
until you have previewed it and typed the service's name.

---

## What moves

| | |
|---|---|
| **Playlists** | Created on the other service with the same name and the tracks in the same order. Pick which ones. |
| **Favourite albums** | Matched by barcode where both services have one, then by title, artist and track listing. |
| **Followed artists** | Matched on an exact name. |
| **Favourite tracks** | Matched by ISRC, then by title, artist and duration. |

Both directions. The two service clients implement one interface, so
"Qobuz → Spotify" and "Spotify → Qobuz" are the same migration with the source
and destination swapped.

### What does *not* move

* **Play counts, dates added, playlist artwork and playlist descriptions.**
  Neither API offers a way to set most of these.
* **Local files in a Spotify playlist.** They exist only on that machine.
  Reported as skipped, by name.
* **Podcast episodes.** Not music, and there is nothing to match them to.
* **Anything the other service does not have.** Reported, with the reason.

---

## Clearing a library

The **Clear a library** card empties one service — to start again, or to clean
up before migrating into it. Pick **Qobuz** or **Spotify**, then tick any of:

* **Favourite tracks** (Spotify: *Liked Songs*)
* **Favourite albums** (Spotify: saved albums)
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

Removals go in batches (fifty at a time; twenty for Spotify albums) with a row
per item in the report: *removed*, or *failed* with the service's own reason.
A failed batch does not stop the rest, but if the service refuses the first
few batches outright, or the sign-in has expired, the run stops and says why
rather than painting your whole library red. Stopping part-way leaves
everything already removed removed; run it again to finish.

---

## The part that matters: how it decides two tracks are the same

This is the only thing in the app that can be wrong in a way you would not
notice. A migration that fails is obvious and you can fix it. A migration that
quietly put the karaoke version, the radio edit or a covers band into your
playlist **looks like it worked**, and you find out months later.

So the rule is: **where the evidence is not decisive, it matches nothing and
tells you why.**

Three tiers, and they are not interchangeable:

1. **ISRC** — the recording's own identifier. The same code on both services
   means the same master of the same performance. This tier needs no
   corroboration, and it is why both clients ask for ISRCs on every read.
   Albums use their **barcode** the same way, and are searched by it: Spotify's
   album search does not return barcodes at all, so the barcode has to be the
   thing you search *for* rather than something you compare afterwards.
2. **Exact** — titles agree character for character once normalised, the
   artists overlap, *and* the durations agree within 5 seconds. Three
   independent facts.
3. **Close** — titles agree only after an *edition* suffix is removed
   (`- 2011 Remaster`, `(Deluxe Edition)`). Weaker evidence, so the duration
   gate tightens to 3 seconds.

Below that there is no "best guess" tier, because a best guess is
indistinguishable from a real match once it is in a playlist.

**A suffix that names a different recording is never stripped.** `(Live)`,
`(Acoustic)`, `(Radio Edit)`, `(Someone Remix)`, `- Extended Mix`, `(Demo)` —
those are different performances, and matching through them is exactly the
failure the tiers exist to prevent. `(Remastered)` is the same performance, so
that one is.

Five seconds rather than two, incidentally, because the two services encode
from different deliveries and trim silence differently: a 3–4 second gap for an
identical recording is ordinary. A radio edit is 60–90 seconds shorter and a
live take minutes longer, so the gate still catches what it is for.

There is a **Only certain matches** option that accepts ISRC matches and
nothing else. Fewer records move; every one that does is certainly the same
recording.

### The report is the output

The useful result of a migration is not "done" — it is the list of records that
did **not** come across, and what to do about each. Every refusal carries its
reason, and the reasons distinguish the cases that need different things from
you:

> *"Glory Box (Live at Roseland)" is 312s there and 298s here — more than 5s
> apart, so a different recording*
> — you own a different edition; findable by hand in a minute.

> *nothing called "Untitled Demo" by that artist on the other service*
> — it is not there; nothing will fix it.

> *"Teardrop" is there but credited to a different artist — likely a cover, so
> it was not taken*
> — deliberately refused.

Download the lot as a CSV and work through it.

### An album with no barcode

Qobuz and Spotify both hand over a barcode, which identifies a release
outright, and albums are searched *by* it. When one side has none, title and
artist are all there is — and they are not enough. "Greatest Hits" by almost
anybody is several different records. So the album's **own track listing**
carries the decision: a candidate must match on title and artist *and* share
at least 70% of the tracks you own, or it is refused and the report says how
many it did share. A deluxe edition is accepted for the standard one you own —
it contains all of it — and the report says which edition you got.

---

---

## Install

**Download: [`dist/musicd-migrate-0.3.4.apk`](dist/musicd-migrate-0.3.4.apk)**
— open it on the phone and Android will ask you to allow installing from that
source once. (That is the last build published before the rename; CI replaces
it with `mandarin-migration-<version>.apk` and rewrites this link when the new
version is built.)

`dist/latest.json` carries the version and the APK's SHA-256 if you want to
check the download.

The `apk` job in CI rebuilds and republishes the file whenever `versionName`
is bumped, signed with the same key, so updates install over the top. That
needs the keystore in the repository secrets — see
[Signing the APK](#signing-the-apk).

It runs as a foreground service with an ongoing notification while a
migration or a clear-out is working — and only then. That is not decoration:
a real library takes minutes, you will lock your phone during it, and a plain
activity's process is killable the moment it leaves the screen.

### Building it yourself

```bash
cd android
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleRelease
```

Needs JDK 17 and the Android SDK (platform 36, build-tools 36). With no signing
key configured this produces `app-release-unsigned.apk`, which **will not
install** — see below.

To get something you can put on a phone right now, build the debug variant
instead. It is auto-signed with the local debug keystore:

```bash
cd android
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Fine for trying it. Not fine for keeping: the debug key is per-machine, so a
later release-signed build cannot install over it and you would have to
uninstall first.

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
0.4.0 needs the old app uninstalled once. `dist/musicd-migrate-0.3.4.apk`,
while it is still there, is signed with that old key.)

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

## Signing in

### Qobuz — nothing to set up

Press **Sign in with Qobuz**. You sign in on qobuz.com in your browser and it
sends you back. No password is typed into this app and none passes through it.

There is an email-and-password fallback for when the redirect cannot get back
to the app. It is the fallback on purpose: the password is hashed before it is
sent, the way Qobuz's API wants it, and the plaintext is never stored — but the
redirect is still better, because this app never sees anything at all.

### Spotify — a Client ID is built in

Spotify will not issue tokens to an application it does not know, so sign-in
needs a **Client ID**. There is no client secret: sign-in uses PKCE, which
exists precisely for apps that cannot keep one.

> **⚠️ Spotify has frozen new app registrations.** Since early 2026 the
> **Create app** button on the developer dashboard has been greyed out —
> *"New integrations are currently on hold"* — and Spotify has given no
> timeline.

So this build **ships with one filled in**: a well-known id from the
open-source Spotify world. Know what that means — the consent screen names
*that* application rather than this one, and your requests count against its
quota. The [appendix](#appendix-the-client-id-this-build-signs-in-with) says
exactly whose it is and what it costs.

**If you have a Spotify app of your own**, from anything at all, prefer it —
only new registrations are frozen:

1. Open [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
   and pick the app.
2. **Settings → Edit → Redirect URIs**: add `http://127.0.0.1:3380/login` (the
   app shows it on its Spotify card). Save.
3. Paste the **Client ID** into the app and press **Save**. A saved id always
   wins over the built-in one.

The redirect path is `/login` rather than anything more descriptive because
the shared community ids whitelist exactly that one loopback path; anything
else gets `redirect_uri: Not matching configuration` before you can sign in.
On the phone the redirect itself is never a problem: the app's server *is* on
`127.0.0.1`, the one address Spotify accepts over plain http.

Sign-ins open in your **real browser**, never in the app's WebView — a WebView
does not share the browser's cookies, and typing a password into a window this
app controls is exactly what the redirect flow exists to avoid.

---

## Running a migration twice is fine

This is designed for it, because the second run is the common one — you buy
records and migrate again.

* **Favourites** are idempotent: both services' "save" endpoints are.
* **Playlists**: a second run finds the playlist it made last time and adds
  only what is missing. (Changeable: make another one, or leave it alone.)
* **A migration never deletes or unfollows anything.** Only a clear-out does,
  and only when asked as described above.

And it is fast the second time. The expensive part of a migration is not
writing, it is *looking up*: a 2,000-track library is up to 4,000 searches
against a rate-limited API, where the writes are about 40 requests. So every
lookup is cached — **including the misses** — and a re-run of the same
migration does almost no network work at all. **Forget cached lookups** in the
app clears it, for when a service's catalogue has changed and a previous "not
available" is worth retrying.

---

## Who can reach it

**The app's server binds `127.0.0.1` and there is deliberately no switch to
widen it.** Behind that socket are two services' access tokens and the ability
to write to — and now empty — your library. Nothing else on your network has
any reason to reach it.

Android's automatic backup is **off**. The database holds access tokens, and
copying those to cloud storage and restoring them onto a different device is
not something anyone asked for when they signed in on this phone.

---

## How it fits together

```
 ┌──────────────────────────────────────────┐
 │ MainActivity — a WebView                 │
 │   public/ (index.html, app.js, run.js,   │
 │            clear.js) — bundled as-is     │
 └────────────────────┬─────────────────────┘
                      │ /api/… over http://127.0.0.1
 ┌────────────────────▼─────────────────────┐
 │ MigrateService (foreground only per run) │
 │   HttpServer → MigrateApi                │
 │                                          │
 │ :core — the decisions, all unit-tested   │
 │   Canon  Match  Tracklist  TitleTags     │
 │   Migration  Lookups  Writes  Clearance  │
 │   SpotifyClient  QobuzClient             │
 └────────────────────┬─────────────────────┘
                      │
                SqliteStore (sign-ins, match cache, job history)
```

Nothing that makes a decision lives in the Android module: it is a WebView, a
service and SQLite. Everything that could be wrong is in `:core`, a plain
Kotlin/JVM module tested without a phone, a network or an account.

---

## Verification

```bash
npm run check        # all of the below, in order
```

which is:

```bash
for f in public/*.js; do node --check "$f"; done
npx eslint@9 --config tools/eslint.config.mjs public/*.js   # no-undef is the point
python3 tools/check-android-cleartext.py
python3 tools/check-file-length.py                          # no source file over 500 lines
node tools/make-icons.js && git diff --exit-code public/icons/
cd android && ./gradlew :core:test                          # 207 tests
```

No suite needs a Qobuz or Spotify account: the service clients are driven by a
fake HTTP layer and the migration and clear-out engines by fake services
implementing the same interface.

`ContractTest` reads the page's scripts and fails when a route the page calls
is not served, or an option the page sends — including a clear-out's
`dryRun` and `confirm` — is never read by the server. It also fails if
`optString` appears outside `Json.kt`: Android's `org.json` returns the
**literal string `"null"`** for a JSON null, and every JVM test is blind to it.

### What is not tested

**The removal endpoints have not been run against a real account.** Spotify's
are its documented `DELETE` calls; Qobuz's `favorite/delete` is the call the
owner's Mandarin server uses for albums, and `playlist/delete` /
`playlist/unsubscribe` are from Qobuz's own (old) API documentation. The tests
pin exactly what is sent; whether each service still accepts it is something
the first real preview-then-remove will show — try it on a playlist or two
first.

`MainActivity` and `MigrateService` — the WebView and the foreground service —
have nothing but the compiler behind them. There are no instrumentation tests.
Everything underneath them, including the HTTP server and the whole route
table, is exercised on a JVM over a real socket.

---

## Appendix: the Client ID this build signs in with

This is **baked in**, because Spotify has frozen new registrations and a setup
step nobody can complete is an unusable app. It is the owner's decision for the
owner's own install, taken with all of the below understood.

```
d420a117a32841c2b3474932e49fb54b
```

`Pkce.DEFAULT_CLIENT_ID` in `android/core/…/Pkce.kt`; `ContractTest` fails if
it and this appendix ever disagree, because this is where you look when a
sign-in stops working.

What it is, plainly:

* **It is not registered to Mandarin Migration.** It comes from the open-source
  Spotify world, which has long shared a well-known id among its users because
  Spotify will not issue new ones. It works here for exactly one reason: the
  loopback path `/login` is whitelisted on it, which is why this app's redirect
  path is `/login` and not `/api/spotify/callback`.
* **The consent screen will name that application, not this one**, and the
  requests count against its quota. If it is ever rate-limited or withdrawn,
  the Spotify half of this app stops working and there is nothing to be done
  about it from here. That is the trade for being able to sign in at all.
* **It is a fallback, never an override.** A saved id always wins: paste your
  own into the **Spotify Client ID** field and this one is never used again.
  It is resolved in one place — saved, else built-in — because a fallback
  applied at three sites out of four is a sign-in that begins as one
  application and ends as another.
* **It is not a secret.** Sign-in uses PKCE, there is no client secret, and a
  client id travels in the query string of the authorise URL in plain sight
  every time anyone signs in to anything. Writing it down changes nothing about
  who can see it.

**If you fork this**, put your own id in that constant, or clear it to go
back to asking the user — the page still has the field, the Save
button and the instructions for registering one.

---

## Licence

Mandarin Migration is copyright (c) 2026 Lewis Menzies (Music Duck / MusicD) and is
released under the MIT License — the full text is in [LICENSE](LICENSE). In
short: do what you like with it, as long as the copyright notice and the licence
travel with it. It comes with no warranty.

The Qobuz sign-in in `android/core/…/Pkce.kt` is ported from
[MusicD Remote](https://github.com/meltface-80/MusicD-Remote), and
`android/core/…/http/HttpServer.kt` is adapted from
[MusicD Remote Lite](https://github.com/meltface-80/Android-Random-Remote) —
same copyright holder, same licence.
