package com.studynotes.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

sealed class Screen {
    object Notes : Screen()
    data class EditNote(val id: Long?) : Screen()
    object Hidden : Screen()
}

class MainActivity : ComponentActivity() {
    private var foreground = false
    private var resumed = false
    private val lockHook: () -> Unit = { lock() }
    private val screen = mutableStateOf<Screen>(Screen.Notes)
    lateinit var repo: ChatRepo
    lateinit var secret: SecretStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secret = SecretStore(this)
        repo = App.repo(this) { notifyDisguised() }
        PrivacyLock.onLock = lockHook
        // Always start on the study-notes screen; the private area is never restored from saved state.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        createChannel()
        CallManager.onRinging = { notifyCall() }
        CallManager.onRingingStopped = { NotificationManagerCompat.from(this).cancel(8) }
        // Sign in quietly in the background so incoming messages/calls can ring even before chats are opened.
        lifecycleScope.launch { try { repo.start() } catch (_: Exception) { } }
        val notes = NotesStore(this)
        setContent {
            StudyTheme {
                val s by screen
                val inCall = CallManager.state.value != null
                LaunchedEffect(s, inCall) { updateSecure() }
                LaunchedEffect(s) {
                    while (s is Screen.Hidden) {
                        delay(1000)
                        // v1.4: the idle timer is paused during an active call so a call screen is never hidden by the timer.
                        // Background, Recents, screen-off and reopen still lock during a call (the call itself keeps running).
                        if (CallManager.active) { PrivacyLock.touch(); continue }
                        if (System.currentTimeMillis() - PrivacyLock.lastTouch >= PrivacyLock.IDLE_MS) lock()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                when (val cur = s) {
                    Screen.Notes -> NotesScreen(notes,
                        onOpen = { screen.value = Screen.EditNote(it) },
                        onSearch = { q -> if (secret.matches(q)) { PrivacyLock.touch(); askNotifPermission(); screen.value = Screen.Hidden; updateSecure(); true } else false })
                    is Screen.EditNote -> {
                        BackHandler { screen.value = Screen.Notes }
                        NoteEditor(notes, cur.id) { screen.value = Screen.Notes }
                    }
                    Screen.Hidden -> HiddenApp(repo, secret, onHide = { lock() })
                }
                // v1.4: calls never show while the chat section is locked. A ringing call waits in the background
                // (no screen, no sound) and its Accept/Decline screen appears once the passcode unlocks the chats, if it
                // is still ringing. A connected call keeps running while locked; its screen hides and returns on unlock.
                val call = CallManager.state.value
                if (s is Screen.Hidden && call != null) {
                    if (call.phase != CallPhase.Ended) CallManager.surfacedCallId = call.callId
                    // A call that ended while locked never flashes its "ended" banner after unlocking.
                    if (call.phase != CallPhase.Ended || CallManager.surfacedCallId == call.callId) CallOverlay(onHide = { lock() })
                }
                }
            }
        }
    }

    /** Relock: back to the notes screen, chats and call screen hidden. Never hangs up or declines a call. */
    private fun lock() {
        App.externalSince = 0
        VoicePlayer.stop()
        repo.openChat = null; repo.clearOpenedFiles(); screen.value = Screen.Notes
        updateSecure()
    }

    /**
     * FLAG_SECURE (blank Recents thumbnail, no screenshots) is added the moment the private area or a call is up, and is
     * only removed again while this activity is resumed and showing the notes screen. So whatever snapshot the system
     * takes on the way to Recents/Home is always a secure (blank) one, even if the relock hasn't been drawn yet.
     */
    private fun updateSecure() {
        val private = screen.value is Screen.Hidden || CallManager.active
        if (private || !resumed) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!private && resumed)
    }

    override fun onUserInteraction() { super.onUserInteraction(); PrivacyLock.touch() }
    override fun onStart() {
        super.onStart(); foreground = true; App.foreground = true
        // Reopened from Recents/launcher/notification: always the notes screen, unless we are just coming back from a
        // photo/file picker or save dialog the user opened inside a chat within the last minute.
        if (!PrivacyLock.externalActive()) lock()
        App.externalSince = 0
        PrivacyLock.touch()
    }
    override fun onResume() { super.onResume(); resumed = true; updateSecure() }
    override fun onPause() {
        // Before the Recents snapshot: make sure the window is secure while anything private could be on it.
        resumed = false; window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        super.onPause()
    }
    override fun onStop() {
        super.onStop(); foreground = false; App.foreground = false
        if (PrivacyLock.externalActive()) return // a picker/save dialog the user opened from a chat is on top; screen-off still locks
        lock()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Notification tap or launcher while running: always land on the locked notes screen, never inside a chat.
        lock()
    }
    override fun onDestroy() {
        if (PrivacyLock.onLock === lockHook) PrivacyLock.onLock = null
        super.onDestroy()
    }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // v1.5: new channel id, because an existing channel's lock-screen visibility can't be changed after creation.
        // Hidden on the lock screen entirely; no badge count; generic name.
        try { nm.deleteNotificationChannel("rem") } catch (_: Exception) { }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Study reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            setShowBadge(false)
            description = "Reminders to review your notes"
        })
    }

    /**
     * The only notification this app ever posts, for messages, voice messages and calls alike: a generic study reminder.
     * No sender, username, message text, attachment name or call type, in the notification, its public (lock screen)
     * version, or its ticker. Tapping it opens the app on the locked study-notes screen (onNewIntent/onCreate -> lock).
     */
    private fun disguisedNotification(requestCode: Int, heads: Boolean, timeoutMs: Long = 0): android.app.Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notes)
            .setContentTitle(NOTIF_TEXT).setContentText(NOTIF_BODY)
            .build()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notes)
            .setContentTitle(NOTIF_TEXT)
            .setContentText(NOTIF_BODY)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(public)
            .setPriority(if (heads) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) }
            .setContentIntent(pi).setAutoCancel(true).build()
    }

    private fun canNotify(): Boolean = secret.notificationsOn &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Incoming call while the app is in the background: the same generic reminder, no caller, no call type. */
    private fun notifyCall() {
        if (App.foreground || !canNotify()) return
        try { NotificationManagerCompat.from(this).notify(8, disguisedNotification(1, heads = true, timeoutMs = 45_000)) } catch (_: SecurityException) {}
    }

    private fun notifyDisguised() {
        if (App.foreground && screen.value is Screen.Hidden) return
        if (!canNotify()) return
        try { NotificationManagerCompat.from(this).notify(7, disguisedNotification(0, heads = false)) } catch (_: SecurityException) {}
    }
}

/** The only text any chat or call notification ever shows: no sender name, no message content. */
const val NOTIF_TEXT = "Study reminder"
const val NOTIF_BODY = "Time to review your notes"
const val CHANNEL = "study_rem2"

object App {
    @Volatile var foreground = false
    /** Set just before we open the system photo/file picker or save dialog, so onStop doesn't hide the chat. */
    @Volatile var externalSince = 0L
    fun allowExternal() { externalSince = System.currentTimeMillis() }
    private var r: ChatRepo? = null
    private var cb: (() -> Unit)? = null
    fun repo(a: android.content.Context, onIncoming: () -> Unit): ChatRepo {
        cb = onIncoming
        return r ?: ChatRepo(a.applicationContext, Crypto(a.applicationContext)) { cb?.invoke() }.also { r = it }
    }
}
