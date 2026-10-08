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
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class ChatItem(val id: String, val peerUid: String, val peerName: String, val peerPub: String,
                    val lastText: String, val updated: Long, val lastFrom: String)
data class Msg(val id: String, val from: String, val text: String, val ts: Long, val delivered: Boolean, val pending: Boolean)

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
    private var db: FirebaseFirestore? = null
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
                    .apply { j.optString("storageBucket").takeIf { it.isNotBlank() }?.let { setStorageBucket(it) } }.build()
                val app = if (FirebaseApp.getApps(ctx).isEmpty()) FirebaseApp.initializeApp(ctx, opts) else FirebaseApp.getInstance()
                db = FirebaseFirestore.getInstance(app); auth = FirebaseAuth.getInstance(app)
                configured = true
            }
        } catch (e: Exception) { error.value = "The app couldn't load its server settings. Please reinstall the latest version." }
    }

    suspend fun start() {
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
                    Msg(d.id, d.getString("from") ?: "", crypto.decrypt(chatId, c.peerPub, d.getString("cipher") ?: ""),
                        d.getTimestamp("ts")?.toDate()?.time ?: System.currentTimeMillis(),
                        d.getBoolean("delivered") == true, d.metadata.hasPendingWrites())
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

    fun signOut() {
        chatsReg?.remove(); msgRegs.values.forEach { it.remove() }; msgRegs.clear()
        chats.clear(); messages.clear(); myName.value = null; ready.value = false
        auth?.signOut(); myUid.value = null
    }
}
