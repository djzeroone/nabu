package com.mewmix.nabu.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalIconButton
import com.mewmix.nabu.ui.brutalist.BrutalSlider
import com.mewmix.nabu.ui.brutalist.PanelBox
import com.mewmix.nabu.ui.brutalist.PanelRow
import com.mewmix.nabu.utils.KokoroAudioPlayer
import com.mewmix.nabu.utils.PlayerState
import com.mewmix.nabu.utils.formatBytes
import com.mewmix.nabu.utils.saveAudioWithDisplayName
import com.mewmix.nabu.voicelab.VoiceLabEngineInfo
import com.mewmix.nabu.voicelab.VoiceLabParameter
import com.mewmix.nabu.voicelab.VoiceLabRepository
import com.mewmix.nabu.voicelab.VoiceLabRequest
import com.mewmix.nabu.voicelab.VoiceLabRuntimeDiagnostics
import com.mewmix.nabu.voicelab.VoiceLabRuntimeState
import com.mewmix.nabu.voicelab.VoiceLabSynthesisResult
import com.mewmix.nabu.voicelab.VoiceLabText
import com.mewmix.nabu.voicelab.VoiceLabTestTags
import com.mewmix.nabu.voicelab.VoiceLabVoice
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_VOICE_LAB_SCRIPT =
    "Nabu Voice Lab is testing creator narration for Alex Rivera in 2026. " +
        "The sample includes 42 chapters, \$19.95, commas, pauses, and a question: does this voice sound natural? " +
        "Great, let's render it!"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { VoiceLabRepository(context) }
    var engines by remember { mutableStateOf<List<VoiceLabEngineInfo>>(emptyList()) }
    var selectedEngineId by remember { mutableStateOf<String?>(null) }
    var voices by remember { mutableStateOf<List<VoiceLabVoice>>(emptyList()) }
    var selectedVoiceId by remember { mutableStateOf<String?>(null) }
    var script by remember { mutableStateOf(DEFAULT_VOICE_LAB_SCRIPT) }
    var engineExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRendering by remember { mutableStateOf(false) }
    var playerState by remember { mutableStateOf(PlayerState.IDLE) }
    val player = remember { KokoroAudioPlayer(scope) { playerState = it } }
    var lastResult by remember { mutableStateOf<VoiceLabSynthesisResult?>(null) }
    var runtimeDiagnostics by remember { mutableStateOf(VoiceLabRuntimeDiagnostics()) }
    val recentResults = remember { mutableStateListOf<VoiceLabSynthesisResult>() }
    val parameterValues = remember { mutableStateMapOf<String, String>() }

    DisposableEffect(Unit) {
        onDispose {
            player.stop()
            repository.close()
        }
    }

    LaunchedEffect(Unit) {
        runtimeDiagnostics = runtimeDiagnostics.copy(state = VoiceLabRuntimeState.Loading)
        val startedAt = SystemClock.elapsedRealtime()
        val loaded = withContext(Dispatchers.IO) { repository.engines() }
        val loadMs = SystemClock.elapsedRealtime() - startedAt
        engines = loaded
        val nextEngineId = loaded.firstOrNull { it.isAvailable }?.id ?: loaded.firstOrNull()?.id
        selectedEngineId = nextEngineId
        val nextEngine = loaded.firstOrNull { it.id == nextEngineId }
        runtimeDiagnostics = runtimeDiagnostics.copy(
            state = nextEngine.runtimeState(),
            engineCatalogLoadMs = loadMs,
            selectedEngineId = nextEngineId,
            lastFailure = null
        )
    }

    val selectedEngine = engines.firstOrNull { it.id == selectedEngineId }

    LaunchedEffect(selectedEngineId) {
        val engineId = selectedEngineId ?: return@LaunchedEffect
        runtimeDiagnostics = runtimeDiagnostics.copy(
            state = VoiceLabRuntimeState.Loading,
            selectedEngineId = engineId,
            lastFailure = null
        )
        val startedAt = SystemClock.elapsedRealtime()
        val loadedVoices = withContext(Dispatchers.IO) { repository.voices(engineId) }
        val loadMs = SystemClock.elapsedRealtime() - startedAt
        voices = loadedVoices
        val nextVoiceId = loadedVoices.firstOrNull()?.id
        selectedVoiceId = nextVoiceId
        runtimeDiagnostics = runtimeDiagnostics.copy(
            state = selectedEngine.runtimeState(),
            voiceListLoadMs = loadMs,
            selectedEngineId = engineId,
            selectedVoiceId = nextVoiceId
        )
    }

    LaunchedEffect(selectedEngineId, selectedEngine?.parameters) {
        parameterValues.clear()
        selectedEngine?.parameters.orEmpty().forEach { parameter ->
            parameterValues[parameter.id] = parameter.defaultAsString()
        }
    }

    fun render(text: String) {
        val engine = selectedEngine ?: return
        val renderText = VoiceLabText.renderableTextOrNull(text)
        if (renderText == null) {
            error = "Enter script text before rendering."
            runtimeDiagnostics = runtimeDiagnostics.copy(
                state = VoiceLabRuntimeState.Failed,
                lastFailure = error
            )
            return
        }
        if (!engine.isAvailable) {
            error = engine.status
            runtimeDiagnostics = runtimeDiagnostics.copy(
                state = engine.runtimeState(),
                lastFailure = error
            )
            return
        }
        isRendering = true
        error = null
        val renderStartedAt = SystemClock.elapsedRealtime()
        runtimeDiagnostics = runtimeDiagnostics.copy(
            state = VoiceLabRuntimeState.Rendering,
            selectedEngineId = engine.id,
            selectedVoiceId = selectedVoiceId,
            lastRenderRequestedAtMs = renderStartedAt,
            lastRenderCompletedAtMs = null,
            lastFailure = null
        )
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.synthesize(
                        VoiceLabRequest(
                            engineId = engine.id,
                            voiceId = selectedVoiceId,
                            text = renderText,
                            parameters = parameterValues.toMap()
                        )
                    )
                }
                lastResult = result
                recentResults.add(0, result)
                while (recentResults.size > 5) recentResults.removeAt(recentResults.lastIndex)
                player.prepare(result.audio, result.sampleRate)
                player.play()
                runtimeDiagnostics = runtimeDiagnostics.copy(
                    state = VoiceLabRuntimeState.Ready,
                    lastRenderCompletedAtMs = SystemClock.elapsedRealtime()
                )
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                runtimeDiagnostics = runtimeDiagnostics.copy(
                    state = VoiceLabRuntimeState.Failed,
                    lastRenderCompletedAtMs = SystemClock.elapsedRealtime(),
                    lastFailure = error
                )
            } finally {
                isRendering = false
            }
        }
    }

    PanelBox(
        title = "VOICE LAB",
        modifier = Modifier
            .fillMaxSize()
            .testTag(VoiceLabTestTags.Screen)
            .padding(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                TextField(
                    value = script,
                    onValueChange = { script = it },
                    label = { Text("Script") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(VoiceLabTestTags.ScriptInput)
                )
            }

            item {
                ExposedDropdownMenuBox(
                    expanded = engineExpanded,
                    onExpandedChange = { engineExpanded = !engineExpanded }
                ) {
                    TextField(
                        value = selectedEngine?.let { "${it.name} - ${it.status}" }.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Engine") },
                        trailingIcon = {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Select engine")
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                            .testTag(VoiceLabTestTags.EngineSelector)
                    )
                    DropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false }
                    ) {
                        engines.forEach { engine ->
                            DropdownMenuItem(
                                text = {
                                    Text("${engine.name} (${engine.status})")
                                },
                                onClick = {
                                    selectedEngineId = engine.id
                                    engineExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            item {
                VoiceSelector(
                    voices = voices,
                    selectedVoiceId = selectedVoiceId,
                    expanded = voiceExpanded,
                    onExpandedChange = { voiceExpanded = it },
                    onSelected = {
                        selectedVoiceId = it
                        runtimeDiagnostics = runtimeDiagnostics.copy(selectedVoiceId = it)
                        voiceExpanded = false
                    }
                )
            }

            item {
                ParameterControls(
                    parameters = selectedEngine?.parameters.orEmpty(),
                    values = parameterValues,
                    onReset = {
                        selectedEngine?.parameters.orEmpty().forEach { parameter ->
                            parameterValues[parameter.id] = parameter.defaultAsString()
                        }
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrutalButton(
                        onClick = { render(VoiceLabText.previewText(script)) },
                        modifier = Modifier.testTag(VoiceLabTestTags.PreviewButton),
                        enabled = !isRendering && selectedEngine?.isAvailable == true
                    ) {
                        Text(if (isRendering) "Rendering" else "Preview Voice")
                    }
                    BrutalButton(
                        onClick = { render(script) },
                        modifier = Modifier.testTag(VoiceLabTestTags.RenderFullButton),
                        enabled = !isRendering && selectedEngine?.isAvailable == true
                    ) {
                        Text("Render Full Script")
                    }
                }
            }

            item {
                RuntimeDiagnostics(runtimeDiagnostics)
            }

            item {
                PlaybackControls(
                    result = lastResult,
                    playerState = playerState,
                    onPlay = {
                        lastResult?.let {
                            if (playerState == PlayerState.PAUSED) {
                                player.play()
                            } else {
                                player.prepare(it.audio, it.sampleRate)
                                player.play()
                            }
                        }
                    },
                    onPause = { player.pause() },
                    onRestart = {
                        lastResult?.let {
                            player.prepare(it.audio, it.sampleRate)
                            player.play()
                        }
                    },
                    onExport = {
                        lastResult?.let {
                            saveAudioWithDisplayName(
                                audioData = it.audio,
                                context = context,
                                displayName = "VOICE_LAB_${it.engineId}_${it.voiceId ?: "default"}",
                                sampleRate = it.sampleRate
                            )
                        }
                    }
                )
            }

            error?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(VoiceLabTestTags.Error)
                    )
                }
            }

            lastResult?.let { result ->
                item {
                    Diagnostics(result)
                }
            }

            if (recentResults.isNotEmpty()) {
                item {
                    Text(
                        "Recent Renders",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag(VoiceLabTestTags.RecentRenders)
                    )
                }
                items(recentResults) { result ->
                    RecentRenderRow(
                        result = result,
                        onReplay = {
                            lastResult = result
                            player.prepare(result.audio, result.sampleRate)
                            player.play()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeDiagnostics(diagnostics: VoiceLabRuntimeDiagnostics) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(VoiceLabTestTags.RuntimeDiagnostics)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Runtime", style = MaterialTheme.typography.titleMedium)
        Text("State: ${diagnostics.state.name}")
        Text("Engine catalog load: ${diagnostics.engineCatalogLoadMs?.let { "$it ms" } ?: "--"}")
        Text("Voice list load: ${diagnostics.voiceListLoadMs?.let { "$it ms" } ?: "--"}")
        Text("Selected engine: ${diagnostics.selectedEngineId ?: "--"}")
        Text("Selected voice: ${diagnostics.selectedVoiceId ?: "--"}")
        diagnostics.lastRenderRequestedAtMs?.let { requestedAt ->
            val completedAt = diagnostics.lastRenderCompletedAtMs
            val elapsedMs = (completedAt ?: SystemClock.elapsedRealtime()) - requestedAt
            Text("Current/last render wall time: $elapsedMs ms")
        }
        diagnostics.lastFailure?.let {
            Text("Last failure: $it", color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSelector(
    voices: List<VoiceLabVoice>,
    selectedVoiceId: String?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { onExpandedChange(!expanded) }
        ) {
            TextField(
                value = selectedVoiceId ?: "No voices exposed",
                onValueChange = {},
                readOnly = true,
                label = { Text("Voice") },
                trailingIcon = {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Select voice")
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .testTag(VoiceLabTestTags.VoiceSelector)
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = { Text("${voice.displayName} / ${voice.id}") },
                        onClick = { onSelected(voice.id) }
                    )
                }
            }
        }
        voices.firstOrNull { it.id == selectedVoiceId }?.let { voice ->
            Text(
                text = "Engine: ${voice.engineId}   Model: ${voice.modelId}",
                style = MaterialTheme.typography.bodySmall
            )
            voice.metadata.forEach { (key, value) ->
                Text("$key: $value", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParameterControls(
    parameters: List<VoiceLabParameter>,
    values: MutableMap<String, String>,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(VoiceLabTestTags.ParameterControls),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Parameters", style = MaterialTheme.typography.titleMedium)
            BrutalButton(onClick = onReset) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reset parameters")
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset")
            }
        }
        parameters.forEach { parameter ->
            when (parameter) {
                is VoiceLabParameter.FloatValue -> {
                    var sliderValue by remember(parameter.id, values[parameter.id]) {
                        mutableFloatStateOf(values[parameter.id]?.toFloatOrNull() ?: parameter.defaultValue)
                    }
                    PanelRow(name = parameter.label) {
                        BrutalSlider(
                            value = sliderValue,
                            onValueChange = {
                                sliderValue = it
                                values[parameter.id] = String.format(Locale.US, "%.3f", it)
                            },
                            range = parameter.min..parameter.max,
                            modifier = Modifier.weight(1f)
                        )
                        Text(String.format(Locale.US, "%.2f", sliderValue))
                    }
                }
                is VoiceLabParameter.IntValue -> {
                    PanelRow(name = parameter.label) {
                        TextField(
                            value = values[parameter.id] ?: parameter.defaultValue.toString(),
                            onValueChange = { raw ->
                                raw.filter(Char::isDigit).toIntOrNull()?.let {
                                    values[parameter.id] = it.coerceIn(parameter.min, parameter.max).toString()
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp)
                        )
                        Text("${parameter.min}-${parameter.max}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is VoiceLabParameter.ChoiceValue -> {
                    var expanded by remember(parameter.id) { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        TextField(
                            value = values[parameter.id] ?: parameter.defaultValue,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(parameter.label) },
                            trailingIcon = {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Select ${parameter.label}")
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            parameter.choices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice) },
                                    onClick = {
                                        values[parameter.id] = choice
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    result: VoiceLabSynthesisResult?,
    playerState: PlayerState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onRestart: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(VoiceLabTestTags.PlaybackControls),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Duration: ${
                result?.audioDurationSeconds?.let { String.format(Locale.US, "%.2fs", it) } ?: "--"
            }",
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrutalIconButton(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                onClick = onPlay,
                enabled = result != null && playerState != PlayerState.PLAYING
            )
            BrutalIconButton(
                imageVector = Icons.Filled.Pause,
                contentDescription = "Pause",
                onClick = onPause,
                enabled = result != null && playerState == PlayerState.PLAYING
            )
            BrutalIconButton(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = "Restart",
                onClick = onRestart,
                enabled = result != null
            )
            BrutalIconButton(
                imageVector = Icons.Filled.Save,
                contentDescription = "Export WAV",
                onClick = onExport,
                enabled = result != null
            )
        }
    }
}

@Composable
private fun Diagnostics(result: VoiceLabSynthesisResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(VoiceLabTestTags.Diagnostics)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        Text("Engine: ${result.engineName} (${result.engineId})")
        Text("Voice: ${result.voiceId ?: "default"}")
        Text("Characters: ${result.inputCharacterCount}")
        Text("Generation: ${result.generationTimeMs} ms")
        Text("Audio duration: ${String.format(Locale.US, "%.2f", result.audioDurationSeconds)} s")
        Text("RTF: ${String.format(Locale.US, "%.3f", result.realTimeFactor)}")
        Text("WAV size: ${formatBytes(result.wavSizeBytes)}")
        Text("Model: ${result.modelId}")
        Text("Model size: ${result.modelSizeBytes?.let(::formatBytes) ?: "unknown"}")
        Text("Backend: ${result.backend}")
    }
}

@Composable
private fun RecentRenderRow(
    result: VoiceLabSynthesisResult,
    onReplay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${result.engineName} / ${result.voiceId ?: "default"}")
            Text(
                "${String.format(Locale.US, "%.2f", result.audioDurationSeconds)}s  RTF ${String.format(Locale.US, "%.3f", result.realTimeFactor)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        BrutalButton(onClick = onReplay) {
            Text("Replay")
        }
    }
}

private fun VoiceLabParameter.defaultAsString(): String =
    when (this) {
        is VoiceLabParameter.FloatValue -> defaultValue.toString()
        is VoiceLabParameter.IntValue -> defaultValue.toString()
        is VoiceLabParameter.ChoiceValue -> defaultValue
    }

private fun VoiceLabEngineInfo?.runtimeState(): VoiceLabRuntimeState =
    when {
        this == null -> VoiceLabRuntimeState.Unavailable
        isAvailable -> VoiceLabRuntimeState.Ready
        status.contains("download", ignoreCase = true) ||
            status.contains("missing", ignoreCase = true) -> VoiceLabRuntimeState.NeedsModel
        else -> VoiceLabRuntimeState.Unavailable
    }
