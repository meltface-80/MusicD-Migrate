# Working on this repository

Mandarin Migration (formerly MusicD Migrate) is an **Android app only**, and
since 0.5.0 it is **Qobuz only**: it imports a library FILE (a CSV from the
owner's Mandarin server or any other software) into Qobuz favourite albums,
and it can clear a Qobuz library. Spotify was removed in 0.5.0, and Roon, the
Node server and the Docker build in 0.4.0 — all at the owner's request, "until
decided otherwise". The engine underneath is still service-neutral
(`MusicSource` / `MusicTarget`), so bringing a service back is a client and a
sign-in, not a rewrite. The last commit with Spotify is `c6d3de0` on `main`:
its client, its tests and the hard-won Spotify rules (rate-limit pacing,
`Retry-After`, the `upc:` search filter, shared refresh tokens, the `/login`
redirect, the baked-in Client ID) are all there. The Android package id stays
`com.musicd.migrate` — do not change it.

0.4.0 is also signed with a NEW key, `CN=Mandarin Migration`, made at the
owner's request because the original keystore was not kept. So 0.4.0 does
not install over MusicD Migrate (uninstall once, sign in again); every update
after it does, as long as the key never changes again.

## The rule

**Do not ship a change you have not tested. If you cannot test it, say so in
the same breath as you hand it over.**

Compiling is not evidence. `node --check` is not evidence either — it parses a
file, it does not resolve names, and a front-end with no build step will
happily ship a call to a function that no longer exists. ESLint's `no-undef`
cannot see a missing property on the shared `window.Mandarin` object either:
for a change to the page, drive it in a browser (see "Driving the page"
below).

## What "tested" means here, concretely

Run all of these before pushing. None is optional, and none needs a Qobuz
account.

```bash
npm run check          # runs every line below, in order
```

```bash
for f in public/*.js; do node --check "$f"; done
npx eslint@9 --config tools/eslint.config.mjs public/*.js   # no-undef is the point
python3 tools/check-android-cleartext.py
python3 tools/check-file-length.py                          # no source file over 500 lines
node tools/make-icons.js && git diff --exit-code public/icons/
cd android && ./gradlew :core:test                          # 211 tests
```

If `dl.google.com` is unreachable (it is blocked in some cloud sandboxes),
the root build cannot resolve the Android Gradle plugin and even
`:core:test` fails to configure. `:core` itself needs only the Kotlin JVM
plugin, so a throwaway settings file that includes `android/core` alone runs
the same tests — say that you did so.

The APK needs an Android SDK (platform 36, build-tools 36) and JDK 17:

```bash
cd android && ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleRelease
```

**A new test must fail before the fix and pass after it.** Prove it: break the
fix, run the test, show it failing, restore. A test that passes both ways is
decoration.

That is not a slogan here — it has already earned its keep. Teaching
`Canon.kt` that `"live"` is an edition word makes exactly one test fail, and
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

So the rule, in `Match.kt` and everything that feeds it:

> **Where the evidence is not decisive, match nothing — and report why.**

Concretely, and do not relax any of these without a very good argument:

- **0.5.1 changed three album rules, each on the owner's decision after his
  first real preview (14,524 albums, report and export in hand):**
  (1) a title match is put to the track listing even when the album HAS a
  barcode — one the service does not have (136 albums had matched on name
  alone); (2) an edition-stripped title whose track COUNT differs goes to the
  listing instead of being discarded ("War Child" vs its remaster with bonus
  tracks; 190 albums), still refused with the check off; (3) for an EXACT
  album-title match, a "(Live)" tag on EVERY one of the service's tracks is
  ignored on both sides (`Tracklist.withoutUniformLive`; 52 live albums
  shared 0 tracks). All three are pinned by tests that fail when reverted.
- **0.5.2 added three more, on the owner's decision after his second
  preview** (967 not found, report in hand), each pinned the same way:
  (1) a title search that comes back EMPTY is asked again without a trailing
  "(Original)" or catalogue number (`TitleTags.searchNoise`; 78 albums) —
  only with the listing check on, and the listing still decides. Never any
  other tag: without "(Live)" the search finds the studio record, and a live
  album's untagged track titles would agree with it. (2) A SHORTER edition is
  accepted when every one of its tracks is among the owner's, it has at least
  5, and it covers at least a third of what the owner has
  (`Tracklist.isShorterEdition`; "Train", 13 of the owner's 19; 26 albums) —
  the one exception to "coverage of what the user owns", and a different
  edition, never a different record. (3) A Cyrillic letter in a word that
  MIXES scripts reads as its Latin lookalike (`Canon.foldLookalikes`: KoЯn
  is Korn, 15 albums); an all-Cyrillic word is left alone.
- **There is no "best guess" tier.** For a TRACK: ISRC; or
  title+artist+duration; or title-without-edition-suffix+artist+tighter-
  duration. Below that, nothing. For an ALBUM: barcode; or
  title+artist+**track listing**; or — on the owner's decision, after two real
  reports — **a title differing only by a trailing tag, artist agreeing, AND
  the track listing agreeing** (`needsListing`). That last one is not a
  loosening of the tiers, it is the listing tier reached from further away:
  `matchAlbum` still REFUSES those candidates itself and only puts them on
  `shortlist`, so with the track-listing check off nothing changes and
  "(Live)" never passes on a title. What keeps it honest is that a live
  release usually tags its TRACKS live too, and "one live" is not "one", so
  the coverage test fails exactly where it should. The row says
  `method=tracklist` rather than claiming the title agreed. Below that,
  nothing.
- **Title and artist alone are not an album match.** They are not decisive:
  "Greatest Hits" by almost anybody is several different records, a live album
  and a studio album share a name often enough, and a covers band files under
  a name that normalises to the same string. With no barcode the album's own
  TRACK LISTING carries the decision, at 70% of what the user owns
  (`TRACKLIST_MIN_COVERAGE`, in `Tracklist.kt`). Coverage
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
  must never be added to `EDITION_WORDS` in `Canon.kt`. Nothing but
  `CanonTest`'s refusals and a careful review checks that a wrong word was not
  added.
  Five were added on the owner's decision from real reports — "bonus
  edition", "collectors edition", "international version", "u s version" (that
  is what `canon` makes of "U.S. Version") and the anchored `REMASTER`
  pattern — all of them one pressing of the same performances, the family
  "deluxe" and "anniversary edition" already belong to. Asked for and REFUSED
  in the same breath: "extended version", "(Remixes)", "(DJ Mix)",
  "(Acoustic)", "Vol. 2", "(EP)".
  **The list is matched as a SUBSTRING**, which is why the remaster spelling
  is a pattern and not a word: "re master" in the list also strips
  "(Pre-Master)", a studio stage and not an edition. `CanonTest` pins that.
  Adding a word here is the highest-risk edit in the repository and the one
  nothing else can catch — a wrong word is silent.
- **No duration means no title-tier match.** Title and artist alone are exactly
  the two facts a cover, a re-recording and a live take also satisfy.
- **A missing ISRC is missing data, not evidence.** It falls through to the
  title tiers; it never counts against a candidate.
- **Every refusal carries a reason, and the reasons are different on purpose.**
  "The title is there and the length is wrong" means the user owns a different
  edition and can fix it by hand. "Nothing called that" means it is not there.
  Collapsing both into "not found" throws away the difference, and the
  unmatched report is the actual deliverable of a migration. **A refusal quotes
  what WAS there**, too: `matchAlbum` has three separate album refusals — the
  closest record that artist does have, an album of that name by somebody else,
  and nothing at all — because they ask three different things of the user (fix
  the tag, accept a different edition, accept that it is missing). 1,465 rows of
  a real report said only "no album called X by that artist", which invites the
  entirely reasonable objection "but I own it, it is definitely on there".

## One page, one server

`public/` is **bundled unchanged into the APK** and served there by
`MigrateApi.kt` from a loopback HTTP server. It is three scripts sharing one
object, `window.Mandarin`: `app.js` (plumbing, state, the Qobuz sign-in,
start-up), `import.js` (the library-file card), `run.js` (progress, results,
history) and `clear.js` (the clear-out card). Start-up waits for `DOMContentLoaded` so all three have
registered; anything one script needs from another goes through
`window.Mandarin`, never a global.

**The page is the authority on every API field name**, not `MigrateApi.kt`.
MusicD Remote Lite collected eleven wire-contract bugs from porting a server's
names rather than reading what the page actually calls.

`ContractTest.kt` reads every `public/*.js` and fails on a route the page calls
that is not served, an option the page sends in `var body` (a migration) or
`var clearBody` (a clear-out) that the server never reads, and `optString`
escaping `Json.kt`. It does not catch a renamed JSON *field*: `ApiTest.kt` asserts the
field names; keep them in step with the page by hand.

Those tests read files outside `:core`, so `core/build.gradle.kts` declares
`public/` and the README as task inputs. Without that Gradle reports
`:core:test` up to date after a change to the page alone — which is the one
case the contract tests exist for.

`public/` has no build step and must not acquire one. The APK bundles the
directory as-is, and CI compares every file the page loads against the APK.

## Importing a library file

`Csv.kt` reads the file and `CsvLibrary.kt` turns it into a `MusicSource`, so
the import runs through the SAME `Migration` engine and `Match` rules as any
other source — albums only, at the owner's decision. The format is specified
in `docs/library-csv.md`; keep that file and the header aliases in `Csv.ALIASES`
in step, because Mandarin's export will be written against the doc.

- **Lenient about form, strict about evidence.** Comma, semicolon or tab; a
  BOM; CRLF; quoted newlines; headers spelt any common way — all read. A
  column not in `ALIASES` is IGNORED and listed back to the user, never
  guessed at. Nothing in the reader decides a match.
- **One row per track is the shape that matters.** Rows are grouped into
  albums by canonical title and album artist, and each album carries its own
  TRACK LISTING — the only evidence a barcode-less album has. A one-row-per-
  album file can only match albums with a barcode.
- **No barcode AND no listing means not searched for** (`unmatchable`, an
  amber row with `CsvLibrary.NO_EVIDENCE`). Without that hook the engine would
  ask for the missing listing, record a FAILED read, and fifty of those trip
  `UNREADABLE_LIMIT` — the breaker for a broken check — on a file that simply
  lacks the data. `ImportTest` pins it with sixty such albums.
- **An album's id carries its evidence** (title, artist, barcode and track
  titles, hashed). A refusal cached for a bare row must not be reused when a
  richer export of the same album arrives.
