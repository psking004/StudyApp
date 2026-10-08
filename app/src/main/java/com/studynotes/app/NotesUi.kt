package com.studynotes.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

val Paper = Color(0xFFF8F1E4)
val Amber = Color(0xFFF4B740)

@Composable
fun StudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF8A5A00), secondary = Amber, background = Paper, surface = Paper), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(store: NotesStore, onOpen: (Long?) -> Unit, onSearch: (String) -> Boolean) {
    var notes by remember { mutableStateOf(store.load()) }
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun submit() {
        if (onSearch(query)) { query = ""; applied = ""; focus.clearFocus() } else { applied = query; focus.clearFocus() }
    }
    val shown = if (applied.isBlank()) notes else notes.filter { it.title.contains(applied, true) || it.body.contains(applied, true) }
    Scaffold(
        containerColor = Paper,
        topBar = { TopAppBar(title = { Text("Study Notes", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)) },
        floatingActionButton = { FloatingActionButton(onClick = { onOpen(null) }, containerColor = Amber) { Icon(Icons.Default.Add, "New note") } }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it; if (it.isBlank()) applied = "" },
                placeholder = { Text("Search notes") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { IconButton(onClick = { submit() }) { Icon(Icons.Default.ArrowForward, "Search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (shown.isEmpty()) Text("No notes found", Modifier.padding(24.dp), color = Color.Gray)
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { n ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(n.id) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(n.title.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(n.body, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.DarkGray)
                            Spacer(Modifier.height(6.dp))
                            Text(SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(n.updated)), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(store: NotesStore, id: Long?, onDone: () -> Unit) {
    val all = remember { store.load() }
    val existing = all.firstOrNull { it.id == id }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    fun save() {
        if (title.isBlank() && body.isBlank()) { onDone(); return }
        val nid = existing?.id ?: System.currentTimeMillis()
        store.save(all.filter { it.id != nid } + Note(nid, title, body, System.currentTimeMillis()))
        onDone()
    }
    androidx.activity.compose.BackHandler { save() }
    Scaffold(containerColor = Paper, topBar = {
        TopAppBar(title = { Text(if (existing == null) "New note" else "Edit note") },
            navigationIcon = { IconButton(onClick = { save() }) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = {
                if (existing != null) IconButton(onClick = { store.save(all.filter { it.id != existing.id }); onDone() }) { Icon(Icons.Default.Delete, "Delete") }
                IconButton(onClick = { save() }) { Icon(Icons.Default.Check, "Save") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper))
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            TextField(title, { title = it }, placeholder = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = TextFieldDefaults.colors(unfocusedContainerColor = Paper, focusedContainerColor = Paper))
            TextField(body, { body = it }, placeholder = { Text("Write your notes...") }, modifier = Modifier.fillMaxSize(),
                colors = TextFieldDefaults.colors(unfocusedContainerColor = Paper, focusedContainerColor = Paper))
        }
    }
}
