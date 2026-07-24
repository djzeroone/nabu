package com.mewmix.nabu.uiagent

internal enum class TelegramSurface {
    CHAT_LIST,
    SEARCH,
    CHAT,
    UNKNOWN
}

internal data class TelegramSemanticTarget(
    val kind: String,
    val label: String,
    val elementId: String
)

internal data class TelegramUiContext(
    val surface: TelegramSurface,
    val targets: List<TelegramSemanticTarget>,
    val ambiguousKinds: Map<String, Int>
)

internal object TelegramUiAdapter {
    private val telegramPackages = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web"
    )

    fun inspect(screen: UiScreenState): TelegramUiContext? {
        val packageName = screen.packageName?.lowercase() ?: return null
        if (packageName !in telegramPackages && !packageName.startsWith("org.telegram.messenger.")) {
            return null
        }

        val actionable = screen.plannerElements()
        val composerCandidates = actionable.filter { element ->
            element.editable && semanticEvidence(screen, element).matchesAny(COMPOSER_TERMS)
        }
        val searchCandidates = actionable.filter { element ->
            element.editable && semanticEvidence(screen, element).matchesAny(SEARCH_TERMS)
        }
        val sendCandidates = actionable.filter { element ->
            (element.clickable || element.checkable) &&
                semanticEvidence(screen, element).matchesAny(SEND_TERMS)
        }
        val savedMessagesCandidates = actionable.filter { element ->
            (element.clickable || element.checkable) &&
                semanticLabels(screen, element).any { label ->
                    normalize(label) == "saved messages"
                }
        }

        val targets = buildList {
            composerCandidates.singleOrNull()?.let {
                add(TelegramSemanticTarget("composer", "Message composer", it.id))
            }
            sendCandidates.singleOrNull()?.let {
                add(TelegramSemanticTarget("send", "Send", it.id))
            }
            savedMessagesCandidates.singleOrNull()?.let {
                add(TelegramSemanticTarget("chat", "Saved Messages", it.id))
            }
            searchCandidates.singleOrNull()?.let {
                add(TelegramSemanticTarget("search_input", "Search", it.id))
            }
        }
        val ambiguous = buildMap {
            if (composerCandidates.size > 1) put("composer", composerCandidates.size)
            if (sendCandidates.size > 1) put("send", sendCandidates.size)
            if (savedMessagesCandidates.size > 1) put("chat:Saved Messages", savedMessagesCandidates.size)
            if (searchCandidates.size > 1) put("search_input", searchCandidates.size)
        }
        val surface = when {
            composerCandidates.size == 1 -> TelegramSurface.CHAT
            searchCandidates.size == 1 && actionable.any {
                semanticEvidence(screen, it).matchesAny(SEARCH_RESULT_TERMS)
            } -> TelegramSurface.SEARCH
            actionable.any { semanticEvidence(screen, it).matchesAny(CHAT_LIST_TERMS) } ||
                savedMessagesCandidates.isNotEmpty() -> TelegramSurface.CHAT_LIST
            else -> TelegramSurface.UNKNOWN
        }
        return TelegramUiContext(surface, targets, ambiguous)
    }

    private fun semanticEvidence(screen: UiScreenState, element: UiElement): String = buildList {
        addAll(semanticLabels(screen, element))
        element.resourceId?.let(::add)
        element.className?.let(::add)
    }.joinToString(" ").lowercase()

    private fun semanticLabels(screen: UiScreenState, element: UiElement): List<String> =
        screen.plannerLabel(element)
            ?.split('|')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()

    private fun String.matchesAny(terms: Collection<String>): Boolean = terms.any { contains(it) }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private val COMPOSER_TERMS = setOf("message", "write a message", "type a message", "chat_message_edit")
    private val SEARCH_TERMS = setOf("search", "search chats", "search_src")
    private val SEND_TERMS = setOf("send", "send message", "chat_send")
    private val SEARCH_RESULT_TERMS = setOf("search results", "global search", "chats and contacts")
    private val CHAT_LIST_TERMS = setOf("new message", "navigation menu", "search", "chats")
}
