package com.mewmix.nabu.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mewmix.nabu.data.TtsModelValidator
import com.mewmix.nabu.utils.SettingsManager
import java.io.File

@Composable
fun RuntimeStatusLine(
    modifier: Modifier = Modifier,
    ttsEnabled: Boolean = true,
    llmRuntimeDescription: String = "NOT LOADED"
) {
    val context = LocalContext.current
    val ttsEngine = SettingsManager.getTtsEngine(context)
    val sopranoDir = File(context.filesDir, "models/soprano-80m-onnx")
    val sopranoPartialDir = File(context.filesDir, "models/soprano-80m-onnx_partial")
    val sopranoIncomplete = TtsModelValidator
        .missingFiles("soprano-80m-onnx", sopranoDir, sopranoPartialDir)
        .isNotEmpty()

    val voiceLabel = when {
        !ttsEnabled -> "Voice off"
        ttsEngine == "supertonic" -> "Voice Supertonic"
        ttsEngine == "soprano" && sopranoIncomplete -> "Voice Soprano (download incomplete)"
        ttsEngine == "soprano" -> "Voice Soprano"
        else -> "Voice Kokoro"
    }

    Row(modifier = modifier) {
        Text(
            text = "$voiceLabel · LLM $llmRuntimeDescription",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
