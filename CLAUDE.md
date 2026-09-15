# Working on this repository

## The rule

**Do not ship a change you have not tested. If you cannot test it, say so in
the same breath as you hand it over.**

Compiling is not evidence. `node --check` is not evidence either — it parses a
file, it does not resolve names, and a front-end with no build step will
happily ship a call to a function that no longer exists.

## What "tested" means here, concretely

Run all of these before pushing. None is optional, and none needs a Qobuz or
Spotify account.

```bash
npm test                                                  # 188 tests
npx eslint --config tools/eslint.config.mjs public/app.js  # no-undef is the point
node tools/make-icons.js && git diff --exit-code public/icons/
cd android && ./gradlew :core:test                         # 175 tests
```

The APK needs an Android SDK (platform 36, build-tools 36) and JDK 17:

```bash
cd android && ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleRelease
```

**A new test must fail before the fix and pass after it.** Prove it: break the
fix, run the test, show it failing, restore. A test that passes both ways is
decoration.

That is not a slogan here — it has already earned its keep. Teaching
`lib/canon.js` that `"live"` is an edition word makes exactly one test fail, and
it is the one that matters. Two real bugs in this repository were found by
mutating a passing suite rather than by reading the code.

## THE THING THIS REPOSITORY IS FOR

One decision is load-bearing above everything else: **when is a track on one
service the same recording as a track on the other.**

It is the only code here that can be wrong in a way nobody notices. A failed
migration is obvious and recoverable. A migration that quietly put the karaoke
version, the radio edit or a covers band into somebody's playlist **looks like
it worked**, and they find out months later with a playlist they can no longer
trust.

So the rule, in `lib/match.js` and `Match.kt` alike:

> **Where the evidence is not decisive, match nothing — and report why.**

Concretely, and do not relax any of these without a very good argument:

- **There is no "best guess" tier.** For a TRACK: ISRC; or
  title+artist+duration; or title-without-edition-suffix+artist+tighter-
  duration. Below that, nothing. For an ALBUM: barcode; or
  title+artist+**track listing**. Below that, nothing.
- **Title and artist alone are not an album match.** They are not decisive:
  "Greatest Hits" by almost anybody is several different records, a live album
  and a studio album share a name often enough, and a covers band files under
  a name that normalises to the same string. With no barcode — which is every
  Roon album — the album's own TRACK LISTING carries the decision, at 70% of
  what the user owns (`TRACKLIST_MIN_COVERAGE`, in both languages). Coverage
  is measured against what the user OWNS, not against what the candidate
  holds, or a deluxe edition scores half and a record that is plainly right is
  refused. A barcode match is never re-checked against a listing: it is
  decisive, and re-checking could only turn a right answer into a wrong
  refusal.
- **Two candidates, and no more.** Each corroboration costs a read on the
  other service. Ten thousand albums at four candidates each is forty thousand
  requests against a rate-limited API, and the shortlist is already ordered.
- **An edition suffix is strippable. A different performance is not.**
  `(Remastered)` is the same performance. `(Live)`, `(Acoustic)`,
  `(Radio Edit)`, `(Someone Remix)`, `- Extended Mix`, `(Demo)` are not, and
  must never be added to `EDITION_WORDS`. `ContractTest` checks the two lists
  agree; nothing checks that a wrong word was not added to *both*.
- **No duration means no title-tier match.** Title and artist alone are exactly
  the two facts a cover, a re-recording and a live take also satisfy.
- **A missing ISRC is missing data, not evidence.** It falls through to the
  title tiers; it never counts against a candidate.
- **Every refusal carries a reason, and the reasons are different on purpose.**
  "The title is there and the length is wrong" means the user owns a different
  edition and can fix it by hand. "Nothing called that" means it is not there.
  Collapsing both into "not found" throws away the difference, and the
  unmatched report is the actual deliverable of a migration.

## Two halves, one front-end

`public/` is served by the Docker build (`index.js`) **and bundled unchanged
into the APK**, where a Kotlin reimplementation answers the same routes.

**The bundled page is the authority on every API field name** — not `index.js`
and not `MigrateApi.kt`. MusicD Remote Lite collected eleven wire-contract bugs
from porting a server's names rather than reading what the page actually calls.

So: **anything added to one server must be added to the other, and the page's
spelling wins.** A mismatch breaks the APK and *nothing in the Docker build
would notice*, because the container is the half everyone tests.

`ContractTest.kt` catches five classes of that automatically — a route the
page calls that one server does not serve, an option the page sends that one
server never reads, the two edition-word lists disagreeing, the read/write
method lists disagreeing, and `optString` escaping `Json.kt`. It does not catch
a renamed JSON *field*. `ApiTest.kt` and `test/unit/server.test.js` assert the
field names both sides emit; keep them in step by hand.

