package com.studynotes.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

data class Note(val id: Long, val title: String, val body: String, val updated: Long)

class NotesStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "notes.json")

    fun load(): List<Note> {
        if (!file.exists()) { save(seed()); }
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Note(o.getLong("id"), o.getString("title"), o.getString("body"), o.getLong("updated"))
            }.sortedByDescending { it.updated }
        } catch (e: Exception) { emptyList() }
    }

    fun save(notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("updated", it.updated)) }
        file.writeText(arr.toString())
    }

    private fun seed(): List<Note> {
        val t = System.currentTimeMillis()
        return listOf(
            Note(1, "Physics revision", "Ch 4 Laws of motion\n- F = ma\n- Impulse = change in momentum\n- Revise numericals from exercise 4.2", t - 1000),
            Note(2, "Maths formulas", "(a+b)^2 = a^2 + 2ab + b^2\nsin^2 x + cos^2 x = 1\nd/dx (x^n) = n x^(n-1)", t - 2000),
            Note(3, "Exam timetable", "Mon - Physics\nWed - Maths\nFri - Chemistry\nNext Mon - English", t - 3000),
            Note(4, "Things to buy", "Graph notebook\nBlue pens\nHighlighters\nCalculator batteries", t - 4000),
            Note(5, "Project ideas", "Solar tracker model\nWater level alarm\nSmart dustbin", t - 5000)
        )
    }
}

/** Local privacy settings for the hidden section: the passcode (only a salted hash is stored) and notification style. */
class SecretStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** v1.2 format: SHA-256(salt + word). Still accepted so an existing custom passcode keeps working after the update. */
    private fun legacyHash(word: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.encodeToString(md.digest((salt + norm(word)).toByteArray()), Base64.NO_WRAP)
    }

    /** v1.3 format: PBKDF2-HMAC-SHA256 with a random 16-byte salt. */
    private fun pbkdf2(word: String, salt: ByteArray, iterations: Int): String {
        val spec = javax.crypto.spec.PBEKeySpec(norm(word).toCharArray(), salt, iterations, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    private fun norm(word: String) = word.trim().lowercase()

    private fun constEq(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun matches(word: String): Boolean {
        if (norm(word).isEmpty()) return false
        val salt = prefs.getString("s", null)
        val h2 = prefs.getString("h2", null)
        if (salt != null && h2 != null) {
            val it = prefs.getInt("it", PBKDF2_ITERATIONS)
            return try { constEq(pbkdf2(word, Base64.decode(salt, Base64.NO_WRAP), it), h2) } catch (_: Exception) { false }
        }
        val h = prefs.getString("h", null)
        if (salt != null && h != null) {
            val ok = constEq(legacyHash(word, salt), h)
            if (ok) set(word) // upgrade the stored hash to PBKDF2 on the next successful unlock
            return ok
        }
        return norm(word) == DEFAULT_PASSCODE
    }

    /** True while the passcode has never been changed (it is still the default "mango"). */
    val isDefault: Boolean get() = prefs.getString("h2", null) == null && prefs.getString("h", null) == null

    fun set(word: String) {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        prefs.edit().putString("s", Base64.encodeToString(b, Base64.NO_WRAP))
            .putString("h2", pbkdf2(word, b, PBKDF2_ITERATIONS)).putInt("it", PBKDF2_ITERATIONS)
            .remove("h").apply()
    }

    /** Whether chat/call alerts are shown at all. When shown they always read just "Study reminder" (no sender, no content). */
    var notificationsOn: Boolean
        get() = prefs.getBoolean("notif", true)
        set(v) { prefs.edit().putBoolean("notif", v).apply() }

    companion object {
        const val DEFAULT_PASSCODE = "mango"
        const val PBKDF2_ITERATIONS = 120_000
    }
}
