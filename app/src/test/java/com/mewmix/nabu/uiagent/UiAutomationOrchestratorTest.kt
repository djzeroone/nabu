package com.mewmix.nabu.uiagent

import android.content.Context
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
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

        // Without an accessibility service, observe() returns null on the first call,
        // so run() terminates immediately with an Accessibility Service error.
        val result = orchestrator.run("test goal")
        assertTrue(
            "Unexpected result: ${result.output}",
            result.output.contains("Accessibility Service", ignoreCase = true) ||
            result.output.contains("observe", ignoreCase = true)
        )
    }

    @Test
    fun `concurrent automation waits in queue instead of failing`() = runBlocking {
        val context = mock(Context::class.java)
        val phases = mutableListOf<String>()
        val backend = object : LlmBackend {
            override fun initialize() {}
            override fun close() {}
            override fun cancel() {}
            override fun sendMessage(conversation: List<LlmMessage>, resultListener: (String, Boolean) -> Unit) {}
            override fun sendMessage(prompt: String, resultListener: (String, Boolean) -> Unit) {}
        }
        val orchestrator = UiAutomationOrchestrator(
            context = context,
            backend = backend,
            requestConfirmation = { true },
            onProgress = { phase, _ -> phases += phase }
        )

        UiAutomationOrchestrator.sessionMutex.lock()
        try {
            val queued = async { orchestrator.run("Open Calculator") }
            withTimeout(2_000) {
                while ("Queue" !in phases) delay(10)
            }
            assertFalse(queued.isCompleted)
            UiAutomationOrchestrator.sessionMutex.unlock()

            val result = withTimeout(2_000) { queued.await() }
            assertTrue(
                result.output.contains("Accessibility Service", ignoreCase = true) ||
                    result.output.contains("observe", ignoreCase = true)
            )
        } finally {
            if (UiAutomationOrchestrator.sessionMutex.isLocked) {
                UiAutomationOrchestrator.sessionMutex.unlock()
            }
        }
    }

    // TODO: Action-limit termination test belongs in the instrumentation suite because
    // the orchestrator's observe() calls AccessibilityToolHandler which requires the
    // live accessibility service. A unit test cannot exercise the full action loop
    // without a mock accessibility bridge, which would require refactoring observe()
    // to accept an injectable observation source. See docs/DEEP_AUTOMATION_SPEC.md §12.2.
}
