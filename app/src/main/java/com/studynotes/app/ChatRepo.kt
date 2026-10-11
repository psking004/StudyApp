package com.studynotes.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class ChatItem(val id: String, val peerUid: String, val peerName: String, val peerPub: String,
                    val lastText: String, val updated: Long, val lastFrom: String)
/** type: "text" (default, also for old docs without a type), "image" or "file". name/mime/size/path only for attachments. */
data class Msg(val id: String, val from: String, val text: String, val ts: Long, val delivered: Boolean, val pending: Boolean,
               val type: String = "text", val name: String = "", val mime: String = "", val size: Long = 0, val path: String = "")
data class Upload(val id: String, val chatId: String, val name: String, val isImage: Boolean, val progress: Float)

const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

class ChatRepo(private val ctx: Context, private val crypto: Crypto, private val onIncoming: () -> Unit) {
    var configured = false; private set
    val myUid = mutableStateOf<String?>(null)
    val myName = mutableStateOf<String?>(null)
    val ready = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val chats = mutableStateListOf<ChatItem>()
    val messages = mutableStateMapOf<String, List<Msg>>()
    val unread = mutableStateMapOf<String, Int>()
    var openChat: String? = null
    val uploads = mutableStateMapOf<String, Upload>()
    /** WebRTC ICE servers: Google STUN plus optional TURN from firebase_config.json. */
    var iceConfig: List<Triple<List<String>, String, String>> = emptyList(); private set
    private var db: FirebaseFirestore? = null
    private var storage: FirebaseStorage? = null
    internal val firestore: FirebaseFirestore? get() = db
    private val startLock = Mutex()
    private var auth: FirebaseAuth? = null
    private var chatsReg: ListenerRegistration? = null
    private val msgRegs = HashMap<String, ListenerRegistration>()
    private val startedAt = System.currentTimeMillis()

