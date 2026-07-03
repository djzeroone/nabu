package com.mewmix.nabu.assistant

import android.content.Intent
import android.speech.RecognitionService

class NabuRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {}
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
