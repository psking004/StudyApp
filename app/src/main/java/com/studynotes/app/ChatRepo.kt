package com.studynotes.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Blob
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatItem(val id: String, val peerUid: String, val peerName: String, val peerPub: String,
                    val lastText: String, val updated: Long, val lastFrom: String)
/** type: "text" (default, also for old docs without a type), "image" or "file". name/mime/size/chunkCount only for attachments.
 *  chunkCount > 0 means the encrypted bytes live in Firestore at messages/{id}/chunks/{0..n-1}. v1.1 messages that only have a
 *  Cloud Storage "path" (never worked: no bucket) have chunkCount 0 and are shown as an unavailable attachment. */
data class Msg(val id: String, val from: String, val text: String, val ts: Long, val delivered: Boolean, val pending: Boolean,
               val type: String = "text", val name: String = "", val mime: String = "", val size: Long = 0,
               val chunkCount: Int = 0, val cipherSize: Long = 0, val voice: Boolean = false, val durationMs: Long = 0,
               val reply: ReplyRef? = null) {
    val available: Boolean get() = chunkCount > 0
}
/** v1.7 reply-to-message. Stored on the replying message as two optional Firestore fields: "replyTo" (the quoted message's
 *  doc id) and "reply" (an encrypted JSON {from, text, kind, ts} made with the same per-chat key as message text, so the
 *  quoted sender and snippet are never plaintext in Firestore). Messages without these fields are shown exactly as before.
 *  kind: "text", "image", "voice" or "file"; text is the quoted snippet (text messages only). */
data class ReplyRef(val id: String, val from: String, val text: String, val kind: String, val ts: Long) {
    companion object {
        fun of(m: Msg) = ReplyRef(m.id, m.from, if (m.type == "image" || m.type == "file") "" else m.text.take(160),
            when { m.type == "image" -> "image"; m.type == "file" && m.voice -> "voice"; m.type == "file" -> "file"; else -> "text" }, m.ts)
    }
}
data class Upload(val id: String, val chatId: String, val name: String, val isImage: Boolean, val progress: Float, val isVoice: Boolean = false)

