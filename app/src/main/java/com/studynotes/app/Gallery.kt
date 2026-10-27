package com.studynotes.app

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.6: manual "Save to gallery" for chat photos. Only ever called from an explicit button tap; nothing is saved
 * automatically. Takes the already-decrypted image bytes (from ChatRepo.loadAttachment, the same path the chat uses to
 * show the photo) and writes a copy to Pictures/StudyNotes through MediaStore.
 *  - API 29+: MediaStore with RELATIVE_PATH, no permission needed.
 *  - API 26-28: file in the public Pictures/StudyNotes folder + media scan; needs WRITE_EXTERNAL_STORAGE (maxSdkVersion=28).
 */
object Gallery {
    const val FOLDER = "StudyNotes"

    /** Android 9 and below need the storage permission to write to the public Pictures folder. */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT < 29

    /** Returns true when the photo was written. Call off the main thread. */
    fun saveImage(ctx: Context, bytes: ByteArray, mimeHint: String): Boolean {
        var data = bytes
        var mime = sniff(bytes) ?: mimeHint.takeIf { it.startsWith("image/") && it != "image/*" }
        if (mime == null) {
            // Unknown format: re-encode as JPEG so the gallery can read it.
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            data = ByteArrayOutputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
            mime = "image/jpeg"
        }
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/heic", "image/heif" -> "heic"; else -> "jpg" }
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "." + ext
        return if (Build.VERSION.SDK_INT >= 29) saveQ(ctx, data, mime, name) else saveLegacy(ctx, data, mime, name)
    }

    private fun saveQ(ctx: Context, data: ByteArray, mime: String, name: String): Boolean {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: return false
        return try {
            cr.openOutputStream(uri)?.use { it.write(data) } ?: throw IllegalStateException("no stream")
            cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) { }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(ctx: Context, data: ByteArray, mime: String, name: String): Boolean = try {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER).apply { mkdirs() }
        val f = File(dir, name)
        f.writeBytes(data)
        MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(f.absolutePath), arrayOf(mime), null)
        true
    } catch (_: Exception) { false }

    private fun sniff(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code -> "image/gif"
            at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "image/webp"
            else -> null
        }
    }
}
