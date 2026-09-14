package com.musicd.migrate.android

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.app.Activity

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
        }

        setContentView(web)

        load()
    }

    private fun isOurs(uri: Uri): Boolean =
        uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")

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
