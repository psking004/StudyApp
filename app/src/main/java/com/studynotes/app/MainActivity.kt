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
    private var lastTouch = System.currentTimeMillis()
    private var foreground = false
    private val screen = mutableStateOf<Screen>(Screen.Notes)
    lateinit var repo: ChatRepo
    lateinit var secret: SecretStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secret = SecretStore(this)
        repo = App.repo(this) { notifyDisguised() }
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
                LaunchedEffect(s, inCall) {
                    if (s is Screen.Hidden || inCall) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                LaunchedEffect(s) {
                    while (s is Screen.Hidden) {
                        delay(5000)
                        // Idle timer is paused during a call so an active call is never hidden or cut off.
                        if (CallManager.state.value != null) { lastTouch = System.currentTimeMillis(); continue }
                        if (System.currentTimeMillis() - lastTouch > 60_000) { repo.clearOpenedFiles(); screen.value = Screen.Notes }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                when (val cur = s) {
                    Screen.Notes -> NotesScreen(notes,
                        onOpen = { screen.value = Screen.EditNote(it) },
                        onSearch = { q -> if (secret.matches(q)) { lastTouch = System.currentTimeMillis(); askNotifPermission(); screen.value = Screen.Hidden; true } else false })
                    is Screen.EditNote -> {
                        BackHandler { screen.value = Screen.Notes }
                        NoteEditor(notes, cur.id) { screen.value = Screen.Notes }
                    }
                    Screen.Hidden -> HiddenApp(repo, secret, onHide = { repo.openChat = null; repo.clearOpenedFiles(); screen.value = Screen.Notes })
                }
                CallOverlay()
                }
            }
        }
    }

    override fun onUserInteraction() { super.onUserInteraction(); lastTouch = System.currentTimeMillis() }
    override fun onStart() {
        super.onStart(); foreground = true; App.foreground = true
        // Coming back from a photo/file picker: keep chats open, unless that took suspiciously long.
        if (App.externalSince > 0 && System.currentTimeMillis() - App.externalSince > 5 * 60_000) { repo.openChat = null; screen.value = Screen.Notes }
        App.externalSince = 0
        lastTouch = System.currentTimeMillis()
    }
    override fun onStop() {
        super.onStop(); foreground = false; App.foreground = false
        if (App.externalSince > 0) return // we launched a picker/save dialog ourselves; don't hide the chat under it
        repo.openChat = null
        repo.clearOpenedFiles()
        screen.value = Screen.Notes
    }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rem", "Study reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Incoming call while the app is in the background: same harmless-looking reminder text. */
    private fun notifyCall() {
        if (App.foreground) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "rem")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Study Notes")
            .setContentText("Reminder: your study session is starting now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(45_000)
            .setContentIntent(pi).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(this).notify(8, n) } catch (_: SecurityException) {}
    }

    private fun notifyDisguised() {
        if (App.foreground && screen.value is Screen.Hidden) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "rem")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Study Notes")
            .setContentText("Reminder: time to revise your notes")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { NotificationManagerCompat.from(this).notify(7, n) } catch (_: SecurityException) {}
    }
}

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
