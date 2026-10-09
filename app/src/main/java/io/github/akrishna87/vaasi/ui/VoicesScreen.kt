package io.github.akrishna87.vaasi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.akrishna87.vaasi.tts.Accent
import io.github.akrishna87.vaasi.tts.Gender
import io.github.akrishna87.vaasi.tts.PackState
import io.github.akrishna87.vaasi.tts.Voice
import io.github.akrishna87.vaasi.tts.VoicePacks
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(vm: AppViewModel, onBack: () -> Unit, onPacks: () -> Unit) {
    val choice by vm.choice.collectAsState()
    val settings by vm.settings.collectAsState()
    val packs by vm.packStates.collectAsState()
    val previewing by vm.previewing.collectAsState()
    val loading by vm.modelLoading.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var speed by remember(settings.speed) { mutableFloatStateOf(settings.speed) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voices") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = onPacks) { Text("Voice packs") } },
            )
        },
    ) { padding ->
        val c = choice
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                TextButton(onClick = onPacks) { Text("Download a voice pack first") }
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            val installed = VoicePacks.all.filter { packs[it.id] == PackState.Installed }
            if (installed.size > 1) {
                item {
                    SectionLabel("Voice pack")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pack in installed) {
                            FilterChip(
                                selected = pack == c.pack,
                                onClick = { vm.usePack(pack) },
                                label = { Text("${pack.title} · ${pack.voices.size} voices") },
                            )
                        }
                    }
                }
            }
            item {
                SectionLabel("Reading speed")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = speed,
                        onValueChange = { speed = (it * 20).roundToInt() / 20f },
                        onValueChangeFinished = { vm.setSpeed(speed) },
                        valueRange = 0.6f..1.8f,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(speedLabel(speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(56.dp))
                }
            }
            item {
                Spacer(Modifier.size(12.dp))
                TabRow(selectedTabIndex = tab, containerColor = Palette.Background) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Narrator") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Dialogue") })
                }
            }
            if (tab == 1) {
                item {
                    Text(
                        "Read words inside “quotation marks” in a second voice, so stories sound like an audiobook " +
                            "with a narrator and a speaker.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.SubText,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.chooseDialogueVoice(null) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c.dialogue == null, onClick = { vm.chooseDialogueVoice(null) })
                        Text("Off: one voice reads everything", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            val selected = if (tab == 0) c.narrator.id else c.dialogue?.id
            for ((label, group) in groups(c.pack.voices)) {
                item(key = "label-$tab-$label") { SectionLabel(label) }
                items(group, key = { "$tab-${it.id}" }) { voice ->
                    VoiceRow(
                        voice = voice,
                        selected = voice.id == selected,
                        previewing = previewing == voice.id,
                        loading = loading && previewing == voice.id,
                        onSelect = { if (tab == 0) vm.chooseVoice(voice) else vm.chooseDialogueVoice(voice) },
                        onPreview = { vm.preview(voice) },
                    )
                }
            }
        }
    }
}

private fun groups(voices: List<Voice>): List<Pair<String, List<Voice>>> = listOf(
    "American women" to voices.filter { it.accent == Accent.American && it.gender == Gender.Female },
    "American men" to voices.filter { it.accent == Accent.American && it.gender == Gender.Male },
    "British women" to voices.filter { it.accent == Accent.British && it.gender == Gender.Female },
    "British men" to voices.filter { it.accent == Accent.British && it.gender == Gender.Male },
).filter { it.second.isNotEmpty() }

@Composable
private fun VoiceRow(
    voice: Voice,
    selected: Boolean,
    previewing: Boolean,
    loading: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(voice.name, style = MaterialTheme.typography.titleMedium)
                if (voice.best) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Star, contentDescription = "Among the best", tint = Palette.Amber, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                if (loading) "Loading the voices…" else voice.description,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.SubText,
            )
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (previewing) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onPreview) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Hear ${voice.name}") }
            }
        }
    }
}
