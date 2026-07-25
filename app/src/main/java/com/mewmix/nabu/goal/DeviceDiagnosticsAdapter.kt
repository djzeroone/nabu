package com.mewmix.nabu.goal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class DeviceDiagnosticsAdapter(
    private val context: Context,
    private val backend: LlmBackend
) : SurfaceAdapter {

    override val capabilityId: CapabilityId = CapabilityId.DEVICE_DIAGNOSTICS

    override suspend fun executeStep(objective: String): AgentDecision {
        // 1. Gather raw context
        val batteryPct = getBatteryPercentage()
        val networkState = getNetworkState()
        
        val systemContext = """
            [System Diagnostics]
            Battery Level: $batteryPct%
            Network State: $networkState
        """.trimIndent()

        // 2. Format Prompt
        val conversation = listOf(
            LlmMessage(
                role = "system",
                content = "You are a diagnostic assistant. Answer the objective concisely using the provided system context. Reply ONLY with the final answer."
            ),
            LlmMessage(
                role = "user", 
                content = "$systemContext\n\nObjective: $objective"
            )
        )

        // 3. Inference
        val completion = CompletableDeferred<String?>()
        val completed = AtomicBoolean(false)
        val output = StringBuilder()
        
        backend.sendMessage(conversation) { partial, done ->
            if (partial.isNotEmpty()) {
                output.append(partial)
            }
            if (done && completed.compareAndSet(false, true)) {
                completion.complete(output.toString())
            }
        }

        val rawOutput = withTimeoutOrNull(10_000) { completion.await() }
            ?: return AgentDecision.Finish("Error: Inference timed out.")

        return AgentDecision.Finish(rawOutput.trim())
    }

    private fun getBatteryPercentage(): Int {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = context.registerReceiver(null, intentFilter)
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            -1
        }
    }

    private fun getNetworkState(): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        return when {
            capabilities == null -> "Disconnected"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Connected (Wi-Fi)"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Connected (Cellular)"
            else -> "Connected (Other)"
        }
    }
}
