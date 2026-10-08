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

class SecretStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun hash(word: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return Base64.encodeToString(md.digest((salt + word.trim().lowercase()).toByteArray()), Base64.NO_WRAP)
    }

    fun matches(word: String): Boolean {
        val salt = prefs.getString("s", null)
        val h = prefs.getString("h", null)
        if (salt == null || h == null) return word.trim().equals("mango", ignoreCase = true)
        return hash(word, salt) == h
    }

    fun set(word: String) {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        val salt = Base64.encodeToString(b, Base64.NO_WRAP)
        prefs.edit().putString("s", salt).putString("h", hash(word, salt)).apply()
    }
}
