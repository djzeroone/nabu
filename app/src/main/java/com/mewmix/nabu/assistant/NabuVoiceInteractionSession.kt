package com.mewmix.nabu.assistant

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import com.mewmix.nabu.uiagent.ActionRequestDispatcher
import com.mewmix.nabu.uiagent.ActionSessionMode
import kotlinx.coroutines.CompletableDeferred
import NabuTheme

class NabuVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestText by mutableStateOf("")
    private var statusText by mutableStateOf("What should I do on this screen?")
    private var isRunning by mutableStateOf(false)
    private var pendingConfirmation by mutableStateOf<String?>(null)
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    private fun submit() {
        val request = requestText.trim()
        if (request.isEmpty() || isRunning) return
        requestText = ""
        statusText = "Planning…"
        isRunning = true

        ActionRequestDispatcher.submitRequest(
            context = context,
            request = request,
            mode = ActionSessionMode.SINGLE_TURN,
            requestConfirmation = { description ->
                val deferred = CompletableDeferred<Boolean>()
                confirmationDeferred = deferred
                mainHandler.post {
                    pendingConfirmation = description
                    statusText = "Confirmation required"
                    show(Bundle.EMPTY, 0)
                }
                deferred.await()
            },
            onStep = { step ->
                mainHandler.post { statusText = step.result }
            },
            onComplete = { result ->
                mainHandler.post {
                    isRunning = false
                    statusText = result.output
                    if (result.isError) show(Bundle.EMPTY, 0)
                }
            }
        )

        // The VoiceInteractionSession remains alive as the process-level dispatcher takes over,
        // while the originating app becomes unobstructed again.
        hide()
    }

    private fun resolveConfirmation(allow: Boolean) {
        confirmationDeferred?.complete(allow)
        confirmationDeferred = null
        pendingConfirmation = null
        statusText = if (allow) "Continuing…" else "Action declined"
        if (allow) hide()
    }

    override fun onCreateContentView(): View {
        return ComposeView(context).apply {
            setContent {
                NabuTheme {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            color = Color.Black.copy(alpha = 0.8f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "Nabu Assistant", 
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(statusText, color = Color.White)
                                Spacer(modifier = Modifier.height(12.dp))
                                val confirmation = pendingConfirmation
                                if (confirmation != null) {
                                    Text(confirmation, color = Color.White)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        BrutalButton(onClick = { resolveConfirmation(false) }) {
                                            BrutalButtonText("Decline")
                                        }
                                        BrutalButton(onClick = { resolveConfirmation(true) }) {
                                            BrutalButtonText("Allow")
                                        }
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = requestText,
                                        onValueChange = { requestText = it },
                                        enabled = !isRunning,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Action request") },
                                        singleLine = false,
                                        maxLines = 3
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        BrutalButton(onClick = { hide() }) {
                                            BrutalButtonText("Close")
                                        }
                                        BrutalButton(onClick = ::submit) {
                                            BrutalButtonText(if (isRunning) "Running" else "Run")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        confirmationDeferred?.complete(false)
        confirmationDeferred = null
        super.onDestroy()
    }
}
