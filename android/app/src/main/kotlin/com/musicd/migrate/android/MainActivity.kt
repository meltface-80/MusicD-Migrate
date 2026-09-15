package com.musicd.migrate.android

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import android.app.Activity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The whole user interface: a WebView pointed at this app's own server.
 *
 * The page is the repository's public/ directory, served over real HTTP from
 * 127.0.0.1 by MigrateService. Intercepting requests inside the WebView
 * instead would avoid the socket, but shouldInterceptRequest is never handed
 * the BODY of a POST — and this app POSTs to sign in, to start a migration and
 * to cancel one. Serving it properly means the page runs byte-identical to the
 * browser version, and a change to the front-end is a file copy rather than a
 * merge.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MigrateService.start(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true   // the page remembers a PIN there
            settings.mediaPlaybackRequiresUserGesture = false
            // Nothing here loads a file:// or content:// URL, and turning
            // these off costs nothing. The page is served over http from
            // loopback.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = false // it is our own loopback
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            webViewClient = object : WebViewClient() {
                /**
                 * The sign-ins happen on qobuz.com and accounts.spotify.com,
                 * and they must open in a REAL BROWSER, not in here.
                 *
                 * Two reasons, and the second is the important one. A WebView
                 * does not share the browser's cookies, so signing in here
                 * means typing a password into a window the app controls —
                 * which is exactly what the redirect flow exists to avoid.
                 * And Google explicitly blocks OAuth in embedded WebViews for
                 * that reason, so a provider is entitled to refuse it outright.
                 *
                 * The redirect comes back to http://127.0.0.1:3380/…, which
                 * the browser can reach because it is on the same phone — and
                 * the callback page tells this window to refresh.
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    if (isOurs(url)) return false
                    openExternally(url)
                    return true
                }
            }

            /**
             * A WebView does NOTHING with a download unless the app says what
             * to do with it.
             *
             * Both CSV links on the page — the migration report and the Roon
             * library — are ordinary <a download> links to this app's own
             * server, and the server answers with Content-Disposition:
             * attachment. In a browser that saves a file. In a WebView with no
             * DownloadListener, tapping it silently does nothing at all, which
             * is how both buttons were dead from v0.1.0 until somebody tried
             * them. The report is the actual output of a migration, so this is
             * not a cosmetic gap: it is the one feature the whole run exists
             * to produce.
             */
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val name = URLUtil.guessFileName(url, contentDisposition,
                    mimeType ?: "text/csv")
                saveDownload(url, name)
            }
        }

        setContentView(web)

        load()
    }

    private fun isOurs(uri: Uri): Boolean =
        uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")

    /**
     * Fetch a download from this app's own server and put it somewhere the
     * user can reach.
     *
     * Fetched in-process rather than handed to DownloadManager: the URL is
     * loopback and may carry a PIN in its query, and DownloadManager is a
     * different process with its own idea of both. It is our own server, two
     * milliseconds away, and the file is a few hundred kilobytes of CSV.
     *
     * Where it lands depends on the Android version, and both branches are
     * deliberate:
     *   - API 29+: MediaStore's Downloads collection, which is the real
     *     Downloads folder and needs NO permission.
     *   - Below that: this app's own external files directory. Scoped storage
     *     did not exist yet, so a file manager on those versions can open
     *     Android/data freely — and the alternative would be a runtime
     *     storage permission, or a ContentProvider written by hand, because
     *     this app pulls in no AndroidX and so has no FileProvider.
     *
     * Either way the toast says where it went, because a download the user
     * cannot find is the same as no download.
     */
    private fun saveDownload(url: String, name: String) {
        Thread({
            // The failure is REPORTED, not swallowed: a download that quietly
            // does nothing is exactly the bug this method exists to fix, and
            // catching the exception to show nothing would reproduce it.
            var problem: String? = null
            val result = try {
                val bytes = fetch(url)
                if (bytes == null) null else store(name, bytes)
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
                null
            }
            runOnUiThread {
                Toast.makeText(this,
                    result ?: ("Could not save " + name +
                        if (problem != null) ": $problem" else "."),
                    Toast.LENGTH_LONG).show()
            }
        }, "download").start()
    }

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** @return what to tell the user, or null if it could not be written. */
    private fun store(name: String, bytes: ByteArray): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            }
            val uri = contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            return "Saved to Downloads/$name"
        }
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        return "Saved to ${file.absolutePath}"
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // No browser at all is an odd phone, but it must not crash the app.
        }
    }

    /**
     * Point the WebView at the server.
     *
     * The service starts the socket on its own thread, so on a cold launch the
     * address can be a few milliseconds away. Retrying briefly rather than
     * showing an error is the difference between "it works" and "it sometimes
     * shows a blank page on the first open" — which is the kind of bug that
     * only ever reproduces on somebody else's phone.
     */
    private fun load(attempt: Int = 0) {
        val url = MigrateService.rootUrl()
        if (url != null) {
            web.loadUrl(url)
            return
        }
        if (attempt > 40) {   // ~4 seconds
            web.loadData(
                "<p style='font:16px system-ui;padding:24px'>" +
                    "MusicD Migrate could not start its local server. " +
                    "Close the app and open it again.</p>",
                "text/html", "utf-8")
            return
        }
        web.postDelayed({ load(attempt + 1) }, 100)
    }

    /**
     * Back goes back inside the page first, and only leaves the app once the
     * WebView has nowhere left to go.
     *
     * onKeyDown rather than onBackPressed, and rather than an
     * OnBackPressedDispatcher callback. The dispatcher lives in
     * androidx.activity and this app pulls in no AndroidX at all;
     * onBackPressed is deprecated. This is neither, works on every version
     * this app supports, and stays correct as long as predictive back is not
     * enabled — which it is not, because the manifest does not set
     * android:enableOnBackInvokedCallback.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the browser after a sign-in: the page re-reads its
        // state on focus, and this makes sure it gets one.
        web.onResume()
    }

    override fun onDestroy() {
        // The service — and the migration in it — deliberately outlives this
        // activity. Only the view is torn down.
        web.destroy()
        super.onDestroy()
    }
}
