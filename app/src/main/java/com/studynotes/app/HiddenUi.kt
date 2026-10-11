package com.studynotes.app

import android.content.Intent
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
        if (visible.isEmpty())
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("No chats yet. Tap the button to message a username.", color = Color.Gray, modifier = Modifier.padding(32.dp)) }
        LazyColumn(Modifier.padding(pad)) {
            items(visible, key = { it.id }) { c ->
                val unread = repo.unread[c.id] ?: 0
                Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (deleting != c.id) onOpen(c.id) }, onLongClick = { if (deleting == null) chatMenu = c })
                    .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.peerName)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@${c.peerName}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        val (prev, fromMe) = repo.previewOf(c)
                        Text(if (deleting == c.id) "Deleting..." else (if (fromMe && prev.isNotBlank()) "You: " else "") + prev.ifBlank { "Say hi 👋" },
                            color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(time(c.updated), fontSize = 12.sp, color = if (unread > 0) Color(0xFF25D366) else Color.Gray)
                        if (unread > 0) Box(Modifier.padding(top = 4.dp).size(20.dp).clip(CircleShape).background(Color(0xFF25D366)), contentAlignment = Alignment.Center) {
                            Text("$unread", color = Color.White, fontSize = 11.sp)
                        }
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

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFDFE5E7)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color(0xFF54656F), fontSize = 18.sp)
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
    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
    fun upload(uri: Uri?, image: Boolean) {
        if (uri == null) return
        scope.launch { repo.sendAttachment(chatId, uri, image)?.let { toast(it) } }
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
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) } },
                title = { Row(verticalAlignment = Alignment.CenterVertically) { Avatar(chat?.peerName ?: "?"); Spacer(Modifier.width(10.dp)); Text("@${chat?.peerName ?: ""}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                actions = {
                    IconButton(onClick = { call(true) }) { Icon(Icons.Default.Videocam, "Video call", tint = Color.White) }
                    IconButton(onClick = { call(false) }) { Icon(Icons.Default.Call, "Voice call", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WaGreen))
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(WaBg).navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Default.AttachFile, "Attach", tint = Color(0xFF54656F)) }
                    DropdownMenu(attachMenu, { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = {
                            attachMenu = false; App.allowExternal(); pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) }, onClick = {
                            attachMenu = false; App.allowExternal(); pickFile.launch("*/*") })
                    }
                }
                TextField(text, { text = it }, placeholder = { Text("Message") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = 5,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                Spacer(Modifier.width(6.dp))
                FloatingActionButton(onClick = { if (text.isNotBlank()) { repo.send(chatId, text.trim()); text = "" } }, containerColor = WaGreen, shape = CircleShape, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Send, "Send", tint = Color.White)
                }
            }
        },
        containerColor = WaBg
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = list, contentPadding = PaddingValues(8.dp)) {
            item { Text("🔒 Messages are end-to-end encrypted.", fontSize = 12.sp, color = Color(0xFF54656F),
                modifier = Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFFFFF3C4), RoundedCornerShape(8.dp)).padding(8.dp)) }
            items(msgs, key = { it.id }) { m ->
                val mine = m.from == repo.myUid.value
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(10.dp)).background(if (mine) OutBubble else Color.White)
                        .combinedClickable(onClick = {}, onLongClick = { msgMenu = m }).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        when (m.type) {
                            "image" -> ImageThumb(repo, chatId, m, onLong = { msgMenu = m }) { viewing = m }
                            "file" -> FileRow(m, repo.downloads[m.id], onLong = { msgMenu = m }) { if (m.available) fileAction = m else toast("This file isn't available. It was sent from an older version of the app.") }
                            else -> Text(m.text, fontSize = 15.sp)
                        }
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            Text(time(m.ts), fontSize = 11.sp, color = Color.Gray)
                            if (mine) {
                                Spacer(Modifier.width(4.dp))
                                Icon(when { m.pending -> Icons.Default.Schedule; m.delivered -> Icons.Default.DoneAll; else -> Icons.Default.Done }, null,
                                    tint = if (m.delivered) Color(0xFF53BDEB) else Color.Gray, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            items(pendingUploads, key = { "up_" + it.id }) { u ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                    Column(Modifier.widthIn(min = 180.dp, max = 280.dp).background(OutBubble, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (u.isImage) Icons.Default.Image else Icons.Default.InsertDriveFile, null, tint = Color(0xFF54656F), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (u.isImage) "Sending photo..." else u.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    viewing?.let { m -> FullImage(repo, chatId, m) { viewing = null } }
    msgMenu?.let { m ->
        val mine = m.from == repo.myUid.value
        AlertDialog(onDismissRequest = { msgMenu = null },
            title = { Text("Message") },
            text = {
                Column {
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
private fun ImageThumb(repo: ChatRepo, chatId: String, m: Msg, onLong: () -> Unit, onOpen: () -> Unit) {
    var bmp by remember(m.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    val progress = repo.downloads[m.id]
    LaunchedEffect(m.id, m.chunkCount) {
        val b = repo.loadAttachment(chatId, m)
        if (b == null) failed = true else bmp = withContext(Dispatchers.Default) { decodeSampled(b, 800) }.also { if (it == null) failed = true }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), "Photo", contentScale = ContentScale.Crop,
        modifier = Modifier.width(240.dp).heightIn(max = 320.dp).aspectRatio(b.width.toFloat() / b.height.coerceAtLeast(1)).clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onOpen, onLongClick = onLong))
    else Box(Modifier.size(240.dp, 180.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFDFE5E7)).combinedClickable(onClick = {}, onLongClick = onLong), contentAlignment = Alignment.Center) {
        when {
            failed && !m.available -> Text("📷 Photo unavailable\n(sent from an older version)", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
            failed -> Text("📷 Photo couldn't load", color = Color.Gray, fontSize = 13.sp)
            progress != null && progress > 0f -> CircularProgressIndicator(progress = { progress }, color = WaGreen, modifier = Modifier.size(28.dp))
            else -> CircularProgressIndicator(color = WaGreen, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun FullImage(repo: ChatRepo, chatId: String, m: Msg, onClose: () -> Unit) {
    var bmp by remember(m.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(m.id) { repo.loadAttachment(chatId, m)?.let { b -> bmp = withContext(Dispatchers.Default) { decodeSampled(b, 2048) } } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable { onClose() }, contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(b.asImageBitmap(), "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else CircularProgressIndicator(color = Color.White)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
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
                    Text("New messages and calls show only \"$NOTIF_TEXT\", with no sender or message text. Turn off to get no alerts at all.",
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
            Text("Chats lock automatically after 60 seconds idle, when you leave the app, or when you press back from the chat list. Screenshots are blocked.",
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
