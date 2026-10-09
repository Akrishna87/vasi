package io.github.akrishna87.vaasi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.akrishna87.vaasi.tts.PackState
import io.github.akrishna87.vaasi.tts.VoicePack
import io.github.akrishna87.vaasi.tts.VoicePacks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksScreen(vm: AppViewModel, onBack: () -> Unit) {
    val states by vm.packStates.collectAsState()
    val choice by vm.choice.collectAsState()
    var deleting by remember { mutableStateOf<VoicePack?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice packs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "The voices run entirely on your phone, so nothing you listen to leaves it. " +
                        "Download a pack once; after that Vaasi works offline. Wi-Fi is a good idea for the download.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.SubText,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(VoicePacks.all, key = { it.id }) { pack ->
                PackCard(
                    pack = pack,
                    state = states[pack.id] ?: PackState.NotInstalled,
                    inUse = choice?.pack == pack,
                    onDownload = { vm.download(pack) },
                    onCancel = { vm.cancelDownload(pack) },
                    onUse = { vm.usePack(pack) },
                    onDelete = { deleting = pack },
                )
            }
        }
    }

    deleting?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete the ${pack.title} voices?") },
            text = { Text("This frees about ${pack.sizeMb} MB. You can download them again any time.") },
            confirmButton = { TextButton(onClick = { vm.deletePack(pack); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PackCard(
    pack: VoicePack,
    state: PackState,
    inUse: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.Elevated)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state == PackState.Installed) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Installed", tint = Palette.Teal)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(pack.summary, style = MaterialTheme.typography.bodyMedium, color = Palette.SubText)
            Spacer(Modifier.height(4.dp))
            Text(
                pack.voices.filter { it.best }.joinToString(prefix = "Favourites: ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Faint,
            )
            Spacer(Modifier.height(16.dp))
            when (state) {
                PackState.NotInstalled -> Button(onClick = onDownload) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download · ${pack.sizeMb} MB")
                }
                is PackState.Downloading -> {
                    val f = state.fraction
                    Text(
                        "Downloading… ${mb(state.done)} of ${mb(state.total)} MB",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
                is PackState.Installing -> {
                    Text("Unpacking the voices…", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.fraction }, Modifier.fillMaxWidth())
                }
                PackState.Installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (inUse) {
                        Text("In use", style = MaterialTheme.typography.labelLarge, color = Palette.Teal, modifier = Modifier.align(Alignment.CenterVertically))
                    } else {
                        Button(onClick = onUse) { Text("Use these voices") }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDelete) { Text("Delete") }
                }
                is PackState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Palette.Error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onDownload) { Text("Try again") }
                }
            }
        }
    }
}

private fun mb(bytes: Long) = bytes / 1_000_000
