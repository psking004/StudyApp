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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatList(repo: ChatRepo, onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onHide: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
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
        if (repo.chats.isEmpty())
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("No chats yet. Tap the button to message a username.", color = Color.Gray, modifier = Modifier.padding(32.dp)) }
        LazyColumn(Modifier.padding(pad)) {
            items(repo.chats, key = { it.id }) { c ->
                val unread = repo.unread[c.id] ?: 0
                Row(Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.peerName)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@${c.peerName}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        val prev = repo.preview(c)
                        Text((if (c.lastFrom == repo.myUid.value && prev.isNotBlank()) "You: " else "") + prev.ifBlank { "Say hi 👋" },
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
}

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFFDFE5E7)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color(0xFF54656F), fontSize = 18.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(repo: ChatRepo, chatId: String, onBack: () -> Unit) {
    DisposableEffect(chatId) { repo.openChat = chatId; repo.unread.remove(chatId); onDispose { repo.openChat = null } }
    val chat = repo.chats.firstOrNull { it.id == chatId }
    val msgs = repo.messages[chatId] ?: emptyList()
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
                    Column(Modifier.widthIn(max = 280.dp).background(if (mine) OutBubble else Color.White, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        when (m.type) {
                            "image" -> ImageThumb(repo, chatId, m) { viewing = m }
                            "file" -> FileRow(m) { fileAction = m }
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

@Composable
private fun ImageThumb(repo: ChatRepo, chatId: String, m: Msg, onOpen: () -> Unit) {
    var bmp by remember(m.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(m.id) { mutableStateOf(false) }
    LaunchedEffect(m.id, m.path) {
        val b = repo.loadAttachment(chatId, m)
        if (b == null) failed = true else bmp = withContext(Dispatchers.Default) { decodeSampled(b, 800) }.also { if (it == null) failed = true }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), "Photo", contentScale = ContentScale.Crop,
        modifier = Modifier.width(240.dp).heightIn(max = 320.dp).aspectRatio(b.width.toFloat() / b.height.coerceAtLeast(1)).clip(RoundedCornerShape(8.dp)).clickable { onOpen() })
    else Box(Modifier.size(240.dp, 180.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFDFE5E7)), contentAlignment = Alignment.Center) {
        if (failed) Text("📷 Photo couldn't load", color = Color.Gray, fontSize = 13.sp) else CircularProgressIndicator(color = WaGreen, modifier = Modifier.size(28.dp))
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

@Composable
private fun FileRow(m: Msg, onClick: () -> Unit) {
    Row(Modifier.widthIn(min = 200.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x14000000)).clickable { onClick() }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.InsertDriveFile, null, tint = WaGreen, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(m.name.ifBlank { "File" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sizeText(m.size) + " · tap to save or open", fontSize = 11.sp, color = Color.Gray)
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
    var newWord by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings", color = Color.White) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = WaGreen))
    }, containerColor = Color.White) { pad ->
        Column(Modifier.padding(pad).padding(20.dp)) {
            Text("Username", fontWeight = FontWeight.SemiBold); Text("@${repo.myName.value}", color = Color.Gray)
            Spacer(Modifier.height(24.dp))
            Text("Secret search word", fontWeight = FontWeight.SemiBold)
            Text("Typing this in Search notes opens your chats.", color = Color.Gray, fontSize = 13.sp)
            OutlinedTextField(newWord, { newWord = it; saved = false }, label = { Text("New secret word") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(enabled = newWord.trim().length >= 3, onClick = { secret.set(newWord); newWord = ""; saved = true }) { Text("Save") }
            if (saved) Text("Saved. Use the new word next time.", color = WaGreen)
            Spacer(Modifier.height(24.dp))
            Text("Chats lock automatically after 60 seconds idle or when you leave the app.", color = Color.Gray, fontSize = 13.sp)
        }
    }
}

private fun time(t: Long): String {
    val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
    val same = now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR) && now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    return SimpleDateFormat(if (same) "h:mm a" else "d MMM", Locale.getDefault()).format(Date(t))
}