- **A spreadsheet mangles barcodes** (`075992736121` → `7.59927E+10`). Those are
  dropped and counted in a warning rather than searched for as digits.
- **The upload is the file's own bytes**, `fetch` with the `File` as the body,
  and the server streams them into the parser: a track-per-row export of a big
  library is ~20MB, which is why `HttpServer.MAX_BODY_BYTES` is 48MB, not 4MB
  (4MB answered 413 "Payload Too Large" — `HttpServerTest` pins a 12MB upload).
- **`<input type="file">` does nothing in a WebView without `FilePicker`**
  (`onShowFileChooser`), the same trap as downloads. Its callback must be
  answered every time, null on cancel, or the page never gets a picker again.
  It asks for `*/*` because file managers label CSVs four different ways, and
  `allowContentAccess` is on because the picked file arrives as `content://`.
  None of that is testable off a phone — say so when handing it over.
- **Library notes come out of album titles** (`CsvLibrary.cleanTitle`):
  "(12 tracks)", "(Disc 1)", "[CD-02]", " CD4", "(1 of 2)", a trailing "(2003)"
  or "(2)", and an "_" at the end or before a space. Repeated until stable.
  588 of 1,053 empty searches in the owner's export were these. It changes
  what is searched and compared, never what is accepted, and grouping by the
  cleaned title merges the discs of a set into one album with one listing.
  A real title keeps its "_" ("Nymph_o"), "(Live)" and "[Mini Album]".
