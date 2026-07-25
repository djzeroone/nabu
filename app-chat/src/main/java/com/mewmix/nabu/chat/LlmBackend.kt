package com.mewmix.nabu.chat

data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String
)

data class LlmStructuredToolCall(
    val name: String,
    val arguments: Map<String, Any?>
)

data class LlmStructuredResult(
    val toolCall: LlmStructuredToolCall? = null,
    val text: String = ""
)

interface LlmBackend {
    fun runtimeDescription(): String = "UNKNOWN"

    fun initialize()

    fun sendMessage(
        conversation: List<LlmMessage>,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    )

    fun sendMessage(
        prompt: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    )

    fun supportsImageInput(): Boolean = false

    fun supportsAudioInput(): Boolean = false

    /**
     * Returns null when this backend does not expose native structured tool
     * calling. A non-null result represents one model inference, even when the
     * model returned text instead of selecting a tool.
     */
    fun generateStructured(
        conversation: List<LlmMessage>,
        tools: List<LlmToolDefinition>
    ): LlmStructuredResult? = null

    fun sendMessage(
        conversation: List<LlmMessage>,
        image: LlmImageInput,
        resultListener: (partialResult: String, done: Boolean) -> Unit
    ) {
        resultListener("Image input is not supported by this backend.", true)
    }

    fun cancel()

    fun close()
}