Those tests read files outside `:core`, so `core/build.gradle.kts` declares
them as task inputs. Without that Gradle reports `:core:test` up to date after
a change to the JavaScript half alone — which is the one case the contract
tests exist for.

`public/` has no build step and must not acquire one. The APK bundles the
directory as-is.

## Things about this codebase that are easy to get wrong

- **Roon needs Node 22.** The MOO session runs over the global `WebSocket`,
  which arrived in Node 22, and Node's WebSocket hands binary frames over as a
  **Blob** unless `binaryType` is set to `"arraybuffer"` — a Blob read as a
  Buffer is empty, so every MOO frame silently fails to parse and the Core
  looks like it connected and then said nothing.
  `test/unit/roon-socket.test.js` drives a real handshake over a hand-rolled
  RFC 6455 server on loopback, which is what catches that. The Dockerfile and
  CI are on 22.
- **Roon reaches the Core over a RAW SOCKET, and that is load-bearing on
  Android.** The app's network security config permits cleartext to loopback
  and nowhere else, but a Roon Core is a plain `ws://` on the LAN. Android's
  cleartext policy is enforced by the HTTP LIBRARIES — HttpURLConnection,
  OkHttp, WebView — and not by `java.net.Socket` or `DatagramSocket`, so
  `RawWebSocketFactory` and `SoodDiscovery` are unaffected by it. Writing the
  WebSocket by hand was done because `:core` has no dependencies; keeping it
  that way is also what lets the cleartext exemption stay scoped to loopback.
  **Do not replace it with an HTTP library**: Roon would stop working on the
  phone, and widening the exemption to fix that would permit cleartext to
  every destination for an app that holds two services' access tokens.
- **Android filters multicast out of userspace** unless the app holds a
  `WifiManager` multicast lock, so SOOD discovery needs one or a network with
  a Roon Core on it reports none. `WifiMulticastLock` in `MigrateService.kt`
  holds it, `CHANGE_WIFI_MULTICAST_STATE` is in the manifest, and
  `MigrateApi` takes the discovery as a parameter only so the app can supply
  it — `:core` must not depend on the SDK.
- **There is no Roon Core in CI, in Docker, or in the container this was
  written in.** So `lib/roon-core.js` takes the socket, the discovery and the
  token store as seams, and the tests drive a scripted Core and assert on the
  exact frames. Discovery is the exception: it runs over a real UDP socket
  against a fake Core on loopback, because a mock would assume the packet
  layout rather than check it. What cannot be tested here is a REAL Core's
  behaviour, and that has to be said plainly wherever Roon is handed over.
- **A service is either readable or readable-and-writable.** `MusicSource` is
  the reading half and `MusicTarget` adds the searches and the writes;
  `lib/service.js` says the same in the only way JavaScript can, as a list of
  method names and a check that throws. Roon is a source and can never be a
  target — a Roon library is files on a disk. Get that wrong and the failure is
  not a crash: `safely()` turns a failed search into one unmatched item on
  purpose, so a whole run would report every album as "not found".
- **Qobuz counts durations in SECONDS.** Spotify's `duration_ms` is
  milliseconds. The conversion happens once, in `toTrack`/`toQobuzTrack`, and
  nowhere else. A matcher comparing 213 against 213000 rejects every track in
  the library and looks exactly like "nothing is on Qobuz".
- **Qobuz puts the edition in a separate `version` field.** Title "Blue Monday"
  + version "2016 Remaster" against Spotify's one string. Recomposed in the
  same two functions, so `stripVersion` sees the same thing from both sides.
- **`optString` is unsafe.** Android's `org.json` returns the literal string
  `"null"` for a JSON null; the desktop one returns `""`. Every JVM test is
  blind to the difference. Use `str()` / `strOrNull()` from `Json.kt`.
- **Spotify's album SEARCH returns no barcode, so albums must be searched BY
  barcode.** `/search?type=album` yields SimplifiedAlbumObject, which has no
  `external_ids` — so a candidate from `searchAlbums` always carries
  `upc = ""`, and `matchAlbum`'s barcode tier compared a real Qobuz code
  against an empty string for every candidate and could never fire. Everything
  fell through to the title tiers, where the track-count gate then refused any
  edition mismatch: **114 of 195 favourite albums reported "not found" on a
  real library.** The fix is the `upc:` search filter (albums only), mirroring
  `searchByIsrc` for tracks, and it must be tried BEFORE the title search —
  an earlier version fetched the source barcode *after* searching, so the code
  was never used to search for anything.
  Because the result still carries no barcode, **the filter is the evidence**:
  the code is stamped onto the result so the tier can see it, and only when the
  result set is small (`UPC_TRUST_LIMIT`) — a barcode identifies one release,
  and a crowd means the filter is not filtering, where stamping would be a
  confidently wrong match.
  The test fakes must model this: `FakeService.searchAlbums` strips `upc`,
  because an earlier version returned it and thereby **hid the exact bug it
  should have exposed** — the title path "matched on barcode" under test while
  failing in production.