- **The upload reads the file first** (`FileReader`), then sends text of known
  length, retrying once if nothing answers. Handing fetch() the File made the
  WebView stream a `content://` URI; a stream of unknown length goes CHUNKED,
  which `HttpServer` could not read (it can now — `readChunked`), and the user
  saw only "Failed to fetch". `api()` turns that message into words.
- **After an import PREVIEW, the results offer "Import N albums into Qobuz".**
  The only way to the real run used to be an unticked box under Options, and a
  real user finished a preview with "no option to import".

## Clearing a library — the one irreversible thing

`Clearance.kt` removes favourite tracks, favourite albums, followed artists and
playlists from one service. Everything else in this app only ever adds; this
only ever removes, and neither service keeps a copy. So the dangerous path is
the one that has to be asked for, and these must all stay:

- **A preview is the default, everywhere.** `ClearOptions.dryRun` defaults to
  true, and `/api/clear` reads an absent `dryRun` as true. A preview lists every
  item WITH ITS ID and removes nothing — and its CSV is the user's only record
  of what was there.
- **The server checks the typed name.** A real run is refused unless `confirm`
  equals the service's name. The page asks for it too, but the server must not
  trust the page: a stale page or a stray request must not empty a library.
- **The page only offers "Remove for good" for exactly the selection that was
  previewed**, and withdraws it once spent or once anything changes.
