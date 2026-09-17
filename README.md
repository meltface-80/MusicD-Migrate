# MusicD Migrate

Move playlists, favourite albums, followed artists and favourite tracks
**between Qobuz and Spotify, in either direction** — and find the albums in
your **Roon** library on either of them.

Two ways to run it, one codebase:

* **Docker** — a small web app on your server or NAS. `docker compose up -d`.
* **Android APK** — the same app, entirely on your phone. No server, no
  Docker, nothing else running.

The interface is not a lookalike. The APK bundles this repository's own
`public/` directory and serves it from a loopback HTTP server in the same
process, so both builds run byte-identical HTML, CSS and JavaScript — the
approach [MusicD Remote Lite](https://github.com/meltface-80/Android-Random-Remote)
established, applied to a much smaller app. CI checks the page inside the built
APK against `public/` and fails if they differ.

---

## ⚠️ Read this before you install it

**Qobuz has no public API.** This app uses the same unofficial one that every
open-source Qobuz client and the Lyrion/LMS "Qobuz" plugin use. That is
**against Qobuz's terms of service**, it can stop working without notice, and
you use it at your own risk. The Spotify side is the official Web API.

**It writes to your library.** Playlists get created, albums and artists get
favourited. Everything it does is additive — it never deletes or unfollows
anything — and it is safe to run twice, but you should still **run the preview
first**, which is the default.

---

## What actually moves

| | |
|---|---|
| **Playlists** | Created on the other service with the same name and the tracks in the same order. Pick which ones. |
| **Favourite albums** | Matched by barcode where both services have one, then by title and artist. |
| **Followed artists** | Matched on an exact name. |
| **Favourite tracks** | Matched by ISRC, then by title, artist and duration. |

Both directions. The same code runs either way — the two service clients
implement one interface, so "Qobuz → Spotify" and "Spotify → Qobuz" are the
same migration with the source and destination swapped.

### What does *not* move

* **Tracks and playlists, when the source is Roon.** Deliberately — see below.
* **Play counts, dates added, playlist artwork and playlist descriptions.**
  Neither API offers a way to set most of these.
* **Local files in a Spotify playlist.** They exist only on that machine.
  Reported as skipped, by name.
* **Podcast episodes.** Not music, and there is nothing to match them to.
* **Anything the other service does not have.** Reported, with the reason.

---

## Roon

Point it at your Roon Core and it will scan your library — ten thousand local
albums is fine — and then look for each of those albums on Qobuz or Spotify.
The output is a **CSV with a row per album** saying, for each service, either
what it was matched to or **why it was not**. That list is the point: it is
what tells you which of your records are not available to stream, and which
are there under a different edition.

**Roon is a source only.** Nothing is ever written back into it — the files
would have to exist first — so the two directions are Roon → Spotify and
Roon → Qobuz, and there is no way to ask for the reverse.

**Albums and artists. Not tracks, and not playlists.** This is a deliberate
refusal, not a missing feature. A Roon browse row gives a title and an artist
and *not a track's length*, and a title and an artist alone are exactly the
two facts that a cover version, a re-recording and a live take also satisfy.
Matching on that would quietly put the wrong recording in your library, which
is the one failure this app is built to avoid. Ask for them anyway and the run
records the reason rather than reporting nothing found.

### How a Roon album is matched without a barcode

Qobuz and Spotify both hand over a barcode, which identifies a release
outright. Roon hands over none, so title and artist are all there is — and
they are not enough on their own. "Greatest Hits" by almost anybody is several
different records.

So the album's **own track listing** carries the decision. A candidate has to
match on title and artist *and* share at least 70% of the tracks you own, or
it is refused and the report says how many it did share. Two different records
by the same artist with the same title do not have the same eleven track
titles.

A deluxe edition is accepted for the standard one you own — it contains all of
it — and the report says which edition you got.

### Setting it up

1. Press **Find my Roon Core**. Discovery is a UDP broadcast; if your network
   drops those, type the address instead (Roon → Settings → General).
2. Roon will show **MusicD Migrate** under **Settings → Extensions**. Enable
   it. This happens once — the token is remembered per Core.
3. Press **Scan my library**. A ten thousand album library is about a hundred
   requests to the Core and takes a minute or two. It can be stopped and
   resumed.
4. Pick a direction, preview, and download the CSV.

The extension is read-only: it asks Roon for the browse service and nothing
else, it cannot play anything, and it cannot change anything in your library.

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

---

## Install: Docker

```bash
docker compose up -d
```

Then open `http://<that-machine>:3380`.

Or without cloning:

```yaml
services:
  musicd-migrate:
    build: https://github.com/meltface-80/MusicD-Migrate.git#main
    container_name: musicd-migrate
    restart: unless-stopped
    ports:
      - "3380:3380"
    volumes:
      - musicd-migrate-data:/app/data
volumes:
  musicd-migrate-data:
```

**Keep the volume.** It holds both services' sign-ins and the match cache. A
renamed one starts empty: you sign in again, and every track is looked up from
scratch on the next run.

### Options

| Environment variable | |
|---|---|
| `PORT` | Default `3380`. |
| `MIGRATE_PIN` | If set, every request needs this PIN — including the page. Off by default. See [Who can reach it](#who-can-reach-it). |
| `TZ` | Only used to date the job history readably. |
| `DATA_DIR` | Where the database lives. Default `/app/data` in the container. |

---

## Install: Android

**Download: [`dist/musicd-migrate-0.3.0.apk`](dist/musicd-migrate-0.3.0.apk)**
— open it on the phone and Android will ask you to allow installing from that
source once.

`dist/latest.json` carries the version and the APK's SHA-256 if you want to
check the download.

From here on the `apk` job in CI rebuilds and republishes that file whenever
`versionName` is bumped, signed with the same key, so updates install over the
top. That needs the keystore in the repository secrets — see
[Signing the APK](#signing-the-apk).

Nothing else is needed — no Docker, no server, no companion anything. The app
runs its own HTTP server on `127.0.0.1` and shows the page in a WebView.

It runs as a foreground service with an ongoing notification while it works.
That is not decoration: a migration of a real library takes minutes, you will
lock your phone during it, and a plain activity's process is killable the
moment it leaves the screen.

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

**The key for this project already exists.** `dist/musicd-migrate-0.3.0.apk`
is signed with it, and its fingerprint is pinned in
`tools/release-key.sha256`. Every later build has to use the **same** key or
Android will refuse to install it over the copy already on the phone — that is
the whole reason this is a stored secret rather than a key made per run.

To let CI take over the publishing, add the keystore you were given as two
repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `MUSICD_KEYSTORE_BASE64` | the base64 blob from above |
| `MUSICD_KEYSTORE_PASSWORD` | the password you chose |

The alias is `musicd`, which the workflow passes as `MUSICD_KEY_ALIAS`.

To get the base64 blob from the keystore file:

```bash
base64 -w0 musicd-release.jks     # macOS: base64 -i musicd-release.jks
```

With those two secrets set, bumping `versionName` and `versionCode` in
`android/app/build.gradle.kts` is all it takes: CI builds, checks the signature
against the pinned fingerprint, writes the APK and `dist/latest.json`, and
rewrites the download link in this README.

### Starting over with a new key

Only if the keystore is lost. Make one with:

```bash
keytool -genkeypair -v \
  -keystore musicd-release.jks \
  -alias musicd \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12 \
  -dname "CN=MusicD Migrate"
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
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' > tools/release-key.sha256
```

If the file is ever removed the key is simply not pinned, and the job says so in
a notice rather than failing — it is a guard, not a prerequisite.

---

## Signing in

### Qobuz — nothing to set up

Press **Sign in with Qobuz**. You sign in on qobuz.com and it sends you back.
No password is typed into this app and none passes through it.

There is an email-and-password fallback for when the redirect cannot get back
to you — a headless server you are configuring from a laptop elsewhere. It is
the fallback on purpose: the password is hashed before it is sent, the way
Qobuz's API wants it, and the plaintext is never stored, but the redirect is
still better because this app never sees anything at all.

### Spotify — you need a Client ID, and that is currently the hard part

Spotify will not issue tokens to an application it does not know, so this app
asks for a **Client ID**. There is no client secret: sign-in uses PKCE, which
exists precisely for apps that cannot keep one, so nothing secret is shipped in
the container or the APK.

> **⚠️ Spotify has frozen new app registrations.** Since early 2026 the
> **Create app** button on the developer dashboard has been greyed out, with
> the tooltip *"New integrations are currently on hold while we make updates to
> improve reliability and performance."* Community reports had it still
> disabled in September 2026, and Spotify has given no timeline. Nothing in
> this app can work around that — an unregistered client id gets no tokens.

**If you already have a Spotify app**, from anything at all, you are fine:
existing apps still work and only new ones are frozen.

1. Open [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
   and pick the app.
2. **Settings → Edit → Redirect URIs**: add the address this app shows you on
   its own Spotify card. It is derived from how you reached the page, so it is
   already right — on the phone it is
   `http://127.0.0.1:3380/login`. Save.
3. Copy the **Client ID** from that same page into this app.

**If you do not have one**, the Spotify half cannot be set up yet. Both
directions of a migration need Spotify, so that blocks the whole thing. The
dashboard's access-request form is the only sanctioned route, and it is slow.

The open-source Spotify ecosystem — librespot, ncspot, Spotty, SpotOn — has
long dealt with this by sharing a well-known Client ID among its users. You can
paste one into the Client ID field and this app will work with it: **the
redirect path is `/login` precisely so that it does.** Those registrations
whitelist exactly one loopback path, and sending anything else gets
`redirect_uri: Not matching configuration` before you can even sign in — which
is what this app's original `/api/spotify/callback` got.

This project deliberately does **not** ship such an id as a default. They
belong to other projects, your traffic counts against their quota, and the
consent screen will name their application rather than this one. Pasting one is
a trade-off you may reasonably choose; it is not a default imposed on you.

#### If Spotify refuses your redirect URI

Spotify accepts `https://…` or `http://` **on the loopback IP literal only**.
`http://127.0.0.1:3380/login` is fine;
`http://localhost:3380/…` is refused, and so is `http://192.168.1.50:3380/…`.

The **port** is the forgiving part and the **path** is not. RFC 8252 says a
loopback redirect's port should be ignored, and that matches what we observed:
a shared community Client ID accepted this app's port quite happily and
rejected it on the path alone. So the path must be exactly `/login`, which is
what the app now uses and displays; the port it happens to be running on is
fine either way.

If you are registering your own app, just paste what the app shows you and none
of this matters.

The app checks this for you and says so before you spend a sign-in finding out.
Two ways through:

* Open the app on `http://127.0.0.1:3380` from the machine it runs on. Then
  everything just works.
* Or register the `127.0.0.1` address anyway and use **Can't get back here?**:
  sign in from wherever you are, and when Spotify redirects to a page that will
  not load, copy the whole address out of the address bar and paste it in.

**On the phone this never comes up** — the APK's server *is* on loopback, which
is the one address Spotify accepts over plain http.

---

## Running it twice is fine

This is designed for it, because the second run is the common one — you buy
records and migrate again.

* **Favourites** are idempotent: both services' "save" endpoints are.
* **Playlists**: a second run finds the playlist it made last time and adds
  only what is missing. (Changeable: make another one, or leave it alone.)
* **Nothing is ever deleted or unfollowed.**

And it is fast the second time. The expensive part of a migration is not
writing, it is *looking up*: a 2,000-track library is up to 4,000 searches
against a rate-limited API, where the writes are about 40 requests. So every
lookup is cached — **including the misses** — and a re-run of the same
migration does almost no network work at all. **Forget cached lookups** in the
UI clears it, for when a service's catalogue has changed and a previous "not
available" is worth retrying.

---

## Who can reach it

**The APK binds `127.0.0.1` and there is deliberately no switch to widen it.**
Behind that socket are two services' access tokens and the ability to write to
your library. Nothing else on your network has any reason to reach a migration
running on your phone, so the option does not exist.

**The Docker build has to listen on every interface** — that is how a published
port works, and how the sign-in redirect reaches it from a phone. On a home
network that is usually fine and a PIN would be friction for nothing, so it is
off by default. Set `MIGRATE_PIN` to require one; it then covers every route
*including the page itself*, because a gate with an unauthenticated hole in it
is not a gate. The two OAuth callbacks are the deliberate exception: they arrive
from your browser following a redirect, which cannot carry a header, and they
are useless without the one-time state the app generated moments earlier.

Android's automatic backup is **off** for the APK. The database holds access
tokens, and copying those to cloud storage and restoring them onto a different
device is not something anyone asked for when they signed in on this phone.

---

## How it fits together

```
 Docker                                  Android APK
 ┌────────────────────────┐              ┌──────────────────────────────┐
 │ browser                │              │ MainActivity — a WebView     │
 └──────────┬─────────────┘              └───────────────┬──────────────┘
            │ /api/…                                     │ /api/…
 ┌──────────▼─────────────┐              ┌───────────────▼──────────────┐
 │ index.js  (Express)    │              │ MigrateService               │
 │                        │              │   HttpServer → MigrateApi    │
 │ lib/  ── the decisions │              │ :core ── the same decisions  │
 │   canon.js  match.js   │   ⟷ same ⟷   │   Canon.kt  Match.kt         │
 │   spotify.js qobuz.js  │    rules     │   SpotifyClient QobuzClient  │
 │   migrate.js store.js  │              │   Migration.kt  Store.kt     │
 └──────────┬─────────────┘              └───────────────┬──────────────┘
            │                                            │
            └──────────► public/  ◄─────────────────────  ┘
                   one front-end, shipped to both
```

Nothing that makes a decision lives in `index.js` or in the Android module.
`index.js` is a route table; the Android module is a WebView, a service and
SQLite. Everything that could be wrong is in `lib/` and `:core`, where it is
tested without a network or an account — and the Kotlin tests are the
JavaScript suite translated case for case, so "the APK behaves like the
container" is something that fails a build rather than something to hope for.

---

## Verification

```bash
npm test                                   # 188 tests: units + the API over a socket
npx eslint --config tools/eslint.config.mjs public/app.js
cd android && ./gradlew :core:test         # 175 tests, the JS suite translated
```

No suite needs a Qobuz account, a Spotify account or a Roon Core: the service
clients are driven by a fake HTTP layer, the migration engine by fake services
implementing the same interface, and the Roon client by a scripted browse tree
behind an injectable socket.

Five checks in `ContractTest` exist to stop the two halves drifting, because
`public/app.js` is written once and shipped to both — so a mismatch breaks the
APK and *nothing in the Docker build would notice*. They read the real source
files and fail when:

* a route the page calls is missing from `index.js` **or** `MigrateApi.kt`;
* an option the page sends is never read by one of the two servers;
* the edition-word lists in `lib/canon.js` and `Canon.kt` disagree;
* the read/write method lists in `lib/service.js` and `Model.kt` disagree;
* `optString` appears anywhere outside `Json.kt` — Android's `org.json` returns
  the **literal string `"null"`** for a JSON null, so a null ISRC read that way
  would be *searched for* on the other service. Every JVM test is blind to it.

Two more pin the Roon protocol across the two implementations: the SOOD
discovery packet byte for byte, and the album key, because the two halves must
file the same record under the same id or a library scanned on one and
migrated on the other pays for every lookup twice.

### What is not tested

**No Roon Core has ever been contacted.** There is none in CI and none in the
environment this was written in. The protocol is a port of code that has run
against a real Core, the codecs are tested exhaustively, discovery runs over a
real UDP socket against a fake Core on loopback, and the transport runs over a
real WebSocket against a hand-rolled RFC 6455 server — but the first genuine
pairing will be yours.

`MainActivity` and `MigrateService` — the WebView, the foreground service and
the multicast lock — have nothing but the compiler behind them. There are no
instrumentation tests and no device in the loop. Everything underneath them,
including the HTTP server and the whole route table, is exercised on a JVM
over a real socket.

---

## Appendix: the Client ID this build signs in with

This is **baked in**, because Spotify has frozen new registrations and a setup
step nobody can complete is an unusable app. It is the owner's decision for the
owner's own install, taken with all of the below understood.

```
d420a117a32841c2b3474932e49fb54b
```

`Pkce.DEFAULT_CLIENT_ID` in `android/core/…/Pkce.kt` and `DEFAULT_CLIENT_ID` in
`lib/spotify-pkce.js`; `ContractTest` fails if those two, or this appendix, ever
disagree — the id that starts a sign-in has to be the id that redeems the code,
and Spotify's complaint when they differ is about the *code*, not about the id.

What it is, plainly:

* **It is not registered to MusicD Migrate.** It comes from the open-source
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
  Both halves resolve it the same way — saved, else built-in — through a single
  function, because a fallback applied at three sites out of four is a sign-in
  that begins as one application and ends as another.
* **It is not a secret.** Sign-in uses PKCE, there is no client secret, and a
  client id travels in the query string of the authorise URL in plain sight
  every time anyone signs in to anything. Writing it down changes nothing about
  who can see it.

**If you fork this**, put your own id in those two constants, or clear them
both to go back to asking the user — the page still has the field, the Save
button and the instructions for registering one.

---

## Licence

MIT. See [LICENSE](LICENSE).

`lib/qobuz-oauth.js` is ported from
[MusicD-Remote](https://github.com/meltface-80/MusicD-Remote), and
`android/core/…/http/HttpServer.kt` is adapted from
[Android-Random-Remote](https://github.com/meltface-80/Android-Random-Remote) —
same author, same licence.
