package com.studynotes.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val WaGreen = Color(0xFF008069)
private val WaBg = Color(0xFFEFE7DE)
private val OutBubble = Color(0xFFD9FDD3)
// v1.6 WhatsApp-style palette (visual only)
private val InBubble = Color.White
private val WaLight = Color(0xFF25D366)
private val WaText = Color(0xFF111B21)
private val WaSub = Color(0xFF667781)
private val WaDivider = Color(0xFFE9EDEF)
private val AvatarColors = listOf(Color(0xFF00A884), Color(0xFF7F66FF), Color(0xFFFF8A65), Color(0xFF53BDEB),
    Color(0xFFE26AB6), Color(0xFF3BA55C), Color(0xFFFFA000), Color(0xFF5C6BC0), Color(0xFFEF5350), Color(0xFF26A69A))

private sealed class H { object List : H(); data class Chat(val id: String) : H(); object New : H(); object Settings : H() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenApp(repo: ChatRepo, secret: SecretStore, onHide: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.start() }
    var page by remember { mutableStateOf<H>(H.List) }
    MaterialTheme(colorScheme = lightColorScheme(primary = WaGreen)) {
        when {
            !repo.configured -> Centered("Server not set up yet.\nAdd your Firebase config to finish.", onHide)
            repo.error.value != null && !repo.ready.value -> Centered(repo.error.value!!, onHide, onRetry = { scope.launch { repo.start() } })
            !repo.ready.value -> Centered("Connecting...", onHide)
            repo.myName.value == null -> UsernameSetup(repo, onHide)
            else -> when (val p = page) {
                H.List -> { BackHandler { onHide() }; ChatList(repo, onOpen = { page = H.Chat(it) }, onNew = { page = H.New }, onSettings = { page = H.Settings }, onHide = onHide) }
                is H.Chat -> { BackHandler { page = H.List }; ChatScreen(repo, p.id) { page = H.List } }
                H.New -> { BackHandler { page = H.List }; NewChat(repo, onBack = { page = H.List }, onStarted = { page = H.Chat(it) }) }
                H.Settings -> { BackHandler { page = H.List }; Settings(repo, secret) { page = H.List } }
            }
        }
    }
}

@Composable
private fun Centered(text: String, onHide: () -> Unit, onRetry: (() -> Unit)? = null) {
    BackHandler { onHide() }
    Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.DarkGray)
        Spacer(Modifier.height(16.dp))
        if (onRetry != null) Button(onClick = onRetry) { Text("Try again") }
        TextButton(onClick = onHide) { Text("Back to notes") }
    }
}

