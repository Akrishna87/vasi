package io.github.akrishna87.vaasi.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.akrishna87.vaasi.BookEntry
import io.github.akrishna87.vaasi.play.Reader
import io.github.akrishna87.vaasi.tts.PackState
import kotlinx.coroutines.flow.Flow

sealed interface Screen {
    data object Library : Screen
    data class Reading(val bookId: String) : Screen
    data object Voices : Screen
    data object Packs : Screen
}

/** The whole app: a small stack of screens. [incoming] carries PDFs shared or opened from elsewhere. */
@Composable
fun AppScreens(vm: AppViewModel, incoming: Flow<Uri>, openPlaying: Flow<Unit>) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Library)) }
    fun go(screen: Screen) {
        stack = stack + screen
    }
    fun back() {
        stack = stack.dropLast(1)
    }

    LaunchedEffect(Unit) {
        incoming.collect { uri -> vm.import(uri) { id -> stack = listOf(Screen.Library, Screen.Reading(id)) } }
    }
    LaunchedEffect(Unit) {
        openPlaying.collect {
            Reader.state.value.bookId?.let { id -> stack = listOf(Screen.Library, Screen.Reading(id)) }
        }
    }

    BackHandler(enabled = stack.size > 1) { back() }

    when (val screen = stack.last()) {
        Screen.Library -> LibraryScreen(
            vm,
            onOpen = { go(Screen.Reading(it)) },
            onVoices = { go(if (vm.choice.value == null) Screen.Packs else Screen.Voices) },
            onPacks = { go(Screen.Packs) },
        )
        is Screen.Reading -> BookScreen(vm, screen.bookId, onBack = ::back, onVoices = { go(Screen.Voices) })
        Screen.Voices -> VoicesScreen(vm, onBack = ::back, onPacks = { go(Screen.Packs) })
        Screen.Packs -> PacksScreen(vm, onBack = ::back)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: AppViewModel, onOpen: (String) -> Unit, onVoices: () -> Unit, onPacks: () -> Unit) {
    val books by vm.books.collectAsState()
    val importing by vm.importing.collectAsState()
    val choice by vm.choice.collectAsState()
    val packs by vm.packStates.collectAsState()
    val reader by Reader.state.collectAsState()
    var deleting by remember { mutableStateOf<BookEntry?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri, onOpen)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vaasi", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onVoices) { Icon(Icons.Rounded.RecordVoiceOver, contentDescription = "Voices") }
                },
            )
        },
        floatingActionButton = {
            if (choice != null) {
                ExtendedFloatingActionButton(
                    onClick = { pick.launch(arrayOf("application/pdf")) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add PDF") },
                    modifier = Modifier.padding(bottom = if (reader.bookId != null) 72.dp else 0.dp),
                )
            }
        },
        bottomBar = {
            if (reader.bookId != null) MiniPlayer(reader, onOpen = { reader.bookId?.let(onOpen) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (choice == null) {
                item { WelcomeCard(packs, onPacks) }
            }
            importing?.let { state ->
                item { ImportCard(state, onDismiss = vm::dismissImportError) }
            }
            if (books.isEmpty() && choice != null && importing == null) {
                item {
                    Column(Modifier.padding(vertical = 48.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Headphones, null, tint = Palette.Amber, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Add a PDF to hear it", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap Add PDF, or share a PDF to Vaasi from any app. It's read aloud on your phone, no internet needed.",
                            color = Palette.SubText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(books, key = { it.id }) { entry ->
                BookCard(entry, reading = reader.bookId == entry.id, onOpen = { onOpen(entry.id) }, onDelete = { deleting = entry })
            }
        }
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove “${entry.title}”?") },
            text = { Text("Vaasi forgets its text and where you stopped. The PDF file itself isn't touched.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteBook(entry.id); deleting = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WelcomeCard(packs: Map<String, PackState>, onPacks: () -> Unit) {
    val busy = packs.values.firstOrNull { it is PackState.Downloading || it is PackState.Installing }
    Card(colors = CardDefaults.cardColors(containerColor = Palette.Elevated)) {
        Column(Modifier.padding(20.dp)) {
            Icon(Icons.Rounded.RecordVoiceOver, null, tint = Palette.Amber, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("Turn PDFs into natural speech", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Vaasi reads with Kokoro, a lifelike voice that runs right on your phone. " +
                    "Download a voice pack once and it works offline after that.",
                color = Palette.SubText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            when (busy) {
                is PackState.Downloading -> {
                    Text("Downloading voices…", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    val f = busy.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is PackState.Installing -> {
                    Text("Unpacking voices…", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { busy.fraction }, Modifier.fillMaxWidth())
                }
                else -> Button(onClick = onPacks) { Text("Choose a voice pack") }
            }
        }
    }
}

@Composable
private fun ImportCard(state: ImportState, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.Elevated)) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is ImportState.Reading -> {
                    Text("Reading the PDF…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (state.pages > 0) {
                        Text("Page ${state.page} of ${state.pages}", color = Palette.SubText, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { state.page.toFloat() / state.pages }, Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is ImportState.Failed -> {
                    Text("Couldn't add that PDF", style = MaterialTheme.typography.titleMedium, color = Palette.Error)
                    Spacer(Modifier.height(6.dp))
                    Text(state.message, color = Palette.SubText, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun BookCard(entry: BookEntry, reading: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = Palette.Elevated),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Elevated2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (reading) Icons.Rounded.Headphones else Icons.Rounded.PictureAsPdf,
                    contentDescription = null,
                    tint = if (reading) Palette.Teal else Palette.Amber,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val listened = (entry.progress * 100).toInt()
                Text(
                    buildString {
                        append(if (entry.pages == 1) "1 page" else "${entry.pages} pages")
                        if (listened > 0) append(" · $listened% listened")
                    },
                    color = Palette.SubText,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (listened > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Palette.Teal,
                        trackColor = Palette.Highlight,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: Reader.State, onOpen: () -> Unit) {
    val context = LocalContext.current
    Surface(color = Palette.Elevated2, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.navigationBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusLine(state),
                    color = Palette.SubText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (state.playing && state.waiting) {
                    CircularProgressIndicator(Modifier.size(44.dp).clearAndSetSemantics {}, strokeWidth = 2.dp)
                }
                IconButton(onClick = { Reader.toggle(context) }) {
                    Icon(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                    )
                }
            }
        }
    }
}

fun statusLine(state: Reader.State): String = when {
    state.error != null -> state.error
    state.playing && state.waiting -> "Getting the voice ready…"
    state.total > 0 -> "${percent(state.sentence, state.total)}% · ${if (state.playing) "reading" else "paused"}"
    else -> ""
}

fun percent(sentence: Int, total: Int): Int = if (total <= 1) 0 else sentence * 100 / (total - 1)

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.Faint,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
