package io.github.akrishna87.vaasi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ModalBottomSheet
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
import io.github.akrishna87.vaasi.convert.AudioStatus
import io.github.akrishna87.vaasi.convert.Convert
import io.github.akrishna87.vaasi.convert.ConvertService
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
    val audioMap by Convert.status.collectAsState()
    val audio = audioMap[bookId] ?: AudioStatus()
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
    var showContents by remember { mutableStateOf(false) }
    var slowTipDismissed by remember { mutableStateOf(false) }
    val bookError by vm.bookError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (b != null) TextButton(onClick = { showContents = true }) { Text("Contents") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save to Music") },
                                leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                                enabled = (choice != null || audio.conversion?.complete == true) && !audio.saving,
                                onClick = { menu = false; confirmExport = true },
                            )
                            if (audio.conversion != null) {
                                DropdownMenuItem(
                                    text = { Text("Delete the audio") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { menu = false; Convert.delete(context, bookId) },
                                )
                            }
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
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                val error = bookError
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodyLarge, color = Palette.SubText)
                } else {
                    CircularProgressIndicator()
                }
            }
        } else {
            Column(Modifier.padding(padding)) {
                AudioCard(
                    audio = audio,
                    currentSignature = choice?.let { Convert.signature(it, settings.expressive) },
                    voiceName = choice?.narrator?.name,
                    onConvert = { Convert.start(context, bookId) },
                    onPause = { Convert.pause(context, bookId) },
                    onReconvert = { Convert.reconvert(context, bookId) },
                    onDismissSaved = { Convert.dismissSaved(bookId) },
                )
                if (isCurrent && reader.slow && !slowTipDismissed) {
                    SlowTip(
                        compact = choice?.pack?.id == "compact",
                        onDismiss = { slowTipDismissed = true },
                    )
                }
                BookText(b, current, onTap = { Reader.seek(context, bookId, it) })
            }
        }
    }

    if (showContents && b != null) {
        ContentsSheet(
            book = b,
            current = current,
            onDismiss = { showContents = false },
            onChoose = { sentence ->
                showContents = false
                Reader.seek(context, bookId, sentence)
            },
        )
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Save to Music?") },
            text = {
                Text(
                    "Saves the audio as one M4A file in Music/Vaasi, to play in any app. " +
                        if (audio.conversion?.complete == true) {
                            "It's ready in a few seconds."
                        } else {
                            "Vaasi finishes converting the PDF first, then saves it."
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmExport = false; Convert.saveToMusic(context, bookId) }) { Text("Save") }
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

/** Where converting this PDF to audio stands, and what to do about it. */
@Composable
private fun AudioCard(
    audio: AudioStatus,
    currentSignature: String?,
    voiceName: String?,
    onConvert: () -> Unit,
    onPause: () -> Unit,
    onReconvert: () -> Unit,
    onDismissSaved: () -> Unit,
) {
    val conversion = audio.conversion
    val percent = ((conversion?.fraction ?: 0f) * 100).toInt()
    val complete = conversion?.complete == true
    val stale = complete && currentSignature != null && conversion?.signature != currentSignature
    // Once it's converted (in the voice chosen), keep out of the way.
    if (complete && !stale && audio.savedTo == null && !audio.saving && audio.error == null) return
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.Elevated2),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            val (title, detail) = when {
                audio.saving -> "Saving to Music…" to null
                audio.savedTo != null -> "Saved to ${audio.savedTo}" to null
                audio.error != null -> audio.error to "What's converted so far is kept."
                audio.active -> "Converting to audio · $percent%" to (
                    listOfNotNull(audio.etaSeconds?.let { ConvertService.timeLeft(it) }).joinToString() +
                        (if (audio.etaSeconds != null) ". " else "") +
                        "You can listen now: Vaasi converts just ahead of you, and keeps going with the screen off."
                    )
                stale -> "Converted with ${conversion?.voiceName}" to "Convert it again with ${voiceName ?: "the voice you chose"}?"
                conversion != null && conversion.converted > 0 -> "Converted $percent% so far" to "Continue converting to finish it."
                else -> "Convert to audio" to
                    "Turns the whole PDF into audio first, so it plays without pauses. It takes a while, " +
                    "and carries on with the screen off."
            }
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.SubText)
            }
            if (audio.active || audio.saving) {
                Spacer(Modifier.height(8.dp))
                if (audio.saving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                } else {
                    LinearProgressIndicator(progress = { conversion?.fraction ?: 0f }, Modifier.fillMaxWidth().padding(end = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when {
                    audio.saving -> Unit
                    audio.savedTo != null -> TextButton(onClick = onDismissSaved) { Text("OK") }
                    audio.active -> TextButton(onClick = onPause) { Text("Pause converting") }
                    stale -> TextButton(onClick = onReconvert) { Text("Convert again") }
                    conversion != null && conversion.converted > 0 && !complete -> TextButton(onClick = onConvert) { Text("Continue converting") }
                    audio.error != null -> TextButton(onClick = onConvert) { Text("Continue converting") }
                    !complete -> TextButton(onClick = onConvert) { Text("Convert to audio") }
                }
            }
        }
    }
}

