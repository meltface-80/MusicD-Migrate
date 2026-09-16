package com.musicd.migrate

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/*
 * ContractTest.kt — the two checks that stop this repository's two halves
 * drifting apart.
 *
 * public/app.js is written ONCE and shipped to BOTH the Docker build and the
 * APK. So a route added to index.js and not to MigrateApi.kt, or a field
 * renamed on one side, is a bug that only shows on a phone — where nobody is
 * looking, because everything worked fine in the container. These tests read
 * the actual source files and fail on exactly that.
 */
class ContractTest {

    /** The repository root, found by walking up to the package.json. */
    private fun repoRoot(): File? {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            if (dir != null && File(dir, "package.json").isFile &&
                File(dir, "public/app.js").isFile) return dir
            dir = dir?.parentFile
        }
        return null
    }

    /**
     * Every /api/ path the bundled page asks for exists on BOTH servers.
     *
     * The page is the authority — not index.js and not MigrateApi.kt. Eleven
     * wire-contract bugs in MusicD Remote Lite came from porting a server's
     * names rather than reading what the page actually calls.
     */
    @Test fun `every route the page calls exists in both servers`() {
        val root = repoRoot()
        // Run from a published source tree only. A consumer building :core on
        // its own has no index.js to compare against, and a test that fails
        // for that reason is noise rather than a finding.
        assumeTrue("not running from the repository", root != null)

        val appJs = File(root, "public/app.js").readText()
        val indexJs = File(root, "index.js").readText()
        val apiKt = File(root, "android/core/src/main/kotlin/com/musicd/migrate/api/" +
            "MigrateApi.kt").readText()

        val literals = Regex("""["'](/api/[A-Za-z0-9/._-]*)""").findAll(appJs)
            .map { it.groupValues[1] }
            .map { it.trimEnd('/') }
            .filter { it.length > 5 }
            .toSortedSet()

        assertTrue("the page should call a good few routes", literals.size >= 10)

        val missingNode = literals.filter { !indexJs.contains(it) }
        val missingApk = literals.filter { !apiKt.contains(it) }
        assertEquals("routes the page calls but index.js does not serve",
            emptyList<String>(), missingNode)
        assertEquals("routes the page calls but the APK does not serve",
            emptyList<String>(), missingApk)

        // The three sub-paths the page builds by concatenation rather than as
        // one literal, so the regex above cannot see them.
        //
        // Either spelling counts. Express registers them with the leading
        // slash ("/api/job/:id/cancel") while MigrateApi strips the job id
        // first and matches the bare tail ("cancel"), so insisting on one
        // spelling would fail on a route both servers do in fact serve — which
        // is what the first version of this test did.
        for (tail in listOf("cancel", "items", "report.csv")) {
            assertTrue("index.js serves $tail",
                indexJs.contains("/$tail") || indexJs.contains("\"$tail\""))
            assertTrue("the APK serves $tail",
                apiKt.contains("/$tail") || apiKt.contains("\"$tail\""))
        }
    }

    /**
     * optString must not come back.
     *
     * Android's org.json returns the LITERAL string "null" for a JSON null,
     * where the desktop implementation returns "". Every test in this module
     * runs against the desktop one, so this class of bug is invisible here and
     * shows up only on a phone: a null `isrc` read as "null" would be SEARCHED
     * FOR on the other service, and a null album name would become an album
     * called "null" that the tie-break then scores against.
     *
     * Json.kt is the one file allowed to name it, because that is where the
     * safe readers are built.
     */
    @Test fun `no optString outside Json kt`() {
        val root = repoRoot()
        assumeTrue("not running from the repository", root != null)
        val srcDir = File(root, "android/core/src/main/kotlin")
        assumeTrue("no sources to scan", srcDir.isDirectory)

        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Json.kt" }
            .filter { it.readText().contains("optString") }
            .map { it.name }
            .toList()

        assertEquals("optString is unsafe on Android — use str()/strOrNull() from Json.kt",
            emptyList<String>(), offenders)
    }

    /**
     * The baked-in Client ID is the same on both sides.
     *
     * A mismatch would mean the phone and the container sign in as two
     * DIFFERENT applications, and the failure is not obvious from either end:
     * the id that starts a sign-in has to be the id that redeems the code, and
     * Spotify's complaint when they differ is about the code, not about the id.
     * It also has to match the one written down in the README, which is where
     * the owner goes looking when a sign-in stops working.
     */
    @Test fun `the baked-in Spotify Client ID matches the JavaScript one`() {
        val root = repoRoot()
        assumeTrue("not running from the repository", root != null)

        val pkceJs = File(root, "lib/spotify-pkce.js").readText()
        val js = Regex("const DEFAULT_CLIENT_ID = \"([^\"]*)\";")
            .find(pkceJs)?.groupValues?.get(1)
            ?: throw AssertionError("could not find DEFAULT_CLIENT_ID in lib/spotify-pkce.js")

        assertEquals("lib/spotify-pkce.js and Pkce.kt must sign in as the same application",
            js, Pkce.DEFAULT_CLIENT_ID)
        assertTrue("a Spotify Client ID is 32 hex characters, not ${Pkce.DEFAULT_CLIENT_ID}",
            Regex("^[0-9a-f]{32}$").matches(Pkce.DEFAULT_CLIENT_ID))

        val readme = File(root, "README.md").readText()
        assertTrue("the README appendix must quote the id the code actually uses",
            readme.contains(Pkce.DEFAULT_CLIENT_ID))
    }

    /**
     * The edition-word list is the same on both sides.
     *
     * It is the single most behaviour-defining constant in the app: it decides
     * which suffixes are an edition (safe to match through) and which name a
     * different recording (never matched through). A word added to one list
     * and not the other means the APK and the container migrate different
     * tracks, silently.
     */
    @Test fun `the edition words match the JavaScript list`() {
        val root = repoRoot()
        assumeTrue("not running from the repository", root != null)
        val canonJs = File(root, "lib/canon.js").readText()

        val jsList = Regex("""const EDITION_WORDS = \[(.*?)];""", RegexOption.DOT_MATCHES_ALL)
            .find(canonJs)?.groupValues?.get(1)
            ?: throw AssertionError("could not find EDITION_WORDS in lib/canon.js")
        val jsWords = Regex(""""([^"]+)"""").findAll(jsList)
            .map { it.groupValues[1] }.toSortedSet()

        assertEquals("lib/canon.js and Canon.kt must agree on what an edition suffix is",
            jsWords, Canon.EDITION_WORDS.toSortedSet())
    }
    /**
     * Every option the page SENDS to /api/migrate is read by BOTH servers.
     *
     * ContractTest already checks that the routes match. It did not check the
     * FIELDS inside a request body, and CLAUDE.md said to keep those in step by
     * hand — which is exactly the kind of instruction that holds until it
     * doesn't.
     *
     * The consequence is not cosmetic. If one server misread `dryRun`, a run
     * the user asked to PREVIEW would write to their library instead, and it
     * would look like the preview had simply worked. A silently ignored
     * `strict` would loosen matching without being asked. This is the highest-
     * consequence wire contract in the app, so it is now a test.
     *
     * Subset, not equality: a handler may legitimately read more than the page
     * sends (both read `concurrency`, which the page leaves to the default).
     */
    @Test fun `both servers read every migrate option the page sends`() {
        val root = repoRoot()
        assumeTrue("not running from the repository", root != null)

        val appJs = File(root, "public/app.js").readText()
        val indexJs = File(root, "index.js").readText()
        val apiKt = File(root, "android/core/src/main/kotlin/com/musicd/migrate/api/" +
            "MigrateApi.kt").readText()

        // The object literal the page POSTs, and the keys in it.
        val bodyBlock = Regex("""var body = \{(.*?)\n\s*\};""", RegexOption.DOT_MATCHES_ALL)
            .find(appJs)?.groupValues?.get(1)
            ?: throw AssertionError("could not find the /api/migrate body in public/app.js")
        val sent = Regex("""(?m)^\s+([a-zA-Z]+):""").findAll(bodyBlock)
            .map { it.groupValues[1] }.toSortedSet()

        assertTrue("the page should send a good few options, found $sent", sent.size >= 8)
        assertTrue("dryRun is the one that must never be missed", sent.contains("dryRun"))

        // What each server actually reads out of that body.
        val nodeHandler = Regex("""app\.post\("/api/migrate".*?res\.json\(\{ jobId \}\)""",
            RegexOption.DOT_MATCHES_ALL).find(indexJs)?.value
            ?: throw AssertionError("could not find the /api/migrate handler in index.js")
        val readByNode = Regex("""body\.([a-zA-Z]+)""").findAll(nodeHandler)
            .map { it.groupValues[1] }.toSortedSet()

        val ktHandler = Regex("""private fun migrate\(req: Request\).*?\n    \}""",
            RegexOption.DOT_MATCHES_ALL).find(apiKt)?.value
            ?: throw AssertionError("could not find migrate() in MigrateApi.kt")
        val readByKt = Regex("""b\.(?:opt|str|intOrNull|optBoolean)[A-Za-z]*\("([a-zA-Z]+)"""")
            .findAll(ktHandler).map { it.groupValues[1] }.toSortedSet()

        assertEquals("options the page sends that index.js never reads",
            emptyList<String>(), (sent - readByNode).toList())
        assertEquals("options the page sends that the APK never reads",
            emptyList<String>(), (sent - readByKt).toList())
    }

    /**
     * The read/write split is the same split on both sides.
     *
     * MusicSource and MusicTarget in Model.kt, and SOURCE_METHODS and
     * TARGET_METHODS in lib/service.js, are the same two lists written twice.
     * They decide one thing: whether a service can be migrated INTO. Roon
     * cannot — a Roon library is files on a disk — and the consequence of one
     * side thinking otherwise is not a crash, it is a run that reports every
     * album as not found, because a failed search is deliberately counted as
     * one unmatched item rather than an error.
     *
     * So the lists are compared, exactly as the edition words are.
     */
    @Test fun `the source and target method lists match the JavaScript ones`() {
        val root = repoRoot()
        assumeTrue("not running from the repository", root != null)
        val modelKt = File(root, "android/core/src/main/kotlin/com/musicd/migrate/Model.kt")
            .readText()
        val serviceJs = File(root, "lib/service.js").readText()

        fun ktBody(name: String): String =
            Regex("""interface $name[^{]*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
                .find(modelKt)?.groupValues?.get(1)
                ?: throw AssertionError("could not find interface $name in Model.kt")

        fun funs(body: String) = Regex("""(?m)^\s+fun ([A-Za-z]+)\(""")
            .findAll(body).map { it.groupValues[1] }.toSortedSet()

        fun jsList(name: String): Set<String> {
            val block = Regex("""const $name = [^;]*?\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(serviceJs)?.groupValues?.get(1)
                ?: throw AssertionError("could not find $name in lib/service.js")
            return Regex(""""([A-Za-z]+)"""").findAll(block)
                .map { it.groupValues[1] }.toSortedSet()
        }

        val ktSource = funs(ktBody("MusicSource"))
        val ktTarget = funs(ktBody("MusicTarget"))

        assertTrue("MusicSource should declare a handful of reads, found $ktSource",
            ktSource.size >= 6)
        assertTrue("MusicTarget should declare the searches and the writes, found $ktTarget",
            ktTarget.size >= 9)

        assertEquals("MusicSource and SOURCE_METHODS in lib/service.js must agree",
            jsList("SOURCE_METHODS"), ktSource)
        // TARGET_METHODS is SOURCE_METHODS plus the rest, and MusicTarget
        // inherits MusicSource, so the union is what has to match.
        assertEquals("MusicTarget and TARGET_METHODS in lib/service.js must agree",
            jsList("SOURCE_METHODS") + jsList("TARGET_METHODS"),
            (ktSource + ktTarget).toSortedSet())
    }

}