- **Spotify's redirect path is `/login`, not `/api/spotify/callback`.** The
  shared community Client IDs — the only ones available while Spotify has new
  registrations frozen — whitelist exactly one loopback path, and any other
  gets `redirect_uri: Not matching configuration` before the user can sign in.
  The port is forgiving (RFC 8252); the path is not. `Pkce.CALLBACK_PATH` and
  `lib/spotify-pkce.js` both define it, `/api/spotify/callback` is still served
  for an already-registered URI, and `/login` has to be routed BEFORE the
  static fallback on both sides or it 404s looking for an asset called "login".
- **The app cannot load its own page without a network security config.**
  Android denies cleartext HTTP by default at targetSdk 28+, and the entire UI
  is served over plain http from 127.0.0.1 by the app's own server. v0.1.0
  shipped without `android:networkSecurityConfig` and every launch showed
  `net::ERR_CLEARTEXT_NOT_PERMITTED`, which reads like a network failure when
  nothing left the device. `tools/check-android-cleartext.py` guards it, in
  both suites and in CI. Keep the exemption scoped to loopback in a
  `domain-config`: this app holds two services' access tokens, and
  `base-config` or `usesCleartextTraffic="true"` would permit cleartext to
  every destination. The guard fails on either.
- **Do not use flexbox `gap` in `public/style.css`.** It arrived in Chrome 84,
  and an Android WebView is not always current — on API 28's bundled Chromium
  every `gap` collapsed to nothing and the page rendered as "Qobuznot signed
  in". Spacing in flex rows uses the negative-container-margin pattern
  instead, which works everywhere. Grid `gap` (`.history`) is fine and is
  supported far earlier.
- **`[hidden]` loses to any author rule that sets `display`.** `button, .btn {
  display: inline-block }` made both "Sign out" buttons visible while signed
  out, while the JavaScript set `.hidden = true` faithfully. `style.css` now
  has `[hidden] { display: none !important }`; do not remove it, and toggle
  visibility with `el.hidden`, never `style.display`.
- **Batch every write, and count a failed batch as all of it.** The endpoint
  maxima are 100 (Spotify playlist tracks) and 50 (everything else, both
  services). A failed batch of fifty is one report row but *fifty* stranded
  items — counting it as one understated the damage by forty-nine, which is a
  bug that shipped here once.
- **Obey 429 with the delay the service asked for.** Spotify sends
  `Retry-After` and it is authoritative; guessing shorter turns one 429 into a
  cascade. Qobuz sends nothing, so it backs off exponentially.
- **Cache the misses.** A cached "we looked and found nothing" is a real
  answer. Treating it as "we have not looked" makes every re-run pay again for
  exactly the tracks that are slowest, because a miss costs the full fallback
  search.
- **Playlist order is a promise.** Lookups run concurrently; results are
  written into a slot by index and read back in order. Pushing as they land
  shuffles every playlist into completion order. The *report* rows are not
  ordered — they are recorded as workers finish — so never assert on
  `items[0]` / `items[1]` in a test.
- **`safely()` wraps searches and nothing else.** A search that fails costs one
  track. A read or a write that fails means something is actually wrong.
  `AuthError` is re-thrown through it: a dead sign-in must stop the run, not be
  reported as four thousand misses.
- **The APK's server binds loopback and has no switch to widen it.** Two
  services' access tokens and write access to somebody's library are behind it.
  Do not add a LAN option. In Docker the bind is necessarily wide (that is how
  a published port works); `MIGRATE_PIN` gates every route *including the static
  page*, and the only exceptions are the two OAuth callbacks, which cannot
  carry a header. **Do not add a per-route bypass.**
- **Sign-ins open in a real browser on Android, never the WebView.** A WebView
  does not share the browser's cookies, so signing in there means typing a
  password into a window this app controls — the thing the redirect flow exists
  to avoid — and providers may refuse embedded WebViews outright.
- **Spotify rotates refresh tokens.** A refresh may return a new one, and the
  old one then stops working. Persist on every refresh (`onTokens`), or a
  long-lived install loses its sign-in with no way back.
- **A Qobuz `user_auth_token` belongs to the app that minted it.** The `app_id`
  and the token move together; presenting a mismatched pair is a 401 that reads
  exactly like an expired sign-in.