@Composable
private fun BookText(book: Book, current: Int, onTap: (Int) -> Unit) {
    // Open at the sentence being read (or where reading will start), not at the title page.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = book.paragraphOf(current))
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
                // Before listening starts, say where it will begin when that isn't the first page.
                !isCurrent && !dragging && book.start > 0 && current == book.start ->
                    "Starts at “${placeName(book, current)}”, past the title page and contents. " +
                        "Tap Contents to start elsewhere."
                else -> listOfNotNull(
                    book.chapterAt(shown)?.title?.let { if (it.length > 32) it.take(31) + "…" else it },
                    "Page ${book.pageOf(shown)} of ${book.pages}",
                    "${percent(shown, total)}%",
                ).joinToString(" · ")
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

private fun placeName(book: Book, sentence: Int): String {
    val name = book.chapters.firstOrNull { it.sentence == sentence }?.title ?: book.sentence(sentence)
    return if (name.length > 40) name.take(39) + "…" else name
}

/** The chapters to jump to, plus the very beginning (title page, contents) of the PDF. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(book: Book, current: Int, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    val here = book.chapterAt(current)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Elevated) {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                ContentsRow(
                    title = "Beginning of the PDF",
                    detail = if (book.start > 0) "Title page, copyright and contents" else "Page 1",
                    selected = false,
                    onClick = { onChoose(0) },
                )
            }
            items(book.chapters.size) { i ->
                val chapter = book.chapters[i]
                ContentsRow(
                    title = chapter.title,
                    detail = "Page ${book.pageOf(chapter.sentence)}" + if (chapter.sentence == book.start && book.start > 0) " · where reading starts" else "",
                    selected = chapter == here,
                    onClick = { onChoose(chapter.sentence) },
                )
            }
            if (book.chapters.isEmpty()) {
                item {
                    Text(
                        "Vaasi couldn't find chapters in this PDF. Tap a sentence in the text to start reading there.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.SubText,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentsRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) Palette.Amber.copy(alpha = 0.14f) else Palette.Elevated)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (selected) Palette.Amber else Palette.Text)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.SubText)
    }
}

/** Shown when the voice keeps falling behind playback on this phone. */
@Composable
private fun SlowTip(compact: Boolean, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Palette.Elevated2),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "This phone takes a little longer to make the voice than to play it, so there are short pauses. " +
                    if (compact) {
                        "The Studio voices speak about three times faster, and sound better too (Voices → Voice packs)."
                    } else {
                        "Use Save as audio file (in the ⋮ menu) to prepare the whole PDF ahead and listen without pauses."
                    },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss") }
        }
    }
}
