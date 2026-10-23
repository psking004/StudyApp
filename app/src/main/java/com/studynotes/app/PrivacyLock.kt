package com.studynotes.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * v1.5 privacy lock. One place that decides when the private chat area must close and the app must show only the
 * study-notes screen. Triggers (each one calls [lockNow]):
 *  - 1 minute without user interaction inside the private area (timer in MainActivity, reset by [touch]);
 *  - the activity stops (Home, Recents/app switcher, switching apps) -> MainActivity.onStop;
 *  - the whole app goes to the background -> ProcessLifecycleOwner ON_STOP (backstop for anything onStop missed);
 *  - screen off / device lock -> ACTION_SCREEN_OFF receiver (always, even while a photo/file picker is open);
 *  - the app is brought back from Recents or the launcher, or a notification is tapped -> MainActivity.onStart / onNewIntent.
 * The only exception is a system photo/file picker or save/"Open with" dialog that the user opened from inside a chat
 * ([App.allowExternal]); it may keep the chat open for at most [EXTERNAL_GRACE_MS], and screen-off still locks.
 * Locking never hangs up or declines a call (v1.4 behaviour): only the screens are hidden.
 */
object PrivacyLock {
    /** Idle time inside the private area before it locks. */
    const val IDLE_MS = 60_000L
    /** Longest a picker/save/open-with trip outside the app may keep the chat open. */
    const val EXTERNAL_GRACE_MS = 60_000L

    /** Set by MainActivity while it exists; hides the private area (main thread). */
    @Volatile var onLock: (() -> Unit)? = null
    /** Last user interaction (touch, key, typing, recording, voice playback). */
    @Volatile var lastTouch = System.currentTimeMillis()

    fun touch() { lastTouch = System.currentTimeMillis() }

    /** True while a picker/save dialog the user opened from a chat is on top and its grace time hasn't run out. */
    fun externalActive(): Boolean {
        val since = App.externalSince
        return since > 0 && System.currentTimeMillis() - since <= EXTERNAL_GRACE_MS
    }

    fun lockNow() {
        App.externalSince = 0
        VoicePlayer.stop()
        onLock?.invoke()
    }
}

class StudyNotesApp : Application() {
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) PrivacyLock.lockNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, f)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Whole app left the foreground. A picker the user opened from a chat is the only thing allowed to keep it open.
                if (!PrivacyLock.externalActive()) PrivacyLock.lockNow()
            }
        })
    }
}
