package com.musicd.migrate.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.musicd.migrate.api.MigrateApi
import com.musicd.migrate.http.HttpServer

/**
 * The app, as a process that keeps running.
 *
 * WHY A FOREGROUND SERVICE AND NOT JUST AN ACTIVITY. A migration of a real
 * library takes minutes: thousands of searches against two rate-limited
 * services, and every 429 is waited out rather than hammered through. During
 * that time the person will lock their phone or answer a message, and a plain
 * activity's process is killable the moment it leaves the screen — which would
 * abandon the run halfway with no way to tell them why. The notification is
 * the honest price of that: Android requires one, and it is right that
 * something writing to somebody's music library says so while it works.
 *
 * The HTTP server and the API live here rather than in the activity for the
 * same reason: the WebView reconnects to a server that never went away when
 * the app comes back to the foreground.
 */
class MigrateService : Service() {

    companion object {
        private const val CHANNEL = "migrate"
        private const val NOTIFICATION_ID = 1

        /** The port the WebView will be pointed at. Fixed rather than
         *  ephemeral so a restarted service does not strand a page that is
         *  already loaded from the old one. */
        private const val PORT = 3380

        @Volatile private var instance: MigrateService? = null

        /** The running server's address, or null before it has started. */
        fun rootUrl(): String? = instance?.server?.rootUrl

        fun start(context: Context) {
            val intent = Intent(context, MigrateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var server: HttpServer? = null
    private var api: MigrateApi? = null
    private var store: SqliteStore? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        val s = SqliteStore(applicationContext)
        store = s
        val a = MigrateApi(s, AndroidAssets(applicationContext),
            version = BuildConfig.VERSION_NAME,
            roonDiscovery = com.musicd.migrate.roon.SoodDiscovery(
                multicastLock = WifiMulticastLock(applicationContext)))
        api = a

        // Loopback only, and there is deliberately no switch to widen it:
        // behind this socket are two services' access tokens and the ability
        // to write to somebody's music library, and nothing else on the
        // network has any reason to reach a migration running on this phone.
        val srv = HttpServer({ req -> a.handle(req) }, PORT)
        server = srv
        srv.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        // START_STICKY: if Android kills the process under memory pressure,
        // bring it back. A migration in flight is NOT resumed — the job is
        // marked interrupted on the next start and is re-runnable by design,
        // which is far better than a half-written playlist nobody is told
        // about.
        return START_STICKY
    }

    override fun onDestroy() {
        api?.shutdown()
        server?.stop()
        store?.close()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}

/**
 * A WifiManager multicast lock, held while Roon discovery listens.
 *
 * Android filters multicast out of userspace unless an app holds one of these,
 * so without it the SOOD replies never arrive and discovery reports no Roon
 * Core on a network that has one — which sends the user to look at their Wi-Fi
 * for a problem that is in this app. Android-Random-Remote needed exactly the
 * same thing.
 *
 * Reference-counted because a second discovery round must not release the
 * lock the first one is still using.
 */
private class WifiMulticastLock(context: Context) : com.musicd.migrate.roon.MulticastLock {
    private val lock = (context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
        ?.createMulticastLock("musicd-migrate-roon")
        ?.also { it.setReferenceCounted(true) }

    override fun acquire() {
        // Guarded because a device with Wi-Fi off, or an emulator image
        // without the service, has no WifiManager — and Roon discovery over
        // Ethernet still works without the lock. The app must not fail to
        // start over it.
        runCatching { lock?.acquire() }
    }

    override fun release() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }
}