/** Attachments are stored in Firestore (free Spark plan, no Cloud Storage), so keep them small. */
const val MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024
/** Raw ciphertext bytes per chunk document (Firestore's document limit is 1 MiB). */
const val CHUNK_BYTES = 700 * 1024
const val TOO_BIG = "That's too big to send. Photos and files can be up to 5 MB."
/** Firestore allows 500 writes per batch; stay well under it. */
const val DELETE_BATCH = 450

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
    /** Download progress (0..1) of attachments being fetched, keyed by message id. */
    val downloads = mutableStateMapOf<String, Float>()
    private val loadLocks = HashMap<String, Mutex>()
    /** WebRTC ICE servers: Google STUN plus optional TURN from firebase_config.json. */
    var iceConfig: List<Triple<List<String>, String, String>> = emptyList(); private set
    private var db: FirebaseFirestore? = null
    internal val firestore: FirebaseFirestore? get() = db
    private val startLock = Mutex()
    private var auth: FirebaseAuth? = null
    private var chatsReg: ListenerRegistration? = null
    private val msgRegs = HashMap<String, ListenerRegistration>()
    private val pubRegs = HashMap<String, ListenerRegistration>()
    private val startedAt = System.currentTimeMillis()
    /** Background work that must outlive a screen (call-doc cleanup after a call ends). */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- "Delete for me": stored only on this phone, never sent anywhere ----
    private val privacy = ctx.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    /** chatId -> ids of messages hidden on this phone. */
    val hiddenMsgs = mutableStateMapOf<String, Set<String>>()
    /** chatId -> messages with a server time at or before this are hidden on this phone (set by "Delete chat for me"). */
    val chatCut = mutableStateMapOf<String, Long>()
    /** chatId -> chat is hidden from the list until its "updated" time moves past this (i.e. a new message arrives). */
    val chatHide = mutableStateMapOf<String, Long>()

    init {
        try {
            val j = JSONObject(ctx.assets.open("firebase_config.json").bufferedReader().readText())
            val key = j.optString("apiKey")
            if (key.isNotBlank() && !key.startsWith("PASTE")) {
                val opts = FirebaseOptions.Builder().setApiKey(key).setApplicationId(j.getString("appId"))
                    .setProjectId(j.getString("projectId")).setGcmSenderId(j.optString("messagingSenderId")).build()
                val app = if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx, opts) else FirebaseApp.getInstance()
                db = FirebaseFirestore.getInstance(app); auth = FirebaseAuth.getInstance(app)
                iceConfig = iceFrom(j)
                configured = true
            }
        } catch (e: Exception) { error.value = "The app couldn't load its server settings. Please reinstall the latest version." }
    }

    init {
        for ((k, v) in privacy.all) {
            when {
                k.startsWith("hm_") -> (v as? Set<*>)?.let { set -> hiddenMsgs[k.removePrefix("hm_")] = set.map { it.toString() }.toSet() }
                k.startsWith("cut_") -> (v as? Long)?.let { chatCut[k.removePrefix("cut_")] = it }
                k.startsWith("hide_") -> (v as? Long)?.let { chatHide[k.removePrefix("hide_")] = it }
            }
        }
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
            // Starting a chat you had deleted "for me" shows it again (older messages stay hidden on this phone).
            unhideChat(id)
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
            // A chat hidden with "Delete for me" comes back as soon as a newer message moves its "updated" time.
            list.forEach { c -> chatHide[c.id]?.let { h -> if (c.updated > h) unhideChat(c.id) } }
            // Chats deleted for everyone (by either person) disappear: stop listening and drop cached ciphertext.
            if (!snap.metadata.isFromCache) {
                val ids = list.map { it.id }.toSet()
                (msgRegs.keys + pubRegs.keys + messages.keys).filter { it !in ids }.toSet().forEach { forgetChatLocally(it, clearPrefs = false) }
            }
            list.forEach { c -> if (c.peerPub.isBlank()) fetchPub(c.id, c.peerUid) else listenMessages(c.id) }
        }
    }

    private fun fetchPub(chatId: String, peerUid: String) {
        if (pubRegs.containsKey(chatId)) return
        pubRegs[chatId] = db!!.collection("users").document(peerUid).addSnapshotListener { d, _ ->
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
                    var name = ""; var mime = ""; var size = 0L; var voice = false; var dur = 0L
                    if (type != "text") d.getString("meta")?.let { m ->
                        try {
                            val o = JSONObject(crypto.decrypt(chatId, c.peerPub, m))
                            name = o.optString("name"); mime = o.optString("mime"); size = o.optLong("size")
                            // v1.5 voice messages are type "file" (so no Firestore rules change and older apps show a playable
                            // .m4a file); the encrypted meta marks them with voice=true and their duration.
                            voice = o.optBoolean("voice", false); dur = o.optLong("dur", 0L)
                        } catch (_: Exception) { name = "🔒 Can't decrypt" }
                    }
                    // v1.7: optional reply reference; absent on older messages (reply stays null, bubble unchanged).
                    val reply = d.getString("replyTo")?.takeIf { it.isNotBlank() }?.let { rid ->
                        try {
                            val o = JSONObject(crypto.decrypt(chatId, c.peerPub, d.getString("reply") ?: ""))
                            ReplyRef(rid, o.optString("from"), o.optString("text"), o.optString("kind", "text"), o.optLong("ts"))
                        } catch (_: Exception) { ReplyRef(rid, "", "", "text", 0L) }
                    }
                    Msg(d.id, d.getString("from") ?: "", crypto.decrypt(chatId, c.peerPub, d.getString("cipher") ?: ""),
                        d.getTimestamp("ts")?.toDate()?.time ?: System.currentTimeMillis(),
                        d.getBoolean("delivered") == true, d.metadata.hasPendingWrites(),
                        type, name, mime, size, (d.getLong("chunkCount") ?: 0L).toInt(), d.getLong("size") ?: 0L, voice, dur, reply)
                }
                for (ch in snap.documentChanges) {
                    // Deleted for everyone (by the sender): drop any cached ciphertext of it on this phone too.
                    // (A message that merely scrolls out of the 300-message window also lands here; it is just re-downloaded if opened.)
                    if (ch.type == DocumentChange.Type.REMOVED) { deleteCached(listOf(ch.document.id)); continue }
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

    /** v1.7: the optional reply fields for a new message (empty map when not replying). The quote is encrypted like text. */
    private fun replyFields(chatId: String, peerPub: String, r: ReplyRef?): Map<String, Any> {
        if (r == null) return emptyMap()
        val o = JSONObject().put("from", r.from).put("text", r.text).put("kind", r.kind).put("ts", r.ts)
        return mapOf("replyTo" to r.id, "reply" to crypto.encrypt(chatId, peerPub, o.toString()))
    }

    fun send(chatId: String, text: String, reply: ReplyRef? = null) {
        val me = myUid.value ?: return
        val c = chats.firstOrNull { it.id == chatId } ?: return
        if (c.peerPub.isBlank()) return
        val cipher = crypto.encrypt(chatId, c.peerPub, text)
        val ref = db!!.collection("chats").document(chatId)
        ref.collection("messages").add(mapOf("from" to me, "cipher" to cipher, "ts" to FieldValue.serverTimestamp(), "delivered" to false) +
            replyFields(chatId, c.peerPub, reply))
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

    /** Encrypts a photo or file on the phone, stores only ciphertext in Firestore (as Blob chunks), then posts the message.
     *  Returns an error text or null. */
    suspend fun sendAttachment(chatId: String, uri: Uri, asImage: Boolean, reply: ReplyRef? = null): String? {
        val me = myUid.value ?: return "Not connected"
        val c = peerOf(chatId) ?: return "Chat not ready yet"
        if (c.peerPub.isBlank()) return "Chat not ready yet. Try again in a moment."
        val d = db ?: return "Not connected"
        val ref = d.collection("chats").document(chatId).collection("messages").document()
        val msgId = ref.id
        return try {
            val (origName, origSize) = displayName(uri)
            val mimeIn = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val isImage = asImage || (mimeIn.startsWith("image/") && mimeIn != "image/gif" && mimeIn != "image/svg+xml")
            if (!isImage && origSize > MAX_ATTACHMENT_BYTES) return TOO_BIG
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
                return if (isImage) "Couldn't read that photo. Try another one." else "That file is too big or couldn't be read. Photos and files can be up to 5 MB."
            }
            val (bytes, name, mime) = prepared
            uploadPrepared(chatId, ref, bytes, if (isImage) "image" else "file", name, mime,
                if (isImage) "📷 Photo" else "📎 $name", JSONObject(), reply)
        } catch (e: Exception) {
            uploads.remove(msgId)
            uploadError(e)
        }
    }

    private fun uploadError(e: Exception): String {
        val t = (e.message + " " + (e.cause?.message ?: "")).lowercase()
        return when {
            "too large" in t || "exceeds the maximum" in t || "invalid_argument" in t -> TOO_BIG
            "permission" in t || "denied" in t -> "The server refused the upload. Make sure the latest Firestore rules are published."
            else -> friendly(e)
        }
    }

    /** Encrypts already-prepared bytes on the phone, writes the ciphertext as Firestore Blob chunks, then posts the message.
     *  Shared by photos, files and (v1.5) voice messages. Returns an error text or null. Throws on Firestore errors. */
    private suspend fun uploadPrepared(chatId: String, ref: DocumentReference, bytes: ByteArray, type: String, name: String,
                                       mime: String, labelText: String, extraMeta: JSONObject, reply: ReplyRef? = null): String? {
        val me = myUid.value ?: return "Not connected"
        val c = peerOf(chatId) ?: return "Chat not ready yet"
        val d = db ?: return "Not connected"
        val msgId = ref.id
        if (bytes.size > MAX_ATTACHMENT_BYTES) { uploads.remove(msgId); return TOO_BIG }
        val cipherBytes = withContext(Dispatchers.Default) { crypto.encryptBytes(chatId, c.peerPub, bytes) }
        val count = (cipherBytes.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        // 1) Chunks first, one write per chunk so progress moves per chunk. Each is ~700 KB, under the 1 MiB doc limit.
        val chunks = ref.collection("chunks")
        for (i in 0 until count) {
            val part = cipherBytes.copyOfRange(i * CHUNK_BYTES, minOf(cipherBytes.size, (i + 1) * CHUNK_BYTES))
            d.batch().set(chunks.document(i.toString()), mapOf("idx" to i, "data" to Blob.fromBytes(part))).commit().await()
            uploads[msgId]?.let { u -> uploads[msgId] = u.copy(progress = (i + 1).toFloat() / count) }
        }
        // 2) Then the message itself, which tells the receiver how many chunks to read.
        val metaJson = extraMeta.put("name", name).put("mime", mime).put("size", bytes.size.toLong())
        val meta = crypto.encrypt(chatId, c.peerPub, metaJson.toString())
        val label = crypto.encrypt(chatId, c.peerPub, labelText)
        // Keep a local ciphertext copy so the sender doesn't download it again.
        withContext(Dispatchers.IO) { try { cacheFile(msgId).writeBytes(cipherBytes) } catch (_: Exception) { } }
        ref.set(mapOf("from" to me, "cipher" to label, "type" to type, "meta" to meta,
            "chunkCount" to count, "size" to cipherBytes.size.toLong(),
            "ts" to FieldValue.serverTimestamp(), "delivered" to false) + replyFields(chatId, c.peerPub, reply)).await()
        d.collection("chats").document(chatId).update(mapOf("last" to label, "lastFrom" to me, "updated" to FieldValue.serverTimestamp()))
        uploads.remove(msgId)
        return null
    }

    /** v1.5: sends a recorded voice message (AAC in an .m4a container) through the same encrypted chunk path.
     *  The plaintext recording never leaves the phone; the caller deletes the temp file. Returns an error text or null. */
    suspend fun sendVoice(chatId: String, bytes: ByteArray, durationMs: Long, reply: ReplyRef? = null): String? {
        if (myUid.value == null) return "Not connected"
        val c = peerOf(chatId) ?: return "Chat not ready yet"
        if (c.peerPub.isBlank()) return "Chat not ready yet. Try again in a moment."
        if (bytes.isEmpty()) return "That recording was empty."
        if (bytes.size > MAX_ATTACHMENT_BYTES) return "That voice message is too long to send."
        val d = db ?: return "Not connected"
        val ref = d.collection("chats").document(chatId).collection("messages").document()
        val name = "voice-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".m4a"
        uploads[ref.id] = Upload(ref.id, chatId, name, false, 0f, isVoice = true)
        return try {
            uploadPrepared(chatId, ref, bytes, "file", name, "audio/mp4", "🎤 Voice message",
                JSONObject().put("voice", true).put("dur", durationMs), reply)
        } catch (e: Exception) { uploads.remove(ref.id); uploadError(e) }
    }

    private fun cacheFile(msgId: String): File = File(File(ctx.cacheDir, "att").apply { mkdirs() }, "$msgId.enc")

    /** Reads cached ciphertext, or fetches the Firestore chunks in order, then decrypts in memory.
     *  Only ciphertext is ever cached on disk. Returns null if unavailable or on any failure. */
    suspend fun loadAttachment(chatId: String, m: Msg): ByteArray? {
        val c = peerOf(chatId) ?: return null
        if (c.peerPub.isBlank()) return null
        val f = cacheFile(m.id)
        if (!m.available && !f.exists()) return null
        val lock = synchronized(loadLocks) { loadLocks.getOrPut(m.id) { Mutex() } }
        return try {
            val raw = lock.withLock {
                if (f.exists()) withContext(Dispatchers.IO) { f.readBytes() } else {
                    val d = db ?: return null
                    val chunks = d.collection("chats").document(chatId).collection("messages").document(m.id).collection("chunks")
                    val out = ByteArrayOutputStream(if (m.cipherSize in 1..(MAX_ATTACHMENT_BYTES * 2)) m.cipherSize.toInt() else 64 * 1024)
                    downloads[m.id] = 0f
                    try {
                        // Chunk doc ids are their index, so read 0..n-1 in order (same as ordering by idx) and report progress per chunk.
                        for (i in 0 until m.chunkCount) {
                            val doc = chunks.document(i.toString()).get().await()
                            val blob = doc.getBlob("data") ?: return null
                            if ((doc.getLong("idx") ?: i.toLong()) != i.toLong()) return null
                            out.write(blob.toBytes())
                            downloads[m.id] = (i + 1).toFloat() / m.chunkCount
                        }
                    } finally { downloads.remove(m.id) }
                    val b = out.toByteArray()
                    if (m.cipherSize > 0 && b.size.toLong() != m.cipherSize) return null
                    withContext(Dispatchers.IO) { try { f.writeBytes(b) } catch (_: Exception) { } }
                    b
                }
            }
            withContext(Dispatchers.Default) { crypto.decryptBytes(chatId, c.peerPub, raw) }
        } catch (e: Exception) { null }
    }

    // ===================== Deleting (v1.3) =====================

    /** Messages shown on this phone: everything minus "Delete for me" hides. */
    fun visibleMessages(chatId: String): List<Msg> {
        val all = messages[chatId] ?: return emptyList()
        val hidden = hiddenMsgs[chatId] ?: emptySet()
        val cut = chatCut[chatId] ?: 0L
        return all.filter { it.id !in hidden && (it.pending || it.ts > cut) }
    }

    /** v1.7: true when the quoted original is known to be gone (deleted for everyone). The listener keeps the newest 300
     *  messages, so an original older than that window is not treated as deleted, just not loaded. */
    fun replyOriginalDeleted(chatId: String, r: ReplyRef): Boolean {
        val all = messages[chatId] ?: return false
        if (all.any { it.id == r.id }) return false
        if (all.size < 300) return true
        val oldest = all.firstOrNull()?.ts ?: return false
        return r.ts > 0 && r.ts >= oldest
    }

    /** Chats shown in the list on this phone. */
    fun visibleChats(): List<ChatItem> = chats.filter { c -> chatHide[c.id]?.let { c.updated > it } ?: true }

    /** Chat-list preview that respects local hides. Returns (text, sentByMe). */
    fun previewOf(c: ChatItem): Pair<String, Boolean> {
        if (messages[c.id] != null) {
            val m = visibleMessages(c.id).lastOrNull() ?: return "" to false
            return m.text to (m.from == myUid.value)
        }
        if (chatCut.containsKey(c.id)) return "" to false
        return preview(c) to (c.lastFrom == myUid.value)
    }

    private fun unhideChat(chatId: String) {
        if (chatHide.remove(chatId) != null) privacy.edit().remove("hide_$chatId").apply()
    }

    private fun deleteCached(ids: Collection<String>) {
        if (ids.isEmpty()) return
        bg.launch(Dispatchers.IO) { ids.forEach { try { cacheFile(it).delete() } catch (_: Exception) { } } }
    }

    /** Stops listening to a chat and drops everything this phone holds for it. */
    private fun forgetChatLocally(chatId: String, clearPrefs: Boolean) {
        msgRegs.remove(chatId)?.remove()
        pubRegs.remove(chatId)?.remove()
        deleteCached(messages[chatId]?.map { it.id } ?: emptyList())
        messages.remove(chatId); unread.remove(chatId)
        if (clearPrefs) {
            hiddenMsgs.remove(chatId); chatCut.remove(chatId); chatHide.remove(chatId)
            privacy.edit().remove("hm_$chatId").remove("cut_$chatId").remove("hide_$chatId").apply()
        }
    }

    /** "Delete for me": hides one message on this phone only and removes its cached file. */
    fun deleteMessageForMe(chatId: String, m: Msg) {
        val set = (hiddenMsgs[chatId] ?: emptySet()) + m.id
        hiddenMsgs[chatId] = set
        privacy.edit().putStringSet("hm_$chatId", HashSet(set)).apply()
        deleteCached(listOf(m.id))
    }

    private suspend fun commitDeletes(d: FirebaseFirestore, refs: List<DocumentReference>) {
        for (part in refs.chunked(DELETE_BATCH)) {
            val b = d.batch()
            part.forEach { b.delete(it) }
            b.commit().await()
        }
    }

    private fun deleteError(e: Throwable): String {
        val t = (e.message + " " + (e.cause?.message ?: "")).lowercase()
        return if ("permission" in t || "denied" in t) "The server refused the delete. Make sure the latest Firestore rules (v1.3) are published."
        else friendly(e)
    }

    /** "Delete for everyone" (own messages only): deletes the attachment chunks, the message doc and the local cached file.
     *  The other phone's message listener drops it live. Returns an error text or null. */
    suspend fun deleteMessageForEveryone(chatId: String, m: Msg): String? {
        val me = myUid.value ?: return "Not connected"
        if (m.from != me) return "You can only delete your own messages for everyone."
        val d = db ?: return "Not connected"
        val chatRef = d.collection("chats").document(chatId)
        val ref = chatRef.collection("messages").document(m.id)
        val wasLast = messages[chatId]?.lastOrNull()?.id == m.id
        return try {
            val chunks = if (m.type != "text" || m.chunkCount > 0) ref.collection("chunks").get().await().documents.map { it.reference } else emptyList()
            commitDeletes(d, chunks + ref)
            deleteCached(listOf(m.id))
            if (wasLast) try {
                // Point the chat preview at the previous message so the deleted text doesn't linger there.
                val prev = chatRef.collection("messages").orderBy("ts", Query.Direction.DESCENDING).limit(1).get().await().documents.firstOrNull()
                chatRef.update(mapOf("last" to (prev?.getString("cipher") ?: ""), "lastFrom" to (prev?.getString("from") ?: ""))).await()
            } catch (_: Exception) { }
            null
        } catch (e: Exception) { deleteError(e) }
    }

    /** "Delete chat for me": hides the chat and its current messages on this phone until a new message arrives. */
    fun deleteChatForMe(chatId: String) {
        val c = chats.firstOrNull { it.id == chatId }
        val cut = maxOf(c?.updated ?: 0L, messages[chatId]?.maxOfOrNull { it.ts } ?: 0L)
        chatCut[chatId] = cut; chatHide[chatId] = cut
        privacy.edit().putLong("cut_$chatId", cut).putLong("hide_$chatId", cut).remove("hm_$chatId").apply()
        hiddenMsgs.remove(chatId)
        deleteCached(messages[chatId]?.map { it.id } ?: emptyList())
        unread.remove(chatId)
    }

    /** "Delete chat for everyone": deletes every message and its chunks, then the chat doc, in batches of <= 450.
     *  The chat id is the sorted uid pair, so starting a new chat with that person later simply recreates it. */
    suspend fun deleteChatForEveryone(chatId: String): String? {
        if (myUid.value == null) return "Not connected"
        val d = db ?: return "Not connected"
        val chatRef = d.collection("chats").document(chatId)
        return try {
            // A second pass catches anything the other person sent while the first pass was running.
            for (pass in 0 until 3) {
                val docs = chatRef.collection("messages").get().await().documents
                if (docs.isEmpty()) break
                val refs = ArrayList<DocumentReference>()
                for (m in docs) {
                    val hasChunks = (m.getString("type") ?: "text") != "text" || (m.getLong("chunkCount") ?: 0L) > 0L
                    if (hasChunks) refs += m.reference.collection("chunks").get().await().documents.map { it.reference }
                    refs += m.reference
                }
                commitDeletes(d, refs)
                deleteCached(docs.map { it.id })
            }
            chatRef.delete().await()
            forgetChatLocally(chatId, clearPrefs = true)
            chats.removeAll { it.id == chatId }
            null
        } catch (e: Exception) { deleteError(e) }
    }

    /** Deletes a call's ICE candidate subdocs, then the call doc. Missing docs / already-deleted calls are ignored. */
    suspend fun deleteCallDocs(callId: String): Boolean {
        val d = db ?: return false
        val ref = d.collection("calls").document(callId)
        val cands = ArrayList<DocumentReference>()
        for (side in listOf("callerCandidates", "calleeCandidates"))
            try { cands += ref.collection(side).get().await().documents.map { it.reference } } catch (_: Exception) { }
        try { commitDeletes(d, cands) } catch (_: Exception) { }
        return try { ref.delete().await(); true } catch (_: Exception) { false }
    }

    /** Called when a call ends. The short delay lets the other phone see the final "ended"/"declined" status first. */
    fun deleteCallSoon(callId: String, delayMs: Long = 3000) {
        bg.launch { delay(delayMs); deleteCallDocs(callId) }
    }

    /** "Clear call history": deletes every call doc (and its candidates) where I'm the caller or callee, except a live call.
     *  Returns how many were deleted, or an error text. */
    suspend fun clearCallHistory(): Result<Int> {
        val me = myUid.value ?: return Result.failure(IllegalStateException("Not connected"))
        val d = db ?: return Result.failure(IllegalStateException("Not connected"))
        return try {
            val live = CallManager.state.value?.takeIf { CallManager.active }?.callId
            val ids = (d.collection("calls").whereEqualTo("caller", me).get().await().documents +
                d.collection("calls").whereEqualTo("callee", me).get().await().documents).map { it.id }.toSet() - setOfNotNull(live)
            var done = 0
            for (id in ids) if (deleteCallDocs(id)) done++
            if (done < ids.size && done == 0) Result.failure(IllegalStateException("The server refused the delete. Make sure the latest Firestore rules (v1.3) are published."))
            else Result.success(done)
        } catch (e: Exception) { Result.failure(IllegalStateException(deleteError(e))) }
    }

    /** Temporary plaintext copies made for "Open with" are wiped whenever chats are hidden. */
    fun clearOpenedFiles() { try { File(ctx.cacheDir, "open").deleteRecursively() } catch (_: Exception) { } }

    fun signOut() {
        CallManager.detach()
        chatsReg?.remove(); msgRegs.values.forEach { it.remove() }; msgRegs.clear()
        pubRegs.values.forEach { it.remove() }; pubRegs.clear()
        chats.clear(); messages.clear(); myName.value = null; ready.value = false
        auth?.signOut(); myUid.value = null
    }
}