- **A playlist somebody else made is kept unless the user ticks the box.**
  Ownership must be KNOWN: an ownerless playlist is skipped, and if the account
  cannot be identified no playlist is touched. On Qobuz an owned playlist is
  `playlist/delete` and a followed one `playlist/unsubscribe`.
- **One batch is one request**: 50 of each kind, and the client chunks at the
  same number, so a failed batch names exactly the ids not removed.
- **A dead sign-in, a cancel and an unlifted rate limit stop the run**; a few
  failed batches among successes do not; `FAILED_BATCH_LIMIT` failures with not
  one success is the removal itself being refused, and stops it.
- **None of the removal endpoints has been run against a real account.** Say so
  whenever this is handed over, until someone has.

## File size

**No source file may exceed 500 lines** (raw lines, comments included;
Markdown is exempt). `tools/check-file-length.py` enforces it in CI and in
`npm run check`. When a file approaches the limit, split it by responsibility
BEFORE adding to it, keeping the public names its callers and tests use —
delegate rather than rename. The splits made in 0.4.0 show the pattern:

- `Migration.kt` → `Jobs.kt` (shared types), `JobLog.kt` (rows, counts,
  progress — shared with `Clearance`), `Lookups.kt` (cache, searches,
  corroboration), `Writes.kt` (write-as-you-go, worker pool), `Existing.kt`.
- `MigrateApi.kt` → `api/SignIns.kt` (sessions and OAuth routes),
  `api/ApiText.kt` (the JSON, CSV and HTML it answers with).
- `Match.kt` → `Tracklist.kt`, `TitleTags.kt`.
- `public/app.js` → `app.js`, `import.js`, `run.js`, `clear.js`.
- `MainActivity.kt` → `FilePicker.kt` (0.5.0, when the picker took it to 503).
- Tests split by subject with shared `MigrationFixtures` / `ApiFixtures`.

**Next in line**, at or near the limit — split these first if they grow:
`Migration.kt` (~480: the playlist phase is the natural next file — unused
since 0.5.0, but kept for when a service returns), `MainActivity.kt` (~460:
the crash reporting is the natural next file), `QobuzClient.kt` (~440: the
removals; `QobuzShapes.kt` already split off), `MigrateApi.kt` (~435:
`migrate()`/`clear()` option parsing), `Match.kt` (~430), `Lookups.kt` (~405).
Check with `python3 tools/check-file-length.py` and `wc -l`.

## Things about this codebase that are easy to get wrong

- **A service is either readable or readable-and-writable.** `MusicSource` is
  the reading half and `MusicTarget` adds the searches, the writes and the
  removals. A migration's SOURCE is only ever asked for reads.
- **Qobuz counts durations in SECONDS.** Everything else here counts
  milliseconds. The conversion happens once, in `toQobuzTrack`, and nowhere
  else. A matcher comparing 213 against 213000 rejects every track in
  the library and looks exactly like "nothing is on Qobuz".
- **Qobuz puts the edition in a separate `version` field.** Title "Blue Monday"
  + version "2016 Remaster" against Spotify's one string. Recomposed in the
  same two functions, so `stripVersion` sees the same thing from both sides.
- **`optString` is unsafe.** Android's `org.json` returns the literal string
  `"null"` for a JSON null; the desktop one returns `""`. Every JVM test is
  blind to the difference. Use `str()` / `strOrNull()` from `Json.kt`.
- **The app cannot load its own page without a network security config.**
  Android denies cleartext HTTP by default at targetSdk 28+, and the entire UI
  is served over plain http from 127.0.0.1 by the app's own server. v0.1.0
  shipped without `android:networkSecurityConfig` and every launch showed
  `net::ERR_CLEARTEXT_NOT_PERMITTED`, which reads like a network failure when
  nothing left the device. `tools/check-android-cleartext.py` guards it, in
  `npm run check` and in CI. Keep the exemption scoped to loopback in a
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
  maximum is 50 on Qobuz. A failed batch of fifty is one report row but *fifty* stranded
  items — counting it as one understated the damage by forty-nine, which is a
  bug that shipped here once.
