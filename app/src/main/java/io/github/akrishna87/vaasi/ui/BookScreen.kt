package io.github.akrishna87.vaasi.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.vaasi.export.Export
import io.github.akrishna87.vaasi.play.Reader
import io.github.akrishna87.vaasi.text.Book
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(vm: AppViewModel, bookId: String, onBack: () -> Unit, onVoices: () -> Unit) {
    LaunchedEffect(bookId) { vm.open(bookId) }
    val book by vm.book.collectAsState()
    val reader by Reader.state.collectAsState()
    val books by vm.books.collectAsState()
    val export by Export.state.collectAsState()
    val choice by vm.choice.collectAsState()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val entry = books.firstOrNull { it.id == bookId }

    val b = book?.takeIf { it.id == bookId }
    val isCurrent = reader.bookId == bookId
    // The highlighted sentence: what's being read, or where this book was left off.
    val current = if (isCurrent) reader.sentence else entry?.position ?: 0

    var menu by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save as audio file") },
                                leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                                enabled = choice != null && export !is Export.State.Working,
                                onClick = { menu = false; confirmExport = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove from Vaasi") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (b != null) {
                PlayerPanel(
                    book = b,
                    reader = reader,
                    isCurrent = isCurrent,
                    current = current,
                    speed = settings.speed,
                    voiceName = choice?.narrator?.name,
                    onPlay = {
                        if (isCurrent) Reader.toggle(context) else Reader.play(context, bookId, current)
                    },
                    onSkip = { by ->
                        if (isCurrent) Reader.skip(context, by) else Reader.play(context, bookId, (current + by).coerceIn(0, b.sentenceCount - 1))
                    },
                    onSeek = { Reader.seek(context, bookId, it) },
                    onSpeed = vm::setSpeed,
                    onVoices = onVoices,
                )
            }
        },
    ) { padding ->
        if (b == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.padding(padding)) {
                ExportBanner(export, bookId, context)
                BookText(b, current, onTap = { Reader.seek(context, bookId, it) })
            }
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Save as an audio file?") },
            text = {
                Text(
                    "${choice?.narrator?.name ?: "The voice"} reads the whole PDF into an M4A file in Music/Vaasi, " +
                        "so you can play it in any app. A long PDF can take a while; you can keep using your phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmExport = false; Export.start(context, bookId) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this PDF?") },
            text = { Text("Vaasi forgets its text and where you stopped. The PDF file itself isn't touched.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteBook(bookId); onBack() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExportBanner(export: Export.State, bookId: String, context: android.content.Context) {
    val text: String
    var progress: Float? = null
    var cancellable = false
    when (export) {
        is Export.State.Working -> {
            if (export.bookId != bookId) return
            text = "Saving as audio… ${percent(export.done, export.total + 1)}%"
            progress = if (export.total > 0) export.done.toFloat() / export.total else 0f
            cancellable = true
        }
        is Export.State.Done -> {
            if (export.bookId != bookId) return
            text = "Saved to ${export.location}"
        }
        is Export.State.Failed -> {
            if (export.bookId != bookId) return
            text = "Couldn't save the audio: ${export.message}"
        }
        Export.State.Idle -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.Elevated2),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                progress?.let {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth())
                }
            }
            IconButton(onClick = { if (cancellable) Export.cancel(context) else Export.dismiss() }) {
                Icon(Icons.Rounded.Close, contentDescription = if (cancellable) "Cancel" else "Dismiss")
            }
        }
    }
}

