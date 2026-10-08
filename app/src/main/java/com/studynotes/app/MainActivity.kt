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
import androidx.compose.runtime.*
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
        val notes = NotesStore(this)
        setContent {
            StudyTheme {
                val s by screen
                LaunchedEffect(s) {
                    if (s is Screen.Hidden) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                LaunchedEffect(s) {
                    while (s is Screen.Hidden) {
                        delay(5000)
                        if (System.currentTimeMillis() - lastTouch > 60_000) screen.value = Screen.Notes
                    }
                }
                when (val cur = s) {
                    Screen.Notes -> NotesScreen(notes,
                        onOpen = { screen.value = Screen.EditNote(it) },
                        onSearch = { q -> if (secret.matches(q)) { lastTouch = System.currentTimeMillis(); askNotifPermission(); screen.value = Screen.Hidden; true } else false })
                    is Screen.EditNote -> {
                        BackHandler { screen.value = Screen.Notes }
                        NoteEditor(notes, cur.id) { screen.value = Screen.Notes }
                    }
                    Screen.Hidden -> HiddenApp(repo, secret, onHide = { repo.openChat = null; screen.value = Screen.Notes })
                }
            }
        }
    }

    override fun onUserInteraction() { super.onUserInteraction(); lastTouch = System.currentTimeMillis() }
    override fun onStart() { super.onStart(); foreground = true; App.foreground = true }
    override fun onStop() {
        super.onStop(); foreground = false; App.foreground = false
        repo.openChat = null
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
    private var r: ChatRepo? = null
    private var cb: (() -> Unit)? = null
    fun repo(a: android.content.Context, onIncoming: () -> Unit): ChatRepo {
        cb = onIncoming
        return r ?: ChatRepo(a.applicationContext, Crypto(a.applicationContext)) { cb?.invoke() }.also { r = it }
    }
}