- **WRITE AS YOU GO. A run that stops early must keep what it matched.**
  `write()` used to be called once, after the whole matching loop, so a run
  that was cancelled, tripped a circuit breaker, lost its sign-in, hit
  Android's six-hour foreground cap or simply had its **process killed** wrote
  NOTHING — however many albums it had already matched. Into Qobuz the phase
  finishes and the write happens, which is why only the Spotify directions
  looked broken; into Spotify a ten thousand album library takes hours against
  a rate-limited search, and any interruption in those hours saved nothing at
  all. 0.3.0's own error text promised "nothing already matched has been lost"
  while that was untrue.
  `Writes.Writer` holds ids and flushes at `WRITE_BATCH` (50 — the endpoint
  maximum, and what both clients already chunk at, so this costs **no extra
  requests**), and `Writes.flushAll()` runs from the run's catch as well, so the
  sub-batch remainder is not stranded either. **Both halves are needed and are
  tested separately**: nothing runs in a catch block when the process is
  killed, so only an already-issued write survives that — which is why the
  mid-phase test watches `writtenAlbums` from INSIDE the run rather than
  asserting after it. Asserting only afterwards let a mutation that removed
  either half pass, because each covered for the other.
  The batch is taken out of the held list **synchronously** (a `splice`, or
  under the lock in Kotlin) BEFORE the write, or two concurrent workers carry
  off the same ids. An incremental flush passes `quiet` so it does not relabel
  a matching run as "writing".
- **Obey 429.** Qobuz sends no `Retry-After`, so the client backs off
  exponentially. (Spotify did, and it was authoritative — see `c6d3de0`.)
  **A 502/503/504 is retried twice** (nginx in front of Qobuz; three albums of
  a real preview failed on one), then reported in words, never as HTML.
- **A SEARCH THAT COULD NOT BE MADE IS NOT A SEARCH THAT FOUND NOTHING.** This
  is the "cache the misses" rule one layer up, and it took a second real
  report to find. `safely()` collapsed every search failure into the caller's
  fallback, which for a search was `[]` — so a rate-limited or dropped search
  became "the search returned nothing", an AMBER row reading "your records
  are not on that service", and it was **cached**, so the refusal outlived the
  thing that caused it and a re-run did not even retry. `trySearch` (in
  `Lookups.kt`) returns the message beside the (empty) results; the caller turns it
  into a red `failed` row quoting what the service said, and never caches it.
  Only when nothing matched in the end: a failed barcode search does not
  matter once the title search has found the record. A search that comes back
  EMPTY is unchanged — amber, cached, a real answer.
- **A run whose searches never once work stops.** `noteSearchFailure`, the
  twin of `noteUnreadable`, with its own pair of counters and deliberately not
  sharing theirs: a run where searches work and the listing check is broken
  must still trip the other one. "Roon to Spotify seems unresponsive" was
  9,635 albums each waiting out a rate-limit backoff and then being recorded
  as a miss — days to report a library as absent.
- **A throttled run must LOOK different from a hung one.** `onRateLimit`
  existed on both clients and was passed by nothing but the
  tests — dead code in production. The progress label only changes when an
  item FINISHES, so a run held thirty seconds a search showed a counter that
  did not move and no reason at all. It is wired to `noteRateLimit` now, which
  names the service, the wait and the count, and **bypasses the progress
  throttle** — the 400ms throttle would otherwise drop the one label nothing
  overwrites for the next thirty seconds. A test caught that too.
  `progress.rateLimits` is on the wire and the page shows it as a pill.
- **Cache the misses. Never cache a failed READ.** A cached "we looked and
  found nothing" is a real answer; treating it as "we have not looked" makes
  every re-run pay again for exactly the tracks that are slowest, because a
  miss costs the full fallback search. But "we could not look" is not an
  answer, and caching it keeps a bug alive after its own repair — the next run
  reuses the refusal instead of retrying. So a refusal a failed read caused
  carries `unreadable` out of `Match.kt` / `Tracklist.kt`, becomes `problem` on
  the engine's `Resolved`, is **not** cached, and is recorded as **`failed`**
  (red) rather than `unmatched` (amber). That difference is the deliverable:
  0.2.0 shipped with a broken corroboration read, all ~1300 corroborated albums
  came back amber "not found", and it read as a library that is not on the other
  service rather than as a thing that was broken. It took a live run, a
  screenshot and an experiment with the check turned off to find.
