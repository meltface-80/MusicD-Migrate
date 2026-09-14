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
}