- **Guard CSV cells that start with `=`, `+`, `-` or `@`.** A spreadsheet
  executes them as formulas, and track titles beginning with `-` are not rare.
  Both `index.js` and `MigrateApi.kt` prefix an apostrophe.

## Errors must be loud enough to notice

`try { … } catch (e) {}` around something the app depends on is how features
fail invisibly for releases at a time. When guarding something genuinely
optional, make sure the app really works without it — and do not let unrelated
features depend on the thing being guarded. Every deliberate empty catch in
this repository carries a comment saying why.

## The honesty rule about Android code

`:core` is a plain Kotlin/JVM module and is properly tested — the matching,
both API clients, the migration engine, the HTTP server and the whole route
table, including over a real socket.

`app/src/main/kotlin/` has **no automated tests**. There are no instrumentation
tests. So for anything in there, state plainly what was verified and what was
not. "Compiles and the core tests pass" is an honest claim. "Fixed" is not,
unless someone has run it.

**An emulator can be run here, and it caught two bugs the whole test suite
missed.** It needs no KVM — software emulation is slow but it boots, and an
API 28 image is the right target because that is the release where cleartext
became blocked:

```bash
sdkmanager "system-images;android-28;default;x86_64" "emulator"
avdmanager create avd -n ct -k "system-images;android-28;default;x86_64" -d pixel
emulator -avd ct -no-window -no-audio -no-boot-anim -gpu off -accel off &
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 15; done
adb install -r <apk> && adb shell am start -n com.musicd.migrate/com.musicd.migrate.android.MainActivity
adb exec-out screencap -p > shot.png
adb shell dumpsys activity services com.musicd.migrate | grep isForeground
```

Expect ~10 minutes to boot and a "System UI isn't responding" dialog from the
emulator itself — that is the software renderer, not the app. **Do this for any
change to `app/`.** Two releases' worth of bugs were sitting behind a green
suite: the missing cleartext config, and every flexbox `gap` collapsing on an
older WebView.

**Push logic down into `:core` wherever it can go** — that is the only place
with tests.

The APK is still evidence and can be inspected without a device. Do it when a
change touches the manifest, resources or the bundled page:

```bash
apkanalyzer manifest print <apk>            # is the component really declared?
unzip -p <apk> assets/web/app.js | cmp - public/app.js
```

"The string is in the file" and "the component is declared with the right
intent-filter" are different claims.

## Qobuz, and being straight about it

The Qobuz API is unofficial and using it is against Qobuz's terms of service.
That is stated in `lib/qobuz.js`, in `QobuzClient.kt`, in the README above the
install instructions, and in the footer of the page itself — **where someone
reads it before installing rather than after.** Keep it that way. Do not
soften it, and do not move it somewhere less visible.

Do not add anything that fetches audio. Nothing in a migration needs it, and
shipping the machinery for it would invite the question of why it exists.
`MusicD-Remote` has that code (`lib/qobuz-sig.js`) and it is deliberately not
ported here.

## Ported files

- `lib/qobuz-oauth.js` — from `meltface-80/MusicD-Remote`, unchanged apart from
  its header comment.
- `android/core/…/http/HttpServer.kt` — adapted from
  `meltface-80/Android-Random-Remote`. The hard-won details are load-bearing:
  `SO_REUSEADDR` before the bind, the insistent retry on the requested port,
  keep-alive, and `shutdown()` rather than `shutdownNow()` so the worker doing
  the shutting down is not interrupted.

Unlike MusicD Remote Lite's dial, these are **not** kept in sync by a tool —
they were taken once and are ours now. Fix them here.

## Scope and process

- Develop on the branch named in the task. Never push to another branch.
- Do not open a pull request unless asked.
- Bump `version` in `package.json` for a server release, and **both**
  `versionName` and `versionCode` in `android/app/build.gradle.kts` for any APK
  meant to be installed — Android refuses to install over an equal or higher
  `versionCode`. The workflow publishes `dist/` and rewrites the README's
  download link from `versionName`.
- The icons are generated by `tools/make-icons.js`, not checked in as art. Edit
  the generator and re-run it; CI fails if the committed PNGs differ.
- **Do not add `tools` to the Android SDK package list in CI.** It is the
  legacy "Android SDK Tools" package, obsolete and no longer reliably
  downloadable: sdkmanager fetches it, fails with "Error on ZipFile unknown
  archive", and fails the whole job. It comes back corrupt only sometimes, so
  it passed twice here and then broke the merge to main. The APK needs the
  platform and the build-tools and nothing else.
- The signing keystore is private key material. It lives in CI secrets. Do not
  commit it, and do not change the key: an APK signed with a different one
  cannot install over the existing app.
- Ask before guessing when a choice is the user's to make. A destructive
  default, a corner, a layout — ask, do not assume and apologise later.