    init {
        try {
            val j = JSONObject(ctx.assets.open("firebase_config.json").bufferedReader().readText())
            val key = j.optString("apiKey")
            if (key.isNotBlank() && !key.startsWith("PASTE")) {
                val opts = FirebaseOptions.Builder().setApiKey(key).setApplicationId(j.getString("appId"))
                    .setProjectId(j.getString("projectId")).setGcmSenderId(j.optString("messagingSenderId"))
                    .setStorageBucket(bucketFrom(j)).build()
                val app = if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx, opts) else FirebaseApp.getInstance()
                db = FirebaseFirestore.getInstance(app); auth = FirebaseAuth.getInstance(app)
                storage = try { FirebaseStorage.getInstance(app) } catch (_: Exception) { null }
                iceConfig = iceFrom(j)
                configured = true
            }
        } catch (e: Exception) { error.value = "The app couldn't load its server settings. Please reinstall the latest version." }
    }

    /** Bucket: "storage_bucket" key, then legacy "storageBucket", else the default <projectId>.firebasestorage.app. */
    private fun bucketFrom(j: JSONObject): String {
        val b = j.optString("storage_bucket").ifBlank { j.optString("storageBucket") }.trim()
        return b.removePrefix("gs://").ifBlank { j.getString("projectId") + ".firebasestorage.app" }
    }

    private fun iceFrom(j: JSONObject): List<Triple<List<String>, String, String>> {
        val out = mutableListOf(Triple(listOf("stun:stun.l.google.com:19302"), "", ""))
        val raw = j.opt("turn_urls")
        val urls = when (raw) {
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            is String -> raw.split(',')
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotBlank() }
        if (urls.isNotEmpty()) out += Triple(urls, j.optString("turn_username"), j.optString("turn_credential"))
        return out
    }

    suspend fun start() = startLock.withLock { startInner() }

    private suspend fun startInner() {
        if (!configured || ready.value) return
        error.value = null
        try {
            val a = auth!!
            val user = a.currentUser ?: a.signInAnonymously().await().user!!
            myUid.value = user.uid
            val doc = db!!.collection("users").document(user.uid).get().await()
            val name = doc.getString("username")
            if (name != null) {
                if (doc.getString("pub") != crypto.publicKeyB64)
                    db!!.collection("users").document(user.uid).update("pub", crypto.publicKeyB64).await()
                myName.value = name
                listenChats()
            }
            ready.value = true
        } catch (e: Exception) { error.value = friendly(e) }
    }

    suspend fun claimUsername(raw: String): String? {
        val name = raw.trim().lowercase()
        if (!Regex("^[a-z0-9_.]{3,20}$").matches(name)) return "Use 3-20 letters, numbers, _ or ."
        val uid = myUid.value ?: return "Not connected"
        val d = db!!
        return try {
            d.runTransaction { tx ->
                val ref = d.collection("usernames").document(name)
                if (tx.get(ref).exists()) throw IllegalStateException("taken")
                tx.set(ref, mapOf("uid" to uid))
                tx.set(d.collection("users").document(uid), mapOf("username" to name, "pub" to crypto.publicKeyB64))
            }.await()
            myName.value = name; listenChats(); null
        } catch (e: Exception) { if (e.message?.contains("taken") == true || e.cause?.message?.contains("taken") == true) "That username is taken" else friendly(e) }
    }

    /** Returns chatId or error text prefixed with "!" */
    suspend fun startChat(rawName: String): String {
        val name = rawName.trim().lowercase().removePrefix("@")
        val me = myUid.value ?: return "!Not connected"
        if (name == myName.value) return "!That's you"
        val d = db!!
        return try {
            val u = d.collection("usernames").document(name).get().await()
            val peer = u.getString("uid") ?: return "!No user called @$name"
            val id = listOf(me, peer).sorted().joinToString("_")
            val ref = d.collection("chats").document(id)
            if (!ref.get().await().exists()) {
                ref.set(mapOf("members" to listOf(me, peer), "names" to mapOf(me to myName.value, peer to name),
                    "updated" to FieldValue.serverTimestamp(), "lastFrom" to "", "last" to "")).await()
            }
            id
        } catch (e: Exception) { "!" + friendly(e) }
    }

    /** Turns technical errors into short messages for normal users. */
    fun friendly(e: Throwable): String {
        val t = (e.message + " " + (e.cause?.message ?: "")).lowercase()
        return when {
            "network" in t || "unavailable" in t || "unable to resolve" in t || "timeout" in t || "offline" in t ->
                "No internet connection. Check your Wi-Fi or mobile data and try again."
            "permission" in t || "denied" in t -> "Access was refused by the server. Please update the app."
            "quota" in t || "resource_exhausted" in t -> "The server is busy right now. Try again in a few minutes."
            "blocked" in t || "api key" in t || "api_key" in t -> "This copy of the app isn't authorised. Please reinstall the official APK."
            else -> "Something went wrong. Please try again."
        }
    }

    private fun listenChats() {
        val me = myUid.value ?: return
        CallManager.attach(ctx, this)
        chatsReg?.remove()
        chatsReg = db!!.collection("chats").whereArrayContains("members", me).addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val list = snap.documents.mapNotNull { d ->
                val members = (d.get("members") as? List<*>)?.map { it.toString() } ?: return@mapNotNull null
                val peer = members.firstOrNull { it != me } ?: return@mapNotNull null
                val names = d.get("names") as? Map<*, *>
                val old = chats.firstOrNull { it.id == d.id }
                ChatItem(d.id, peer, names?.get(peer)?.toString() ?: "user", old?.peerPub ?: "",
                    d.getString("last") ?: "", d.getTimestamp("updated")?.toDate()?.time ?: System.currentTimeMillis(),
                    d.getString("lastFrom") ?: "")
            }.sortedByDescending { it.updated }
            chats.clear(); chats.addAll(list)
            list.forEach { c -> if (c.peerPub.isBlank()) fetchPub(c.id, c.peerUid) else listenMessages(c.id) }
        }
    }

    private fun fetchPub(chatId: String, peerUid: String) {
        db!!.collection("users").document(peerUid).addSnapshotListener { d, _ ->
            val pub = d?.getString("pub") ?: return@addSnapshotListener
            val i = chats.indexOfFirst { it.id == chatId }
            if (i >= 0 && chats[i].peerPub != pub) { chats[i] = chats[i].copy(peerPub = pub); msgRegs.remove(chatId)?.remove(); listenMessages(chatId) }
        }
    }

    fun preview(c: ChatItem): String = if (c.last().isBlank() || c.peerPub.isBlank()) "" else crypto.decrypt(c.id, c.peerPub, c.lastText)
    private fun ChatItem.last() = lastText

    private fun listenMessages(chatId: String) {
        if (msgRegs.containsKey(chatId)) return
        val me = myUid.value ?: return
        val reg = db!!.collection("chats").document(chatId).collection("messages")
            .orderBy("ts", Query.Direction.ASCENDING).limitToLast(300)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val c = chats.firstOrNull { it.id == chatId } ?: return@addSnapshotListener
                messages[chatId] = snap.documents.map { d ->
                    val type = d.getString("type") ?: "text"
                    var name = ""; var mime = ""; var size = 0L
                    if (type != "text") d.getString("meta")?.let { m ->
                        try {
                            val o = JSONObject(crypto.decrypt(chatId, c.peerPub, m))
                            name = o.optString("name"); mime = o.optString("mime"); size = o.optLong("size")
                        } catch (_: Exception) { name = "🔒 Can't decrypt" }
                    }
                    Msg(d.id, d.getString("from") ?: "", crypto.decrypt(chatId, c.peerPub, d.getString("cipher") ?: ""),
                        d.getTimestamp("ts")?.toDate()?.time ?: System.currentTimeMillis(),
                        d.getBoolean("delivered") == true, d.metadata.hasPendingWrites(),
                        type, name, mime, size, d.getString("path") ?: "")
                }
                for (ch in snap.documentChanges) {
                    if (ch.type != DocumentChange.Type.ADDED) continue
                    val d = ch.document
                    if (d.getString("from") == me) continue
                    if (d.getBoolean("delivered") != true) d.reference.update("delivered", true)
                    val ts = d.getTimestamp("ts")?.toDate()?.time ?: System.currentTimeMillis()
                    if (ts > startedAt && openChat != chatId) {
                        unread[chatId] = (unread[chatId] ?: 0) + 1
                        onIncoming()
                    }
                }
            }
        msgRegs[chatId] = reg
    }

    fun send(chatId: String, text: String) {
        val me = myUid.value ?: return
        val c = chats.firstOrNull { it.id == chatId } ?: return
        if (c.peerPub.isBlank()) return
        val cipher = crypto.encrypt(chatId, c.peerPub, text)
        val ref = db!!.collection("chats").document(chatId)
        ref.collection("messages").add(mapOf("from" to me, "cipher" to cipher, "ts" to FieldValue.serverTimestamp(), "delivered" to false))
        ref.update(mapOf("last" to cipher, "lastFrom" to me, "updated" to FieldValue.serverTimestamp()))
    }

    fun peerOf(chatId: String): ChatItem? = chats.firstOrNull { it.id == chatId }

    private fun displayName(uri: Uri): Pair<String, Long> {
        var name = "file"; var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getString(0)?.let { name = it }
                    if (!cur.isNull(1)) size = cur.getLong(1)
                }
            }
        } catch (_: Exception) { }
        return name to size
    }

    /** Scales an image down to max 1600px on the long side and re-encodes as JPEG 80 (also strips EXIF/location). */
    private fun compressImage(uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val long = maxOf(bmp.width, bmp.height)
        val scaled = if (long > 1600) Bitmap.createScaledBitmap(bmp, bmp.width * 1600 / long, bmp.height * 1600 / long, true) else bmp
        val rotated = rotateFromExif(uri, scaled)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun rotateFromExif(uri: Uri, b: Bitmap): Bitmap = try {
        val deg = ctx.contentResolver.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        } ?: 0f
        if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
    } catch (_: Exception) { b }

    /** Encrypts a photo or file on the phone, uploads only ciphertext, then posts the message. Returns an error text or null. */
    suspend fun sendAttachment(chatId: String, uri: Uri, asImage: Boolean): String? {
        val me = myUid.value ?: return "Not connected"
        val c = peerOf(chatId) ?: return "Chat not ready yet"
        if (c.peerPub.isBlank()) return "Chat not ready yet. Try again in a moment."
        val st = storage ?: return "File sharing isn't set up on the server yet."
        val d = db!!
        val ref = d.collection("chats").document(chatId).collection("messages").document()
        val msgId = ref.id
        return try {
            val (origName, origSize) = displayName(uri)
            val mimeIn = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val isImage = asImage || (mimeIn.startsWith("image/") && mimeIn != "image/gif" && mimeIn != "image/svg+xml")
            if (!isImage && origSize > MAX_ATTACHMENT_BYTES) return "That file is too big. The limit is 25 MB."
            uploads[msgId] = Upload(msgId, chatId, origName, isImage, 0f)
            val prepared = withContext(Dispatchers.IO) {
                if (isImage) compressImage(uri)?.let { Triple(it, origName.substringBeforeLast('.') + ".jpg", "image/jpeg") }
                else ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    val buf = ByteArrayOutputStream(); val chunk = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = ins.read(chunk); if (n < 0) break
                        total += n; if (total > MAX_ATTACHMENT_BYTES) return@use null
                        buf.write(chunk, 0, n)
                    }
                    Triple(buf.toByteArray(), origName, mimeIn)
                }
            }
            if (prepared == null) {
                uploads.remove(msgId)
                return if (isImage) "Couldn't read that photo. Try another one." else "That file is too big or couldn't be read. The limit is 25 MB."
            }
            val (bytes, name, mime) = prepared
            if (bytes.size > MAX_ATTACHMENT_BYTES) { uploads.remove(msgId); return "That file is too big. The limit is 25 MB." }
            val cipherBytes = withContext(Dispatchers.Default) { crypto.encryptBytes(chatId, c.peerPub, bytes) }
            val path = "chats/$chatId/$msgId"
            st.reference.child(path).putBytes(cipherBytes)
                .addOnProgressListener { t ->
                    val total = if (t.totalByteCount > 0) t.totalByteCount else cipherBytes.size.toLong()
                    uploads[msgId]?.let { u -> uploads[msgId] = u.copy(progress = t.bytesTransferred.toFloat() / total) }
                }.await()
            val type = if (isImage) "image" else "file"
            val meta = crypto.encrypt(chatId, c.peerPub, JSONObject().put("name", name).put("mime", mime).put("size", bytes.size.toLong()).toString())
            val label = crypto.encrypt(chatId, c.peerPub, if (isImage) "📷 Photo" else "📎 $name")
            // Keep a local ciphertext copy so the sender doesn't download it again.
            withContext(Dispatchers.IO) { try { cacheFile(msgId).writeBytes(cipherBytes) } catch (_: Exception) { } }
            ref.set(mapOf("from" to me, "cipher" to label, "type" to type, "meta" to meta, "path" to path,
                "ts" to FieldValue.serverTimestamp(), "delivered" to false)).await()
            d.collection("chats").document(chatId).update(mapOf("last" to label, "lastFrom" to me, "updated" to FieldValue.serverTimestamp()))
            uploads.remove(msgId)
            null
        } catch (e: Exception) {
            uploads.remove(msgId)
            val t = (e.message ?: "").lowercase()
            when {
                "object does not exist" in t || "bucket" in t || "not found" in t -> "File sharing isn't set up on the server yet."
                "permission" in t || "unauthorized" in t || "403" in t -> "The server refused the upload. Please update the app."
                else -> friendly(e)
            }
        }
    }

    private fun cacheFile(msgId: String): File = File(File(ctx.cacheDir, "att").apply { mkdirs() }, "$msgId.enc")

    /** Downloads (or reads cached) ciphertext and decrypts it in memory. Only ciphertext is ever cached on disk. */
    suspend fun loadAttachment(chatId: String, m: Msg): ByteArray? {
        val c = peerOf(chatId) ?: return null
        if (m.path.isBlank() || c.peerPub.isBlank()) return null
        return try {
            val f = cacheFile(m.id)
            val raw = if (f.exists()) withContext(Dispatchers.IO) { f.readBytes() } else {
                val st = storage ?: return null
                val b = st.reference.child(m.path).getBytes(MAX_ATTACHMENT_BYTES + 1024 * 1024).await()
                withContext(Dispatchers.IO) { try { f.writeBytes(b) } catch (_: Exception) { } }
                b
            }
            withContext(Dispatchers.Default) { crypto.decryptBytes(chatId, c.peerPub, raw) }
        } catch (e: Exception) { null }
    }

    /** Temporary plaintext copies made for "Open with" are wiped whenever chats are hidden. */
    fun clearOpenedFiles() { try { File(ctx.cacheDir, "open").deleteRecursively() } catch (_: Exception) { } }

    fun signOut() {
        CallManager.detach()
        chatsReg?.remove(); msgRegs.values.forEach { it.remove() }; msgRegs.clear()
        chats.clear(); messages.clear(); myName.value = null; ready.value = false
        auth?.signOut(); myUid.value = null
    }
}
