package com.mewmix.nabu.uiagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mewmix.nabu.utils.DebugLogger

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_EXECUTE) {
            val request = intent.getStringExtra(EXTRA_REQUEST)?.trim().orEmpty()
            if (request.isBlank()) {
                DebugLogger.log("ActionReceiver: received empty request")
                return
            }
            val modeStr = intent.getStringExtra(EXTRA_MODE)?.uppercase() ?: "SINGLE_TURN"
            val mode = runCatching { ActionSessionMode.valueOf(modeStr) }.getOrDefault(ActionSessionMode.SINGLE_TURN)
            DebugLogger.log("ActionReceiver: dispatching request='$request' mode=$mode")
            ActionRequestDispatcher.submitRequest(
                context = context,
                request = request,
                mode = mode,
                onComplete = { result ->
                    DebugLogger.log("ActionReceiver: completed request='$request' isError=${result.isError} output='${result.output}'")
                }
            )
        }
    }

    companion object {
        const val ACTION_EXECUTE = "com.mewmix.nabu.ACTION_EXECUTE"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_MODE = "mode"
    }
}
