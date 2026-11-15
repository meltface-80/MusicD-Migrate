package com.musicd.migrate.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast

/**
 * The file picker behind the import card's "Choose a file…".
 *
 * A WebView does NOTHING with <input type="file"> unless the app says what to
 * do — the same trap the CSV download fell into, dead from v0.1.0 until
 * somebody tried it. Without this, choosing a library file silently does
 * nothing, and a library file is the only way into Qobuz this app has.
 *
 * The callback MUST be answered every time, with null when the user cancels:
 * one left hanging and the WebView never opens a picker again for the life of
 * the page.
 */
class FilePicker(private val activity: Activity) {

    private var pending: ValueCallback<Array<Uri>>? = null

    val client = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
        ): Boolean {
            pending?.onReceiveValue(null)
            pending = callback
            // Any type: file managers label a CSV as text/csv,
            // text/comma-separated-values, application/vnd.ms-excel or
            // application/octet-stream, and filtering on one of them hides the
            // file. The server judges the contents and says what it found.
            val pick = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            return try {
                activity.startActivityForResult(
                    Intent.createChooser(pick, "Choose a library file"), REQUEST)
                true
            } catch (e: ActivityNotFoundException) {
                pending = null
                Toast.makeText(activity, "No app on this phone can pick a file.",
                    Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    /** From Activity.onActivityResult. True when the result was the picker's. */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST) return false
        val callback = pending
        pending = null
        // null on a cancel, which is what tells the page nothing was chosen.
        callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        return true
    }

    private companion object {
        const val REQUEST = 41
    }
}