@Composable
private fun BookText(book: Book, current: Int, onTap: (Int) -> Unit) {
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    var follow by remember { mutableStateOf(true) }
    val paragraph = book.paragraphOf(current)

    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(paragraph, follow) {
        if (!follow) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == paragraph } &&
            visible.firstOrNull { it.index == paragraph }?.let { it.offset + it.size < list.layoutInfo.viewportEndOffset } == true
        if (!shown) list.animateScrollToItem(paragraph, scrollOffset = -48)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(book.paragraphs.size, key = { it }) { p ->
                val first = book.firstSentenceOf(p)
                val count = book.paragraphs[p].sentences.size
                val local = if (current in first until first + count) current - first else -1
                Column {
                    if (p == 0 || book.paragraphs[p - 1].page != book.paragraphs[p].page) {
                        Text(
                            "PAGE ${book.paragraphs[p].page}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.Faint,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    ParagraphText(book.paragraphs[p].sentences, local) { s -> onTap(first + s) }
                }
            }
        }
        if (!follow) {
            SmallFloatingActionButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = Palette.Elevated2,
            ) {
                Icon(Icons.Rounded.MyLocation, contentDescription = "Follow the reading")
            }
        }
    }
}

@Composable
private fun ParagraphText(sentences: List<String>, highlighted: Int, onTap: (Int) -> Unit) {
    val starts = remember(sentences) {
        IntArray(sentences.size).also { s -> for (i in 1 until sentences.size) s[i] = s[i - 1] + sentences[i - 1].length + 1 }
    }
    val text: AnnotatedString = remember(sentences, highlighted) {
        buildAnnotatedString {
            sentences.forEachIndexed { i, sentence ->
                if (i > 0) append(' ')
                if (i == highlighted) {
                    withStyle(SpanStyle(background = Palette.Amber.copy(alpha = 0.22f), color = Palette.Text)) { append(sentence) }
                } else {
                    append(sentence)
                }
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 29.sp),
        color = if (highlighted >= 0) Palette.Text else Palette.Text.copy(alpha = 0.82f),
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(sentences) {
            detectTapGestures { pos ->
                val offset = layout?.getOffsetForPosition(pos) ?: return@detectTapGestures
                val i = starts.indexOfLast { it <= offset }.coerceAtLeast(0)
                onTap(i)
            }
        },
    )
}

@Composable
private fun PlayerPanel(
    book: Book,
    reader: Reader.State,
    isCurrent: Boolean,
    current: Int,
    speed: Float,
    voiceName: String?,
    onPlay: () -> Unit,
    onSkip: (Int) -> Unit,
    onSeek: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onVoices: () -> Unit,
) {
    val playing = isCurrent && reader.playing
    val total = book.sentenceCount
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var speedMenu by remember { mutableStateOf(false) }
    val shown = if (dragging) dragValue.toInt() else current

    Surface(color = Palette.Elevated, tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val status = when {
                isCurrent && reader.error != null -> reader.error
                playing && reader.waiting -> "Getting the voice ready…"
                else -> "Page ${book.pageOf(shown)} of ${book.pages} · ${percent(shown, total)}%"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent && reader.error != null) Palette.Error else Palette.SubText,
                maxLines = 2,
            )
            if (total > 1) {
                Slider(
                    value = shown.toFloat(),
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = { dragging = false; onSeek(dragValue.toInt()) },
                    valueRange = 0f..(total - 1).toFloat(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box {
                    AssistChip(onClick = { speedMenu = true }, label = { Text(speedLabel(speed)) })
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        for (s in SPEEDS) {
                            DropdownMenuItem(text = { Text(speedLabel(s)) }, onClick = { speedMenu = false; onSpeed(s) })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onSkip(-1) }) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous sentence") }
                Box(contentAlignment = Alignment.Center) {
                    // The ring goes underneath, out of the way of the button for touch and TalkBack.
                    if (playing && reader.waiting) {
                        CircularProgressIndicator(
                            Modifier.size(66.dp).clearAndSetSemantics {},
                            strokeWidth = 2.dp,
                            color = Palette.Teal,
                        )
                    }
                    FilledIconButton(
                        onClick = onPlay,
                        modifier = Modifier.size(60.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Amber),
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                IconButton(onClick = { onSkip(1) }) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next sentence") }
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = onVoices,
                    label = { Text(voiceName ?: "Voice", maxLines = 1) },
                    leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

val SPEEDS = listOf(0.75f, 0.9f, 1.0f, 1.1f, 1.25f, 1.4f, 1.6f)

fun speedLabel(speed: Float): String {
    val s = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "$s×"
}
