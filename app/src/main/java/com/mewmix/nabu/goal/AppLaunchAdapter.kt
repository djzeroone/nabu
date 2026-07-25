package com.mewmix.nabu.goal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class AppLaunchAdapter(
    private val context: Context,
    private val backend: LlmBackend
) : SurfaceAdapter {

    override val capabilityId: CapabilityId = CapabilityId.APP_LAUNCH

    override suspend fun executeStep(objective: String): AgentDecision {
        val conversation = listOf(
            LlmMessage(
                role = "system",
                content = "You resolve human intent into Android package names. Given an objective like 'Open YouTube', reply ONLY with the exact android package name like 'com.google.android.youtube'. No markdown, no quotes."
            ),
            LlmMessage(role = "user", content = objective)
        )

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
            ?: return AgentDecision.Finish("Error: Inference timed out trying to resolve app package.")

        val packageName = rawOutput.trim()
        if (packageName.isEmpty()) {
            return AgentDecision.Finish("Failed to resolve package name for objective.")
        }

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AgentDecision.Finish("Successfully launched $packageName")
            } else {
                AgentDecision.Finish("App not found: $packageName")
            }
        } catch (e: Exception) {
            AgentDecision.Finish("Error launching $packageName: ${e.message}")
        }
    }
}
