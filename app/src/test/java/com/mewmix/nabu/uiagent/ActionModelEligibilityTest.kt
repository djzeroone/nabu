package com.mewmix.nabu.uiagent

import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionModelEligibilityTest {
    @Test
    fun `local llm is eligible for action runtime`() {
        assertTrue(ActionModelEligibility.isEligible(model("local", "llama")))
    }

    @Test
    fun `codex oauth is excluded from action runtime`() {
        assertFalse(ActionModelEligibility.isEligible(model("oauth://codex/gpt-5.5", "codex_oauth")))
        assertFalse(ActionModelEligibility.isEligible(model("codex-byos-oauth", "codex_oauth")))
    }

    @Test
    fun `non llm is excluded from action runtime`() {
        assertFalse(ActionModelEligibility.isEligible(model("voice", "local", ModelType.TTS)))
    }

    private fun model(id: String, backend: String, type: ModelType = ModelType.LLM) = Model(
        id = id,
        name = id,
        description = "",
        repo = "",
        downloadUrl = "",
        gated = false,
        type = type,
        initialIsDownloaded = true,
        initialBackend = backend
    )
}