- **AN ARTIST'S NAME MAY CONTAIN THE SEPARATOR.** "Carla Bley/Steve
  Swallow/Andy Sheppard" is three people; "AC/DC" is one band, so is
  "Belle & Sebastian", and "Earth, Wind & Fire" has both a comma and an
  ampersand in its name. `splitArtists` therefore treats a comma, semicolon,
  slash or ampersand as a separator **only when every segment it produces is
  more than one word** — two people are a first name and a last name on both
  sides, and a band with punctuation in its name almost always has a one-word
  piece. `feat.`, `ft.`, `featuring`, `with` and `vs` are never part of a name
  and always split. 88 albums of a real 9,635-album library were refused
  because the splitter had cut a band's own name in half.
- **`artistSet` carries the name AS WRITTEN as well as its parts**, and that
  is what makes two spellings of one name agree. Roon writes "Siouxsie and the
  Banshees", the service writes "Siouxsie & The Banshees", `canon` turns "&"
  into "and" so the two ARE the same string — but the split ran first, cut the
  service's name into "Siouxsie" and "The Banshees", and left nothing to agree
  with. 42 albums, refused over a spelling. Same for "To/Die/For" against
  "To Die For". The set also holds each form without a leading article and
  without a trailing ensemble word (`ENSEMBLE_WORDS`: trio, quartet,
  orchestra…), because "Vijay Iyer Trio" and "Vijay Iyer" are one artist
  billed two ways and jazz does it constantly. Every entry is still a name the
  string actually contains — **"tribute", "covers", "band" and "project" are
  deliberately NOT ensemble words**, because a tribute act is somebody else
  and that is the mistake this module exists to prevent. `MatchTest` and
  `CanonTest` both pin "Portishead" ≠ "Portishead Tribute"; keep them.
- **A tag that SAYS something gets one chance, and only from the listing.**
  `addedTag` is the counterpart of `redundantTag`: it finds the candidates
  whose title differs from the owner's by exactly one trailing tag that adds a
  fact — "(Live)" on a title that never mentions live, a venue, a date,
  "(Legacy Edition)". 634 of the 1,443 near-misses in two real reports were
  that shape. They go on `shortlist` with `needsListing` set and **`album`
  still null**: the engine corroborates them only when the user has the
  track-listing check on, and if the listing disagrees the ORIGINAL title
  refusal is kept, because "the closest that artist has is X" says more than a
  coverage number when the title differed too. Never promote one of these
  inside `matchAlbum`: that would be a title-and-artist album match, which is
  the one thing this file exists to refuse.
- **A tag that repeats what the title already says is not a difference.**
  `TitleTags.redundantTag`: a trailing "(…)", " - …" or ": …" is ignored
  when every word in it already appears in the other side's title or in the
  artist's name, and the rest of the title matches exactly. Services append
  "Always Let Me Go - Live In Tokyo **(Live In Tokyo)**" and "33 Hits **(Nina
  Simone)**"; Roon rips append "Jazz 'Round Midnight**: Stan Getz**". None of
  those adds a fact. A tag that DOES add one — `(Live)` on a title that never
  mentions live, `Vol. 2`, `(Remixes)`, `(Acoustic)` — fails the test and the
  album is still refused, **and that is the only reason this is allowed at
  all**. The tags are tried SHORTEST first, or a title with two of them gets
  cut back to something nobody owns.
- **A SEARCH takes one artist, not everything the source calls the artist.**
  `Lookups.searchArtist`. Roon writes an album's artists as one
  slash-joined string — "Carla Bley/Steve Swallow/Andy Sheppard" — and Qobuz
  hands over a single `performer`; a query naming all three finds nothing on
  either service. Measured on a real 9,514-album Roon library: **457 of the
  1,273 albums whose search came back EMPTY had an artist string naming more
  than one person** (352 slash, 100 ampersand, 16 comma), against 2.0% of the
  4,639 that matched — eighteen times over-represented among the failures. The
  GATES were never the problem: `artistSet` and `primaryArtist` split on all of
  those separators already, so a candidate that came back was compared
  correctly. Only the query was wrong. So: when a match rate looks bad, check
  what was actually ASKED before touching what is accepted — and note that the
  test fakes ignored the artist argument entirely, which is how this passed
  every test while failing for a sixth of a real library. **A fake that answers
  a query the real service would refuse is worse than no fake.** Both now model
  a literal word search.
- **A check that never once works stops the run.** `UNREADABLE_LIMIT`: fifty albums that could not be CHECKED with not one success is the
  check being broken, not bad luck, and one album corroborating anywhere
  disarms it for good so it cannot fire on a healthy run. This is the same rule
  as `safely()` re-throwing `AuthError` — a dead sign-in must stop a run rather
  than be reported as four thousand misses — and 0.2.0 is why it needed writing
  down: the run carried on for forty minutes and three thousand searches
  against a rate-limited API to report a library it could not check as a
  library that is not there. The reason a run stopped is shown **on the page**
  (`#res-error`), not in a toast: five seconds is not long enough to read the
  single most important sentence of a forty-minute run, let alone act on it.
