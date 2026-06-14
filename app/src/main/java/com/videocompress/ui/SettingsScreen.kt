package com.videocompress.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.data.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val targetBitrate by viewModel.targetBitrate.collectAsStateWithLifecycle()
    val targetResolution by viewModel.targetResolution.collectAsStateWithLifecycle()
    val minFileSize by viewModel.minFileSize.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Compression Parameters", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    var bitrateText by remember(targetBitrate) { mutableStateOf(targetBitrate.toString()) }
                    OutlinedTextField(
                        value = bitrateText,
                        onValueChange = { input ->
                            bitrateText = input
                            input.toIntOrNull()?.let { if (it > 0) viewModel.updateTargetBitrate(it) }
                        },
                        label = { Text("Target Bitrate (Mbps)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Target Resolution", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))

                    val resolutionOptions = listOf(
                        SettingsRepository.RESOLUTION_720P to "720p",
                        SettingsRepository.RESOLUTION_1080P to "1080p",
                        SettingsRepository.RESOLUTION_ORIGINAL to "Original"
                    )

                    resolutionOptions.forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = targetResolution == value,
                                onClick = { viewModel.updateTargetResolution(value) }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    var sizeText by remember(minFileSize) { mutableStateOf(minFileSize.toString()) }
                    OutlinedTextField(
                        value = sizeText,
                        onValueChange = { input ->
                            sizeText = input
                            input.toLongOrNull()?.let { if (it > 0) viewModel.updateMinFileSize(it) }
                        },
                        label = { Text("Minimum File Size (MB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Only videos with bitrate or resolution exceeding target values will be compressed. Output format: H.265 (HEVC).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
