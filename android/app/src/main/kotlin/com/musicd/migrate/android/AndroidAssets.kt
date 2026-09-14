package com.musicd.migrate.android

import android.content.Context
import com.musicd.migrate.http.Assets

/**
 * The bundled page, read from the APK.
 *
 * The files under assets/web/ are the repository's own public/ directory,
 * staged there by the stageWebAssets Copy task — the identical HTML, CSS and
 * JavaScript the Docker build serves, not a copy kept in step by hand.
 */
class AndroidAssets(context: Context) : Assets {
    private val assets = context.assets

    override fun read(path: String): ByteArray? = try {
        assets.open(path).use { it.readBytes() }
    } catch (e: Exception) {
        // A missing asset is an ordinary 404 — the page asks for a favicon
        // variant or a source map that is not bundled — and must not be
        // louder than that.
        null
    }
}