- **An `extra` the API does not define is a request it can refuse.** Qobuz's
  `album/get` returns the track listing on its own; `extra: "tracks"` was added
  on a guess and `MusicD-Remote` — a Qobuz client that works — sends only
  `album_id`. A refused read *there* is invisible, because `safely()` turns it
  into one unmatched item and the corroboration tier then refuses every
  barcode-less album. Do not send a parameter to either service without a
  client that is known to work sending it too.
- **A WebView does NOTHING with a download — or a file input — unless the app
  says what to do.** Uploads: see `FilePicker` under "Importing a library
  file". The CSV links are ordinary `<a download>` links to the app's own server, and
  with no `DownloadListener` tapping one does nothing at all, silently — dead
  from v0.1.0 until somebody tried them. The report is the actual output of a
  migration, so a dead download is not cosmetic: it is the whole point of the
  run. `MainActivity` fetches it in-process (the URL is loopback and may carry
  the PIN in its query, which `DownloadManager`, a different process, knows
  nothing about) and writes it through `MediaStore.Downloads` on API 29+ or
  `getExternalFilesDir` below that, and **the toast always says where it went**,
  because a download the user cannot find is the same as no download.
- **Playlist order is a promise.** Lookups run concurrently; results are
  written into a slot by index and read back in order. Pushing as they land
  shuffles every playlist into completion order. The *report* rows are not
  ordered — they are recorded as workers finish — so never assert on
  `items[0]` / `items[1]` in a test.
- **`safely()` wraps searches and nothing else.** A search that fails costs one
  track. A read or a write that fails means something is actually wrong.
  `AuthError` is re-thrown through it: a dead sign-in must stop the run, not be
  reported as four thousand misses.
- **The APK's server binds loopback and has no switch to widen it.** A Qobuz
  access token and write access to somebody's library are behind it.
  Do not add a LAN option — now that it can also EMPTY a library, least of
  all.
- **The sign-in opens in a real browser on Android, never the WebView.** A WebView
  does not share the browser's cookies, so signing in there means typing a
  password into a window this app controls — the thing the redirect flow exists
  to avoid — and providers may refuse embedded WebViews outright.
- **A PAGER MAPS EACH PAGE AS IT GOES.** `pageAll` takes a mapper, and that is not a convenience — it is what
  stops a walk holding the whole library's raw JSON at once. Spotify's
  `/me/albums` returns the FULL album object, which carries
  `available_markets` (about 180 country codes) **on the album and on every
  one of its tracks**: roughly 15KB of JSON per saved album, several times
  that once parsed. Collecting the raw pages and mapping afterwards peaked at
  **187MB for 1,500 albums** in `ClientsTest`, so a library of a couple of
  thousand exhausts the APK's 256MB heap — which is exactly how 0.2.3 died on
  a real phone, before it had matched a single album:
  `OutOfMemoryError … growth limit 268435456; giving up on allocation because
  <1% of heap free after GC`. Mapping inside the loop keeps ONE page live: an
  `Album` is a few hundred bytes against fifty-odd kilobytes of JSON.
  The heap-growth test that guarded it went with the Spotify client; Qobuz's
  favourites are far leaner, but keep the mapper.
- **An Error is not an Exception, and `catch (e: Exception)` does not catch
  one.** An `OutOfMemoryError`, a `StackOverflowError` or a missing class
  sails straight through, kills the worker thread, and Android's default
  handler then kills the PROCESS: the app vanishes, the job row says
  "running" for ever, and there is nothing at all to say why. `MigrateApi
  .runJob` catches `Throwable` and records it as a failed run naming the
  Error; it deliberately does not rethrow, because rethrowing puts the process
  back where it was. **A crash with no evidence costs a release to find.**
