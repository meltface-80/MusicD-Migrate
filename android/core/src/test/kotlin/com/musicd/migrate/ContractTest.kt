package com.musicd.migrate

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/*
 * ContractTest.kt — the checks that stop the page and the server drifting
 * apart.
 *
 * public/app.js has no build step and is bundled into the APK as-is, where
 * MigrateApi.kt answers it. Nothing compiles the two together, so a route the
 * page calls and the server does not serve, or an option the page sends and
 * the server never reads, is a bug that only shows on a phone. These tests
 * read the actual source files and fail on exactly that.
 */
class ContractTest {

    /** The repository root, found by walking up to the page and the build. */
    private fun repoRoot(): File? {
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            if (dir != null && File(dir, "public/app.js").isFile &&
                File(dir, "android/settings.gradle.kts").isFile) return dir
            dir = dir?.parentFile
        }
        return null
    }

    /** Every script the page loads, as one text: the page is split into
     *  app.js, run.js and clear.js, and a route may be called from any. */
    private fun pageScripts(root: File): String =
        File(root, "public").listFiles { f -> f.extension == "js" }!!
            .sortedBy { it.name }.joinToString("\n") { it.readText() }

    private fun apiKt(root: File) = File(root,
        "android/core/src/main/kotlin/com/musicd/migrate/api/MigrateApi.kt").readText()

    /**
     * Every /api/ path the bundled page asks for is served.
     *
     * The page is the authority, not MigrateApi.kt. Eleven wire-contract bugs
     * in MusicD Remote Lite came from porting a server's names rather than
     * reading what the page actually calls.
     */
    @Test fun `every route the page calls is served`() {
        val root = repoRoot()
        // Run from a published source tree only. A consumer building :core on
        // its own has no page to compare against, and a test that fails for
        // that reason is noise rather than a finding.
        assumeTrue("not running from the repository", root != null)

        val appJs = pageScripts(root!!)
        val apiKt = apiKt(root)

        val literals = Regex("""["'](/api/[A-Za-z0-9/._-]*)""").findAll(appJs)
            .map { it.groupValues[1] }
            .map { it.trimEnd('/') }
            .filter { it.length > 5 }
            .toSortedSet()

        assertTrue("the page should call a good few routes, found $literals",
            literals.size >= 8)
        assertTrue("the page should upload a library file", literals.contains("/api/import/file"))
        assertTrue("the page should call the clear-out route", literals.contains("/api/clear"))

        assertEquals("routes the page calls but the APK does not serve",
            emptyList<String>(), literals.filter { !apiKt.contains("\"$it") })

        // The three sub-paths the page builds by concatenation rather than as
        // one literal, so the regex above cannot see them. MigrateApi strips
        // the job id first and matches the bare tail.
        for (tail in listOf("cancel", "items", "report.csv")) {
            assertTrue("the page builds /$tail", appJs.contains("/$tail"))
            assertTrue("the APK serves $tail", apiKt.contains("\"$tail\""))
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
     * Every option the page SENDS is read by the server — for a migration and
     * for a clear-out.
     *
     * The consequence is not cosmetic. If the server misread `dryRun`, a run
     * the user asked to PREVIEW would write to their library — or, for a
     * clear-out, EMPTY it — and it would look like the preview had simply
     * worked. A silently ignored `confirm` would be worse still. These are the
     * highest-consequence wire contracts in the app, so they are tests.
     *
     * Subset, not equality: the handler may legitimately read more than the
     * page sends (it reads `concurrency`, which the page leaves to the
     * default).
     */
    @Test fun `the server reads every migrate and clear option the page sends`() {
        val root = repoRoot()
        assumeTrue("not running from the repository", root != null)

        val appJs = pageScripts(root!!)
        val apiKt = apiKt(root)

        fun sentIn(varName: String): Set<String> {
            val block = Regex("""var $varName = \{(.*?)\n\s*\};""", RegexOption.DOT_MATCHES_ALL)
                .find(appJs)?.groupValues?.get(1)
                ?: throw AssertionError("could not find `var $varName = {…};` in public/*.js")
            return Regex("""(?m)^\s+([a-zA-Z]+):""").findAll(block)
                .map { it.groupValues[1] }.toSortedSet()
        }

        fun readBy(funName: String): Set<String> {
            val handler = Regex("""private fun $funName\(req: Request\).*?\n    \}""",
                RegexOption.DOT_MATCHES_ALL).find(apiKt)?.value
                ?: throw AssertionError("could not find $funName() in MigrateApi.kt")
            return Regex("""b\.(?:opt|str|intOrNull|optBoolean)[A-Za-z]*\("([a-zA-Z]+)"""")
                .findAll(handler).map { it.groupValues[1] }.toSortedSet()
        }

        val migrate = sentIn("body")
        assertTrue("the page should send the import options, found $migrate", migrate.size >= 4)
        assertTrue("dryRun is the one that must never be missed", migrate.contains("dryRun"))
        assertEquals("migrate options the page sends that the APK never reads",
            emptyList<String>(), (migrate - readBy("migrate")).toList())

        val clear = sentIn("clearBody")
        for (must in listOf("service", "dryRun", "confirm")) {
            assertTrue("the clear-out must send $must, found $clear", clear.contains(must))
        }
        assertEquals("clear options the page sends that the APK never reads",
            emptyList<String>(), (clear - readBy("clear")).toList())
    }
}
