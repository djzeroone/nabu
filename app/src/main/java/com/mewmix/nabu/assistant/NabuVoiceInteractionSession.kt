package com.mewmix.nabu.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.mewmix.nabu.ui.brutalist.BrutalButton
import com.mewmix.nabu.ui.brutalist.BrutalButtonText
import NabuTheme

class NabuVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

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
                                .height(200.dp),
                            color = Color.Black.copy(alpha = 0.8f)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "Nabu Assistant", 
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                BrutalButton(onClick = { hide() }) {
                                    BrutalButtonText("Close")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
