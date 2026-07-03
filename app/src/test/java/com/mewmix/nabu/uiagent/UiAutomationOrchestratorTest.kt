package com.mewmix.nabu.uiagent

import android.content.Context
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

class UiAutomationOrchestratorTest {
    @Test
    fun `test execution terminates gracefully when accessibility service is missing`() = runBlocking {
        val context = mock(Context::class.java)
        
        val backend = object : LlmBackend {
            override fun initialize() {}
            override fun close() {}
            override fun cancel() {}
            override fun sendMessage(conversation: List<LlmMessage>, resultListener: (String, Boolean) -> Unit) {}
            override fun sendMessage(prompt: String, resultListener: (String, Boolean) -> Unit) {
                val json = """```json
{"goal":"test","screen_id":"screen1","steps":[{"action":"tap","target":{"element_id":"id"}}]}
```"""
                resultListener(json, true)
            }
        }

        val orchestrator = UiAutomationOrchestrator(
            context = context,
            backend = backend,
            requestConfirmation = { true }
        )

        val result = orchestrator.run("test goal")
        assertTrue("Unexpected result: ${result.output}", result.output.contains("limit reached", ignoreCase = true) || result.output.contains("Failed to observe", ignoreCase = true) || result.output.contains("Accessibility service", ignoreCase = true))
    }
}
