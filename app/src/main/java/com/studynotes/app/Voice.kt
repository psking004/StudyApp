package com.studynotes.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import java.io.File

/** Longest voice message (also enforced by MediaRecorder.setMaxDuration). ~2 min of 32 kbps AAC is about 0.5 MB. */
const val MAX_VOICE_MS = 120_000L

/**
 * v1.5 voice messages: AAC (MPEG-4/.m4a) recorded to a private temp file in cacheDir/rec, read into memory, then
 * deleted. Only ciphertext is uploaded (ChatRepo.sendVoice). Main thread only.
 */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    val recording = mutableStateOf(false)

    fun elapsedMs(): Long = if (recording.value) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Starts recording. [onMaxReached] fires (main thread) when the 2-minute cap is hit. Returns an error text or null. */
    fun start(onMaxReached: () -> Unit): String? {
        if (recording.value) return null
        cleanup()
        val dir = File(ctx.cacheDir, "rec").apply { mkdirs() }
        val f = File(dir, "v_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MAX_VOICE_MS.toInt())
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached() }
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; startedAt = SystemClock.elapsedRealtime(); recording.value = true
            null
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            f.delete()
            "Couldn't start the microphone. Close other apps using it and try again."
        }
    }

    /** Stops and returns (bytes, durationMs), or null if too short / failed. The temp file is always deleted. */
    fun stop(): Pair<ByteArray, Long>? {
        val r = rec ?: return null
        val f = file
        val dur = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(MAX_VOICE_MS)
        rec = null; recording.value = false
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false } // throws if stopped right after start (no data)
        try { r.release() } catch (_: Exception) { }
        val bytes = try { if (ok && f != null && f.exists()) f.readBytes() else null } catch (_: Exception) { null }
        f?.delete(); file = null
        if (bytes == null || bytes.isEmpty() || dur < 700) return null
        return bytes to dur
    }

    /** Stops without sending (cancel, lock, leaving the chat). */
    fun cancel() {
        val r = rec
        rec = null; recording.value = false
        if (r != null) {
            try { r.stop() } catch (_: Exception) { }
            try { r.release() } catch (_: Exception) { }
        }
        cleanup()
    }

    private fun cleanup() {
        file?.delete(); file = null
        try { File(ctx.cacheDir, "rec").listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
    }
}

/** Plays decrypted voice bytes straight from memory (MediaDataSource), so no plaintext audio is written to disk. */
object VoicePlayer {
    /** Id of the message currently loaded in the player, or null. */
    val current = mutableStateOf<String?>(null)
    val playing = mutableStateOf(false)
    val positionMs = mutableStateOf(0L)
    val durationMs = mutableStateOf(0L)
    private var mp: MediaPlayer? = null

    private class Bytes(private val b: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= b.size) return -1
            val n = minOf(size.toLong(), b.size - position).toInt()
            System.arraycopy(b, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = b.size.toLong()
        override fun close() {}
    }

    /** Starts playing [bytes] for message [id]. Returns false if the audio can't be played. */
    fun play(id: String, bytes: ByteArray): Boolean {
        stop()
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(Bytes(bytes))
            p.prepare()
            p.setOnCompletionListener { stop() }
            p.start()
            mp = p; current.value = id; playing.value = true; durationMs.value = p.duration.toLong(); positionMs.value = 0
            true
        } catch (e: Exception) { try { p.release() } catch (_: Exception) { }; false }
    }

    fun toggle() {
        val p = mp ?: return
        try {
            if (p.isPlaying) { p.pause(); playing.value = false } else { p.start(); playing.value = true }
        } catch (_: Exception) { stop() }
    }

    /** Called about every 250 ms from the UI while playing; also counts as user activity for the idle lock. */
    fun tick() {
        val p = mp ?: return
        try { positionMs.value = p.currentPosition.toLong() } catch (_: Exception) { }
        if (playing.value) PrivacyLock.touch()
    }

    fun stop() {
        val p = mp
        mp = null
        if (p != null) { try { p.stop() } catch (_: Exception) { }; try { p.release() } catch (_: Exception) { } }
        current.value = null; playing.value = false; positionMs.value = 0; durationMs.value = 0
    }
}
