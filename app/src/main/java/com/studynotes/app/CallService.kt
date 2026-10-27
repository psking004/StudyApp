package com.studynotes.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps mic capture alive while a call is connected and the app is in the background or the screen is locked.
 *
 * Android stops microphone capture for a backgrounded app unless a foreground service with the "microphone"
 * foreground-service type is running, which is why the other side went silent once the user left the app or
 * locked the phone. The service is started while the app is still in the foreground (from the moment a call
 * becomes active), because Android 12+ forbids starting a foreground service from the background and Android 14+
 * additionally requires the mic type to be started from a foreground state.
 *
 * Disguise (unchanged app-wide rule): the ongoing notification this service must post looks exactly like the rest
 * of Study Notes. Generic notes icon, "Study Notes" / "Syncing notes", no call wording, no caller or contact name,
 * no call timer or chronometer, no CallStyle, no CATEGORY_CALL, no actions. Silent, minimum importance, and hidden
 * on the lock screen (VISIBILITY_SECRET, with an equally generic public version). Tapping it opens MainActivity,
 * which always lands on the locked study-notes screen through the existing privacy lock.
 */
class CallService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (_: Exception) {
            // Foreground start refused (e.g. a race with the app leaving the foreground). The call itself keeps
            // running; only the background-mic guarantee is lost, exactly as before this fix.
            stopSelfSafely()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    /** Keeps the CPU running so WebRTC audio keeps flowing with the screen off. Never keeps the screen on. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "studynotes:sync").apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notes)
            .setContentTitle(SERVICE_TITLE).setContentText(SERVICE_BODY)
            .build()
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notes)
            .setContentTitle(SERVICE_TITLE)
            .setContentText(SERVICE_BODY)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(public)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val NOTIF_ID = 9
        const val ACTION_STOP = "com.studynotes.app.STOP_SYNC"

        /** Nothing here hints at a call: a generic Study Notes background-sync notice. */
        const val SERVICE_TITLE = "Study Notes"
        const val SERVICE_BODY = "Syncing notes"
        const val SERVICE_CHANNEL = "study_sync"

        private fun createChannel(ctx: Context) {
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                // Android 8+: a silent, minimum-importance channel so the ongoing notice never makes a sound,
                // never peeks, carries no badge, and is hidden on the lock screen.
                nm.createNotificationChannel(
                    NotificationChannel(SERVICE_CHANNEL, "Sync", NotificationManager.IMPORTANCE_MIN).apply {
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                        description = "Keeps your notes in sync"
                    }
                )
            } catch (_: Exception) { }
        }

        /**
         * Starts the ongoing sync service. Must be called while the app is still in the foreground
         * (Android 12+ background-start restriction, Android 14+ mic-type restriction).
         */
        fun start(ctx: Context) {
            try {
                val c = ctx.applicationContext
                createChannel(c)
                val i = Intent(c, CallService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            try {
                val c = ctx.applicationContext
                c.stopService(Intent(c, CallService::class.java))
            } catch (_: Exception) { }
        }
    }
}