- **The app asks Android why it died last time.** `getHistoricalProcessExit
  Reasons` (API 30+) in `MainActivity.reportLastExit` keeps the record —
  including the stack trace for a Java crash or an ANR — and writes it to
  `Downloads/mandarin-migration-crash.txt` with a toast. On a phone there is no adb and no
  logcat, so without this "it crashed" is the entire bug report. Only an
  abnormal end is reported (a swipe-away is not a fault), and the timestamp
  already reported is remembered so one crash is reported once.
  **And two records of different deaths must not read as one**: our saved
  trace survives until the next launch, so if the app died again before then,
  Android's header and our stack are hours apart — a real report from a phone
  had 13:38:43 underneath a header saying 16:38:45. They are joined only when
  the timestamps agree, and labelled as separate when they do not.
- **A `dataSync` foreground service gets SIX HOURS in any 24, and then the
  system kills the process.** Android 14 caps it; at the cap it calls
  `Service.onTimeout` and throws
  `ForegroundServiceDidNotStopInTimeException` moments later if the service
  has not stepped down. v0.2.5 went foreground in `onStartCommand` and stayed
  there for the whole life of the app, so the cap was *certain* to be reached
  — and it was, on a real phone, in the middle of nothing at all. A migration
  takes minutes, not hours, so the service is now in the foreground only
  while a run is in flight: `:core` cannot touch a `Service`, so `MigrateApi`
  reports busy transitions through `onBusyChanged` (edge-triggered — calling
  `startForeground` on every poll re-posts the notification) and
  `MigrateService` decides what that means. Both `onTimeout` overloads are
  implemented (one argument in API 34, two in API 35) and both call
  `cancelCurrentJob()`, so the job row ends as **cancelled** instead of
  sitting at "running" about a process that no longer exists. Start the
  service with `startService`, never `startForegroundService`: the latter
  demands a `startForeground` within five seconds and there is nothing to
  protect yet.
- **A Qobuz `user_auth_token` belongs to the app that minted it.** The `app_id`
  and the token move together; presenting a mismatched pair is a 401 that reads
  exactly like an expired sign-in.
- **Guard CSV cells that start with `=`, `+`, `-` or `@`.** A spreadsheet
  executes them as formulas, and track titles beginning with `-` are not rare.
  `csvCell` in `api/ApiText.kt` prefixes an apostrophe.

## Errors must be loud enough to notice

`try { … } catch (e) {}` around something the app depends on is how features
fail invisibly for releases at a time. When guarding something genuinely
optional, make sure the app really works without it — and do not let unrelated
features depend on the thing being guarded. Every deliberate empty catch in
this repository carries a comment saying why.

## The honesty rule about Android code

`:core` is a plain Kotlin/JVM module and is properly tested — the matching,
both API clients, the migration and clear-out engines, the HTTP server and the
whole route table, including over a real socket.

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
for f in app.js run.js clear.js; do unzip -p <apk> assets/web/$f | cmp - public/$f; done
```

### Driving the page

The page can be driven for real without a phone: a throwaway Gradle build
that includes `android/core` can run `HttpServer` + `MigrateApi` over a
`MemoryStore`, with assets read straight from `public/` and a fake `Http`
standing in for Qobuz, and Playwright's Chromium (pre-installed in the cloud
sandbox) can then click through it — including `setInputFiles` on the import
card. That is how the 0.4.0 clear-out flow and the 0.5.0 import (choose a
file, summary, preview, import, report) were checked end to end. It is not a
WebView, so it does not replace the emulator for anything in `app/`.

"The string is in the file" and "the component is declared with the right
intent-filter" are different claims.

## Qobuz, and being straight about it

The Qobuz API is unofficial and using it is against Qobuz's terms of service.
That is stated in `QobuzClient.kt`, in the README above the
install instructions, and in the footer of the page itself — **where someone
reads it before installing rather than after.** Keep it that way. Do not
soften it, and do not move it somewhere less visible.

Do not add anything that fetches audio. Nothing in a migration needs it, and
shipping the machinery for it would invite the question of why it exists.
`MusicD-Remote` has that code (`lib/qobuz-sig.js`) and it is deliberately not
ported here.

## Ported files

- The Qobuz sign-in (`QobuzOAuth` in `Pkce.kt`) — from
  `meltface-80/MusicD-Remote`, via this repository's former Node server.
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
- Bump **both** `versionName` and `versionCode` in
  `android/app/build.gradle.kts` for any APK
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
  cannot install over the existing app. (It was changed once, in 0.4.0, only
  because the original was lost — and that cost every user a reinstall.) The secrets keep their `MUSICD_*`
  names and the key its `musicd` alias through the rename — renaming them
  would stop CI publishing.
- Ask before guessing when a choice is the user's to make. A destructive
  default, a corner, a layout — ask, do not assume and apologise later.