@Composable
private fun UsernameSetup(repo: ChatRepo, onHide: () -> Unit) {
    BackHandler { onHide() }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.White).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Pick a username", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = WaGreen)
        Spacer(Modifier.height(8.dp))
        Text("No phone number or email. Friends find you by this name only.", color = Color.Gray)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(name, { name = it; err = null }, prefix = { Text("@") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = err != null,
            supportingText = { err?.let { Text(it) } })
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy, onClick = { busy = true; scope.launch { err = repo.claimUsername(name); busy = false } }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving..." else "Continue") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatList(repo: ChatRepo, onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onHide: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var chatMenu by remember { mutableStateOf<ChatItem?>(null) }
    var confirmChat by remember { mutableStateOf<Pair<ChatItem, Boolean>?>(null) } // (chat, forEveryone)
    var deleting by remember { mutableStateOf<String?>(null) }
    val visible = repo.visibleChats()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Chats", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WaGreen),
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
                        DropdownMenuItem(text = { Text("Hide") }, onClick = { menu = false; onHide() })
                    }
                })
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew, containerColor = Color(0xFF25D366)) { Icon(Icons.Default.Chat, "New chat", tint = Color.White) } },
        containerColor = Color.White
    ) { pad ->
        val missed = CallManager.missed.value
        if (visible.isEmpty() && missed.isEmpty())
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("No chats yet. Tap the button to message a username.", color = Color.Gray, modifier = Modifier.padding(32.dp)) }
        LazyColumn(Modifier.padding(pad)) {
            // v1.4: quiet missed-call entries for calls that rang and ended while chats were locked (memory only).
            items(missed, key = { "missed_" + it.callId }) { m ->
                Row(Modifier.fillMaxWidth().background(Color(0xFFFFF5F5)).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (m.video) Icons.Default.Videocam else Icons.Default.Call, null, tint = Color(0xFFE53935), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Missed ${if (m.video) "video" else "voice"} call from @${m.peerName} · ${time(m.at)}", color = Color.Gray, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { CallManager.dismissMissed(m.callId) }) { Icon(Icons.Default.Close, "Dismiss", tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                }
            }
            items(visible, key = { it.id }) { c ->
                val unread = repo.unread[c.id] ?: 0
                // v1.6: WhatsApp-style row: round avatar, name + time on top, preview + unread badge below, inset divider.
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (deleting != c.id) onOpen(c.id) }, onLongClick = { if (deleting == null) chatMenu = c })
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.peerName, 52)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("@${c.peerName}", fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold, fontSize = 16.5.sp, color = WaText,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(time(c.updated), fontSize = 12.sp, color = if (unread > 0) WaLight else WaSub,
                                fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (prev, fromMe) = repo.previewOf(c)
                            Text(if (deleting == c.id) "Deleting..." else (if (fromMe && prev.isNotBlank()) "You: " else "") + prev.ifBlank { "Say hi 👋" },
                                color = if (unread > 0) WaText else WaSub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (unread > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(WaLight).padding(horizontal = 5.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text(if (unread > 99) "99+" else "$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = WaDivider, thickness = 0.8.dp)
                    }
                }
            }
        }
    }
    chatMenu?.let { c ->
        AlertDialog(onDismissRequest = { chatMenu = null },
            title = { Text("Chat with @${c.peerName}") },
            text = {
                Column {
                    TextButton(onClick = { chatMenu = null; confirmChat = c to false }, modifier = Modifier.fillMaxWidth()) { Text("Delete for me", Modifier.fillMaxWidth()) }
                    TextButton(onClick = { chatMenu = null; confirmChat = c to true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete for everyone", Modifier.fillMaxWidth(), color = Color(0xFFD32F2F)) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { chatMenu = null }) { Text("Cancel") } })
    }
    confirmChat?.let { (c, everyone) ->
        AlertDialog(onDismissRequest = { confirmChat = null },
            title = { Text(if (everyone) "Delete chat for everyone?" else "Delete chat for me?") },
            text = { Text(if (everyone) "All messages, photos and files in this chat will be permanently deleted from both phones and the server. This can't be undone."
                          else "The chat and its messages will be removed from this phone only. @${c.peerName} keeps their copy. The chat comes back if they send a new message.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmChat = null
                    if (!everyone) repo.deleteChatForMe(c.id)
                    else { deleting = c.id; scope.launch {
                        val err = repo.deleteChatForEveryone(c.id); deleting = null
                        Toast.makeText(ctx, err ?: "Chat deleted", Toast.LENGTH_LONG).show()
                    } }
                }) { Text("Delete", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { confirmChat = null }) { Text("Cancel") } })
    }
}

/** v1.6: round avatar with the first letter of the username on a colour picked from the name (same name, same colour). */
@Composable
private fun Avatar(name: String, sizeDp: Int = 46) {
    val bg = AvatarColors[Math.floorMod(name.lowercase().hashCode(), AvatarColors.size)]
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = (sizeDp * 0.42f).sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ChatScreen(repo: ChatRepo, chatId: String, onBack: () -> Unit) {
    DisposableEffect(chatId) { repo.openChat = chatId; repo.unread.remove(chatId); onDispose { repo.openChat = null } }
    val chat = repo.chats.firstOrNull { it.id == chatId }
    val msgs = repo.visibleMessages(chatId)
    // If the chat is deleted for everyone (by either person) while it's open, go back to the list.
    var seenChat by remember { mutableStateOf(false) }
    LaunchedEffect(chat == null) { if (chat != null) seenChat = true else if (seenChat) onBack() }
    var msgMenu by remember { mutableStateOf<Msg?>(null) }
    var confirmMsg by remember { mutableStateOf<Pair<Msg, Boolean>?>(null) } // (message, forEveryone)
    LaunchedEffect(msgs.size) { repo.unread.remove(chatId) }
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingUploads = repo.uploads.values.filter { it.chatId == chatId }
    val total = msgs.size + pendingUploads.size
    LaunchedEffect(total) { if (total > 0) list.animateScrollToItem(total) }
    var attachMenu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Msg?>(null) }
    var fileAction by remember { mutableStateOf<Msg?>(null) }
    var pendingCallVideo by remember { mutableStateOf<Boolean?>(null) }
    var linkToOpen by remember { mutableStateOf<String?>(null) }
    // ---- v1.7 reply to a message ----
    var replyTo by remember(chatId) { mutableStateOf<Msg?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    fun takeReply(): ReplyRef? = replyTo?.let { ReplyRef.of(it) }.also { replyTo = null }
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
    // ---- v1.5 voice messages ----
    val recorder = remember { VoiceRecorder(ctx.applicationContext) }
    val recording by recorder.recording
    var recElapsed by remember { mutableStateOf(0L) }
    // Recording counts as activity, so the idle lock never cuts a recording at 60 s (the 2-minute cap still applies).
    LaunchedEffect(recording) { while (recording) { recElapsed = recorder.elapsedMs(); PrivacyLock.touch(); delay(200) } }
    // Leaving the chat for any reason (back, lock, background, screen off) discards an unfinished recording and stops playback.
    DisposableEffect(Unit) { onDispose { recorder.cancel(); VoicePlayer.stop() } }
    fun sendRecording() {
        val r = recorder.stop() ?: return toast("That was too short. Tap the mic, speak, then tap send.")
        val reply = takeReply()
        scope.launch { repo.sendVoice(chatId, r.first, r.second, reply)?.let { toast(it) } }
    }
    fun startRecording() {
        if (CallManager.active) return toast("You can't record a voice message during a call.")
        if (chat == null || chat.peerPub.isBlank()) return toast("Chat not ready yet. Try again in a moment.")
        VoicePlayer.stop()
        PrivacyLock.touch()
        recorder.start(onMaxReached = { sendRecording() })?.let { toast(it) }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording() else toast("Allow the microphone to send voice messages.")
    }
    fun mic() {
        if (hasPermissions(ctx, arrayOf(Manifest.permission.RECORD_AUDIO))) startRecording() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun upload(uri: Uri?, image: Boolean) {
        if (uri == null) return
        val reply = takeReply()
        scope.launch { repo.sendAttachment(chatId, uri, image, reply)?.let { toast(it) } }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { upload(it, true) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { upload(it, false) }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val video = pendingCallVideo ?: return@rememberLauncherForActivityResult
        pendingCallVideo = null
        if (res.values.all { it } && chat != null) CallManager.startCall(chat, video) { toast(it) }
        else toast(if (video) "Allow microphone and camera to make video calls." else "Allow the microphone to make calls.")
    }
    fun call(video: Boolean) {
        val c = chat ?: return
        if (c.peerPub.isBlank()) { toast("Chat not ready yet. Try again in a moment."); return }
        val perms = callPermissions(video)
        if (hasPermissions(ctx, perms)) CallManager.startCall(c, video) { toast(it) } else { pendingCallVideo = video; callPerms.launch(perms) }
    }
    var saveBytes by remember { mutableStateOf<ByteArray?>(null) }
    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val b = saveBytes; saveBytes = null
        if (uri != null && b != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(b) }; true } catch (_: Exception) { false } }
            toast(if (ok) "Saved" else "Couldn't save the file")
        }
    }
    // ---- v1.6: manual "Save to gallery" (never automatic) ----
    // Uses the same loadAttachment() decrypt path the chat uses to show the photo, so it works for every photo that displays.
    var savingPhotos by remember { mutableStateOf(setOf<String>()) }
    var pendingGallery by remember { mutableStateOf<Msg?>(null) }
    fun shortToast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
    fun doSaveToGallery(m: Msg) {
        if (m.id in savingPhotos) return
        savingPhotos = savingPhotos + m.id
        PrivacyLock.touch()
        scope.launch {
            val b = repo.loadAttachment(chatId, m)
            val ok = b != null && withContext(Dispatchers.IO) { try { Gallery.saveImage(ctx, b, m.mime) } catch (_: Exception) { false } }
            savingPhotos = savingPhotos - m.id
            shortToast(when { ok -> "Saved to gallery"; b == null -> "Couldn't load the photo. Check your connection."; else -> "Couldn't save to gallery" })
        }
    }
    // Android 8-9 only. The permission dialog doesn't stop the activity, so the privacy lock needs no grace for it
    // (same as the mic/camera permission requests).
    val galleryPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val m = pendingGallery; pendingGallery = null
        if (m != null) { if (ok) doSaveToGallery(m) else shortToast("Allow storage access to save photos to the gallery.") }
    }
    fun saveToGallery(m: Msg) {
        if (Gallery.needsPermission && !hasPermissions(ctx, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
            pendingGallery = m; galleryPerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else doSaveToGallery(m)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) } },
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(chat?.peerName ?: "?", 38); Spacer(Modifier.width(10.dp))
                    Text("@${chat?.peerName ?: ""}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } },
                actions = {
                    IconButton(onClick = { call(true) }) { Icon(Icons.Default.Videocam, "Video call", tint = Color.White) }
                    IconButton(onClick = { call(false) }) { Icon(Icons.Default.Call, "Voice call", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WaGreen))
        },
        bottomBar = {
          Column(Modifier.fillMaxWidth().background(WaBg).navigationBarsPadding().imePadding()) {
            // v1.7: WhatsApp-style quoted preview while composing a reply (X cancels).
            replyTo?.let { r ->
                ReplyComposeStrip(r, repo.myUid.value, chat?.peerName ?: "", Modifier.padding(start = 6.dp, end = 64.dp, top = 6.dp)) { replyTo = null }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
              if (recording) {
                IconButton(onClick = { recorder.cancel() }) { Icon(Icons.Default.Delete, "Cancel recording", tint = Color(0xFFD32F2F)) }
                Row(Modifier.weight(1f).height(52.dp).shadow(1.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Color.White).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE53935)))
                    Spacer(Modifier.width(10.dp))
                    Text("Recording  ${dur(recElapsed)} / ${dur(MAX_VOICE_MS)}", color = Color(0xFF54656F))
                }
                Spacer(Modifier.width(6.dp))
                FloatingActionButton(onClick = { sendRecording() }, containerColor = WaGreen, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Default.Send, "Send voice message", tint = Color.White)
                }
              } else {
                // v1.6: WhatsApp-style rounded "pill" holding the text field and the attach button.
                Row(Modifier.weight(1f).shadow(1.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Color.White).padding(start = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Plain text field: pasted links are kept exactly as pasted (nothing filters or rewrites them).
                    // Typing counts as activity for the idle lock (soft-keyboard input doesn't reach onUserInteraction).
                    TextField(text, { text = it; PrivacyLock.touch() }, placeholder = { Text("Message", color = WaSub) }, modifier = Modifier.weight(1f), maxLines = 5,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    Box {
                        IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.AttachFile, "Attach", tint = WaSub) }
                        DropdownMenu(attachMenu, { attachMenu = false }) {
                            DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = {
                                attachMenu = false; App.allowExternal(); pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) }, onClick = {
                                attachMenu = false; App.allowExternal(); pickFile.launch("*/*") })
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                val empty = text.isBlank()
                FloatingActionButton(onClick = { if (empty) mic() else { repo.send(chatId, text.trim(), takeReply()); text = "" } }, containerColor = WaGreen, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                    Icon(if (empty) Icons.Default.Mic else Icons.Default.Send, if (empty) "Record voice message" else "Send", tint = Color.White)
                }
              }
            }
          }
        },
        containerColor = WaBg
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
            item { Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text("🔒 Messages are end-to-end encrypted.", fontSize = 12.sp, color = Color(0xFF54656F), textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color(0xFFFFF3C4), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            } }
            itemsIndexed(msgs, key = { _, it -> it.id }) { i, m ->
                val mine = m.from == repo.myUid.value
                // v1.6: day chip when the day changes; bubble tail only on the first message of a run from the same person.
                val prev = msgs.getOrNull(i - 1)
                val newDay = prev == null || !sameDay(prev.ts, m.ts)
                val first = newDay || prev?.from != m.from
                if (newDay) DayChip(m.ts)
                // v1.7: swipe right on a bubble to reply (and a brief highlight when jumped to from a quote).
                SwipeToReply(onReply = { replyTo = m; haptic.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                Row(Modifier.fillMaxWidth().background(if (highlightId == m.id) Color(0x3353BDEB) else Color.Transparent)
                    .padding(top = if (first) 6.dp else 1.5.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    val shape = BubbleShape(mine, first)
                    Column(Modifier.widthIn(max = 300.dp).then(if (m.reply != null) Modifier.width(IntrinsicSize.Max) else Modifier)
                        .shadow(0.6.dp, shape).clip(shape).background(if (mine) OutBubble else InBubble)
                        .combinedClickable(onClick = {}, onLongClick = { msgMenu = m })
                        .padding(start = if (mine) 4.dp else 4.dp + TAIL_DP.dp, end = if (mine) 4.dp + TAIL_DP.dp else 4.dp, top = 4.dp, bottom = 3.dp)
                        .padding(horizontal = if (m.type == "image") 0.dp else 5.dp)) {
                        m.reply?.let { r ->
                            QuoteBlock(r, repo.myUid.value, chat?.peerName ?: "", deleted = repo.replyOriginalDeleted(chatId, r), mine = mine,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                val idx = msgs.indexOfFirst { it.id == r.id }
                                when {
                                    idx >= 0 -> scope.launch {
                                        list.animateScrollToItem(idx + 1) // +1 for the encryption notice item at the top
                                        highlightId = r.id; delay(1200); if (highlightId == r.id) highlightId = null
                                    }
                                    repo.replyOriginalDeleted(chatId, r) -> {}
                                    else -> toast("The original message isn't loaded on this phone.")
                                }
                            }
                        }
                        when (m.type) {
                            "image" -> ImageThumb(repo, chatId, m, saving = m.id in savingPhotos, onSave = { saveToGallery(m) }, onLong = { msgMenu = m }) { viewing = m }
                            "file" -> if (m.voice) VoiceRow(repo, chatId, m, onLong = { msgMenu = m }) { toast(it) }
                                else FileRow(m, repo.downloads[m.id], onLong = { msgMenu = m }) { if (m.available) fileAction = m else toast("This file isn't available. It was sent from an older version of the app.") }
                            else -> LinkText(m.text, onLong = { msgMenu = m }) { linkToOpen = it }
                        }
                        Row(Modifier.align(Alignment.End).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(clock(m.ts), fontSize = 11.sp, color = WaSub)
                            if (mine) {
                                Spacer(Modifier.width(4.dp))
                                Icon(when { m.pending -> Icons.Default.Schedule; m.delivered -> Icons.Default.DoneAll; else -> Icons.Default.Done }, null,
                                    tint = if (m.delivered) Color(0xFF53BDEB) else Color.Gray, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                }
            }
            items(pendingUploads, key = { "up_" + it.id }) { u ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                    val shape = BubbleShape(mine = true, tail = true)
                    Column(Modifier.widthIn(min = 180.dp, max = 300.dp).shadow(0.6.dp, shape).clip(shape).background(OutBubble)
                        .padding(start = 10.dp, end = 10.dp + TAIL_DP.dp, top = 10.dp, bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when { u.isVoice -> Icons.Default.Mic; u.isImage -> Icons.Default.Image; else -> Icons.Default.InsertDriveFile }, null, tint = Color(0xFF54656F), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(when { u.isVoice -> "Sending voice message..."; u.isImage -> "Sending photo..."; else -> u.name }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(6.dp))
                        if (u.progress <= 0f) LinearProgressIndicator(Modifier.fillMaxWidth(), color = WaGreen)
                        else LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth(), color = WaGreen)
                        Text("🔒 Encrypted upload " + (u.progress * 100).toInt() + "%", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }
    }
    viewing?.let { m -> FullImage(repo, chatId, m, saving = m.id in savingPhotos, onSave = { saveToGallery(m) }) { viewing = null } }
    linkToOpen?.let { url ->
        AlertDialog(onDismissRequest = { linkToOpen = null },
            title = { Text("Open link?") },
            text = { Text(url + "\n\nIt opens in your browser. Chats lock when you leave the app.", fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { linkToOpen = null; openLink(ctx, url) { toast(it) } }) { Text("Open") } },
            dismissButton = { TextButton(onClick = {
                linkToOpen = null
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", url))
                toast("Link copied")
            }) { Text("Copy") } })
    }
    msgMenu?.let { m ->
        val mine = m.from == repo.myUid.value
        AlertDialog(onDismissRequest = { msgMenu = null },
            title = { Text("Message") },
            text = {
                Column {
                    TextButton(onClick = { msgMenu = null; replyTo = m }, modifier = Modifier.fillMaxWidth()) { Text("Reply", Modifier.fillMaxWidth()) }
                    TextButton(onClick = { msgMenu = null; confirmMsg = m to false }, modifier = Modifier.fillMaxWidth()) { Text("Delete for me", Modifier.fillMaxWidth()) }
                    if (mine) TextButton(onClick = { msgMenu = null; confirmMsg = m to true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete for everyone", Modifier.fillMaxWidth(), color = Color(0xFFD32F2F)) }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { msgMenu = null }) { Text("Cancel") } })
    }
    confirmMsg?.let { (m, everyone) ->
        AlertDialog(onDismissRequest = { confirmMsg = null },
            title = { Text(if (everyone) "Delete for everyone?" else "Delete for me?") },
            text = { Text(if (everyone) "This message" + (if (m.type != "text") " and its encrypted attachment" else "") + " will be permanently deleted from both phones and the server."
                          else "This message will be removed from this phone only. The other person still has it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMsg = null
                    if (VoicePlayer.current.value == m.id) VoicePlayer.stop()
                    if (replyTo?.id == m.id) replyTo = null
                    if (!everyone) repo.deleteMessageForMe(chatId, m)
                    else scope.launch { repo.deleteMessageForEveryone(chatId, m)?.let { toast(it) } }
                }) { Text("Delete", color = Color(0xFFD32F2F)) }
            },
            dismissButton = { TextButton(onClick = { confirmMsg = null }) { Text("Cancel") } })
    }
    fileAction?.let { m ->
        AlertDialog(onDismissRequest = { fileAction = null },
            title = { Text(m.name.ifBlank { "File" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(sizeText(m.size) + (if (m.mime.isNotBlank()) " · " + m.mime else "")) },
            confirmButton = {
                TextButton(onClick = {
                    fileAction = null
                    scope.launch {
                        val b = repo.loadAttachment(chatId, m) ?: return@launch toast("Couldn't download the file. Check your connection.")
                        saveBytes = b; App.allowExternal(); saveDoc.launch(m.name.ifBlank { "file" })
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    fileAction = null
                    scope.launch {
                        val b = repo.loadAttachment(chatId, m) ?: return@launch toast("Couldn't download the file. Check your connection.")
                        val f = withContext(Dispatchers.IO) {
                            val dir = File(ctx.cacheDir, "open/${m.id}").apply { mkdirs() }
                            File(dir, m.name.ifBlank { "file" }.replace('/', '_')).also { it.writeBytes(b) }
                        }
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try { App.allowExternal(); ctx.startActivity(Intent.createChooser(i, "Open with")) } catch (_: Exception) { toast("No app on this phone can open this file. Use Save instead.") }
                    }
                }) { Text("Open") }
            })
    }
}

// ===================== v1.5: clickable links =====================

private const val LINK_TLDS = "com|in|org|net|io|dev|app|co|me|edu|gov|info|xyz|ai|ly|gg|tv|us|uk|so|to|link|site|online|tech|store|page|live|news|blog"
private val UrlRegex = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+|\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:$LINK_TLDS)\b(?:[/?#][^\s<>"]*)?""")

/** Finds web links in a message: http(s)://..., www.... and bare domains like example.com/page. Emails are skipped.
 *  Returns (character range, url to open); bare links get https://. */
internal fun findLinks(text: String): List<Pair<IntRange, String>> = UrlRegex.findAll(text).mapNotNull { m ->
    val start = m.range.first
    if (start > 0 && (text[start - 1] == '@' || text[start - 1] == '.' || text[start - 1] == '/')) return@mapNotNull null
    var v = m.value
    while (v.isNotEmpty() && v.last() in ".,;:!?)]}'\"") {
        if (v.last() == ')' && v.count { it == '(' } >= v.count { it == ')' }) break
        v = v.dropLast(1)
    }
    if (v.length < 4) return@mapNotNull null
    val url = if (v.startsWith("http://", true) || v.startsWith("https://", true)) v else "https://$v"
    IntRange(start, start + v.length - 1) to url
}.toList()

/** Opens a link in the browser. Only http/https are ever opened (no intent:, file:, javascript: etc.). */
private fun openLink(ctx: android.content.Context, url: String, onError: (String) -> Unit) {
    val u = Uri.parse(url)
    if (u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank()) return onError("That link can't be opened.")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) { onError("No browser on this phone can open this link.") }
}

/** Message text with links underlined; tap a link to open it, long-press anywhere for the message menu. */
@Composable
private fun LinkText(text: String, onLong: () -> Unit, onLink: (String) -> Unit) {
    val links = remember(text) { findLinks(text) }
    if (links.isEmpty()) { Text(text, fontSize = 15.sp); return }
    val annotated = remember(text) {
        buildAnnotatedString {
            append(text)
            links.forEach { (r, u) ->
                addStyle(SpanStyle(color = Color(0xFF027EB5), textDecoration = TextDecoration.Underline), r.first, r.last + 1)
                addStringAnnotation("URL", u, r.first, r.last + 1)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(annotated, fontSize = 15.sp, onTextLayout = { layout = it }, modifier = Modifier.pointerInput(annotated) {
        detectTapGestures(onLongPress = { onLong() }, onTap = { pos ->
            val l = layout ?: return@detectTapGestures
            val off = l.getOffsetForPosition(pos)
            annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { onLink(it.item) }
        })
    })
}

// ===================== v1.5: voice messages =====================

private fun dur(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceRow(repo: ChatRepo, chatId: String, m: Msg, onLong: () -> Unit, toast: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val isCur = VoicePlayer.current.value == m.id
    val playing = isCur && VoicePlayer.playing.value
    var loading by remember(m.id) { mutableStateOf(false) }
    val dl = repo.downloads[m.id]
    LaunchedEffect(playing) { while (playing) { VoicePlayer.tick(); delay(250) } }
    Row(Modifier.width(230.dp).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = {}, onLongClick = onLong).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            PrivacyLock.touch()
            when {
                isCur -> VoicePlayer.toggle()
                !m.available -> toast("This voice message isn't available.")
                loading -> {}
                else -> { loading = true; scope.launch {
                    val b = repo.loadAttachment(chatId, m)
                    loading = false
                    if (b == null) toast("Couldn't download the voice message. Check your connection.")
                    else if (!VoicePlayer.play(m.id, b)) toast("Couldn't play this voice message.")
                } }
            }
        }) {
            if (loading) CircularProgressIndicator(progress = { dl ?: 0f }, color = WaGreen, modifier = Modifier.size(24.dp))
            else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = WaGreen, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f)) {
            val total = if (isCur && VoicePlayer.durationMs.value > 0) VoicePlayer.durationMs.value else m.durationMs
            val pos = if (isCur) VoicePlayer.positionMs.value else 0L
            LinearProgressIndicator(progress = { if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(end = 6.dp), color = WaGreen, trackColor = Color(0x33000000))
            Spacer(Modifier.height(4.dp))
            Text(if (isCur) "${dur(pos)} / ${dur(total)}" else dur(total), fontSize = 11.sp, color = Color.Gray)
        }
        Icon(Icons.Default.Mic, null, tint = Color(0xFF54656F), modifier = Modifier.size(18.dp))
    }
}

private fun sizeText(b: Long): String = when {
    b >= 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageThumb(repo: ChatRepo, chatId: String, m: Msg, saving: Boolean, onSave: () -> Unit, onLong: () -> Unit, onOpen: () -> Unit) {
    var bmp by remember(m.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    val progress = repo.downloads[m.id]
    LaunchedEffect(m.id, m.chunkCount) {
        val b = repo.loadAttachment(chatId, m)
        if (b == null) failed = true else bmp = withContext(Dispatchers.Default) { decodeSampled(b, 800) }.also { if (it == null) failed = true }
    }
    val b = bmp
    if (b != null) Box {
        Image(b.asImageBitmap(), "Photo", contentScale = ContentScale.Crop,
            modifier = Modifier.width(240.dp).heightIn(max = 320.dp).aspectRatio(b.width.toFloat() / b.height.coerceAtLeast(1)).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onOpen, onLongClick = onLong))
        // v1.6: manual Save to gallery (only shown once the photo has loaded, i.e. it could be decrypted).
        SaveButton(saving, onSave, Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
    else Box(Modifier.size(240.dp, 180.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFDFE5E7)).combinedClickable(onClick = {}, onLongClick = onLong), contentAlignment = Alignment.Center) {
        when {
            failed && !m.available -> Text("📷 Photo unavailable\n(sent from an older version)", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
            failed -> Text("📷 Photo couldn't load", color = Color.Gray, fontSize = 13.sp)
            progress != null && progress > 0f -> CircularProgressIndicator(progress = { progress }, color = WaGreen, modifier = Modifier.size(28.dp))
            else -> CircularProgressIndicator(color = WaGreen, modifier = Modifier.size(28.dp))
        }
    }
}

/** v1.6: small round "Save to gallery" button drawn over a photo. */
@Composable
private fun SaveButton(saving: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).clip(CircleShape).background(Color(0x99000000)).clickable(enabled = !saving) { onSave() }, contentAlignment = Alignment.Center) {
        if (saving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Icon(Icons.Default.Download, "Save to gallery", tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FullImage(repo: ChatRepo, chatId: String, m: Msg, saving: Boolean, onSave: () -> Unit, onClose: () -> Unit) {
    var bmp by remember(m.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(m.id) { repo.loadAttachment(chatId, m)?.let { b -> bmp = withContext(Dispatchers.Default) { decodeSampled(b, 2048) } } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { onClose() }, contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else CircularProgressIndicator(color = Color.White)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            if (b != null) TextButton(onClick = onSave, enabled = !saving, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                if (saving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.Default.Download, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Save to gallery", color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(m: Msg, progress: Float?, onLong: () -> Unit, onClick: () -> Unit) {
    Row(Modifier.widthIn(min = 200.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x14000000)).combinedClickable(onClick = onClick, onLongClick = onLong).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.InsertDriveFile, null, tint = WaGreen, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(m.name.ifBlank { "File" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when {
                progress != null -> "🔒 Downloading " + (progress * 100).toInt() + "%"
                !m.available -> "Unavailable (sent from an older version)"
                else -> sizeText(m.size) + " · tap to save or open"
            }, fontSize = 11.sp, color = Color.Gray)
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = WaGreen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChat(repo: ChatRepo, onBack: () -> Unit, onStarted: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("New chat", color = Color.White) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = WaGreen))
    }, containerColor = Color.White) { pad ->
        Column(Modifier.padding(pad).padding(20.dp)) {
            Text("Enter their exact username", color = Color.Gray)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it; err = null }, prefix = { Text("@") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = err != null, supportingText = { err?.let { Text(it) } })
            Spacer(Modifier.height(10.dp))
            Button(enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true
                scope.launch { val r = repo.startChat(name); busy = false; if (r.startsWith("!")) err = r.drop(1) else onStarted(r) }
            }) { Text(if (busy) "Finding..." else "Start chat") }
            Spacer(Modifier.height(20.dp))
            Text("Your username: @${repo.myName.value}", color = Color.Gray)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Settings(repo: ChatRepo, secret: SecretStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var newWord by remember { mutableStateOf("") }
    var confirmWord by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var notifOn by remember { mutableStateOf(secret.notificationsOn) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val wordOk = newWord.trim().length >= 3 && newWord.trim().lowercase() == confirmWord.trim().lowercase()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings", color = Color.White) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = WaGreen))
    }, containerColor = Color.White) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Username", fontWeight = FontWeight.SemiBold); Text("@${repo.myName.value}", color = Color.Gray)

            Spacer(Modifier.height(24.dp))
            Text("Privacy", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = WaGreen)

            Spacer(Modifier.height(12.dp))
            Text("Passcode", fontWeight = FontWeight.SemiBold)
            Text("Typing this in Search notes opens your chats. " + (if (secret.isDefault) "You're still using the default passcode." else "Only a salted hash of it is stored on this phone."),
                color = Color.Gray, fontSize = 13.sp)
            OutlinedTextField(newWord, { newWord = it; saved = false }, label = { Text("New passcode") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmWord, { confirmWord = it; saved = false }, label = { Text("Confirm new passcode") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                isError = confirmWord.isNotBlank() && !wordOk,
                supportingText = { if (confirmWord.isNotBlank() && newWord.trim().length >= 3 && !wordOk) Text("Passcodes don't match") else Text("At least 3 characters. Not case-sensitive.") })
            Button(enabled = wordOk, onClick = { secret.set(newWord); newWord = ""; confirmWord = ""; saved = true }) { Text("Change passcode") }
            if (saved) Text("Saved. Use the new passcode next time.", color = WaGreen)

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notifications", fontWeight = FontWeight.SemiBold)
                    Text("New messages, voice messages and calls show only \"$NOTIF_TEXT: $NOTIF_BODY\", never a name or message text, and nothing on the lock screen. Turn off to get no alerts at all.",
                        color = Color.Gray, fontSize = 13.sp)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = notifOn, onCheckedChange = { notifOn = it; secret.notificationsOn = it })
            }

            Spacer(Modifier.height(20.dp))
            Text("Call history", fontWeight = FontWeight.SemiBold)
            Text("Calls are deleted from the server when they end. This also removes any older call records.", color = Color.Gray, fontSize = 13.sp)
            OutlinedButton(enabled = !clearing, onClick = { confirmClear = true }) { Text(if (clearing) "Clearing..." else "Clear call history") }

            Spacer(Modifier.height(24.dp))
            Text("Chats lock automatically after 1 minute idle, when you leave the app (Home, Recents, switching apps), when the screen turns off, or when you press back from the chat list. Screenshots and Recents previews are blocked.",
                color = Color.Gray, fontSize = 13.sp)
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear call history?") },
        text = { Text("Every call record you were part of is deleted from the server. A call in progress isn't affected.") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false; clearing = true
                scope.launch {
                    val r = repo.clearCallHistory(); clearing = false
                    CallManager.clearMissed()
                    Toast.makeText(ctx, r.fold({ if (it == 0) "No call history to clear" else "Call history cleared" }, { it.message ?: "Couldn't clear call history" }), Toast.LENGTH_LONG).show()
                }
            }) { Text("Clear", color = Color(0xFFD32F2F)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

private fun time(t: Long): String {
    val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
    val same = now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    return SimpleDateFormat(if (same) "h:mm a" else "d MMM", Locale.getDefault()).format(Date(t))
}

// ===================== v1.6: WhatsApp-style bubbles (visual only) =====================

private const val TAIL_DP = 7

/** Rounded bubble; when [tail] is set, the top corner on the sender's side becomes a small pointed tail (WhatsApp style).
 *  Space for the tail is always reserved so bubbles in a run line up. */
private class BubbleShape(private val mine: Boolean, private val tail: Boolean) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                               density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val t = with(density) { TAIL_DP.dp.toPx() }
        val r = with(density) { 10.dp.toPx() }
        val left = if (mine) 0f else t
        val right = if (mine) size.width - t else size.width
        val rr = androidx.compose.ui.geometry.CornerRadius(r, r)
        val zero = androidx.compose.ui.geometry.CornerRadius.Zero
        val path = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(left, 0f, right, size.height,
                topLeftCornerRadius = if (tail && !mine) zero else rr, topRightCornerRadius = if (tail && mine) zero else rr,
                bottomRightCornerRadius = rr, bottomLeftCornerRadius = rr))
            if (tail) {
                if (mine) { moveTo(right - 1f, 0f); lineTo(size.width, 0f); lineTo(right - 1f, t * 1.4f); close() }
                else { moveTo(left + 1f, 0f); lineTo(0f, 0f); lineTo(left + 1f, t * 1.4f); close() }
            }
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/** "Today" / "Yesterday" / date chip between days, like WhatsApp. */
@Composable
private fun DayChip(t: Long) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Text(dayLabel(t), fontSize = 12.sp, color = Color(0xFF54656F),
            modifier = Modifier.shadow(0.5.dp, RoundedCornerShape(8.dp)).background(Color(0xFFFFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }; val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(t: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(t, now) -> "Today"
        sameDay(t, now - 86_400_000L) -> "Yesterday"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(t))
    }
}

/** Time shown inside a bubble (the day is in the chip above). */
private fun clock(t: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))

// ===================== v1.7: reply to a message =====================

private val ReplyBarColor = Color(0xFF06CF9C)
private val ReplyNameColor = Color(0xFF7F66FF)

private fun replyName(from: String, myUid: String?, peerName: String): String =
    if (from.isNotBlank() && from == myUid) "You" else if (peerName.isNotBlank()) "@$peerName" else ""

private fun replySnippet(kind: String, text: String): String = when (kind) {
    "image" -> "📷 Photo"; "voice" -> "🎤 Voice message"; "file" -> "📎 File"; else -> text.replace('\n', ' ')
}

/** Swipe a bubble right past ~64 dp to reply; it springs back. Vertical scrolling and taps/long-presses are unaffected. */
@Composable
private fun SwipeToReply(onReply: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val trigger = with(LocalDensity.current) { 64.dp.toPx() }
    val max = with(LocalDensity.current) { 96.dp.toPx() }
    var fired by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { fired = false },
            onDragEnd = { if (offset.value >= trigger) onReply(); scope.launch { offset.animateTo(0f, tween(180)) } },
            onDragCancel = { scope.launch { offset.animateTo(0f, tween(180)) } },
            onHorizontalDrag = { change, dx ->
                val next = (offset.value + dx).coerceIn(0f, max)
                if (next != offset.value) change.consume()
                scope.launch { offset.snapTo(next) }
            })
    }) {
        val progress = (offset.value / trigger).coerceIn(0f, 1f)
        if (offset.value > 0f) Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(30.dp).alpha(progress)
            .clip(CircleShape).background(Color(0x22000000)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Reply, "Reply", tint = Color(0xFF54656F), modifier = Modifier.size(18.dp))
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/** Quoted block shown on top of a reply bubble. Tap jumps to the original. */
@Composable
private fun QuoteBlock(r: ReplyRef, myUid: String?, peerName: String, deleted: Boolean, mine: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val name = replyName(r.from, myUid, peerName)
    val barColor = if (r.from == myUid) ReplyBarColor else ReplyNameColor
    Row(modifier.height(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).background(if (mine) Color(0x1A000000) else Color(0x0F000000)).clickable { onClick() }) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(barColor))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            if (name.isNotBlank()) Text(name, color = barColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (deleted) "🚫 Message deleted" else replySnippet(r.kind, r.text).ifBlank { "Message" }, color = WaSub, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Preview strip above the input while composing a reply. */
@Composable
private fun ReplyComposeStrip(m: Msg, myUid: String?, peerName: String, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    val r = ReplyRef.of(m)
    val name = replyName(r.from, myUid, peerName)
    val barColor = if (r.from == myUid) ReplyBarColor else ReplyNameColor
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).shadow(1.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Color.White),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(barColor))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(name.ifBlank { "Reply" }, color = barColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(replySnippet(r.kind, r.text).ifBlank { "Message" }, color = WaSub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Cancel reply", tint = WaSub, modifier = Modifier.size(18.dp)) }
    }
}
