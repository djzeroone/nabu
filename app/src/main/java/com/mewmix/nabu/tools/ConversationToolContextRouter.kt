package com.mewmix.nabu.tools

import android.content.Context
import org.json.JSONArray

/**
 * Maintains the active capability working set for each conversation.
 *
 * Tool retrieval is allowed to be lexical and cheap, but the resulting capability context must
 * survive terse clarification turns. This router also expands tools that require an unknown path
 * with compatible read-only discovery tools, based on tool schemas/names instead of user-specific
 * phrases or filenames.
 */
class ConversationToolContextRouter(context: Context) {
    private data class WorkingSet(
        val toolNames: List<String>,
        val lastMessageHash: Int,
        val idleTurns: Int
    )

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun resolve(
        conversationId: Long?,
        latestUserText: String,
        retrievedTools: List<Tool>,
        availableTools: List<Tool>,
        limit: Int
    ): List<Tool> {
        if (limit <= 0) return emptyList()
        val toolsByName = availableTools
            .filter { it.isAvailable }
            .associateBy { it.name }
        val key = conversationId?.toString() ?: TRANSIENT_KEY
        val previous = read(key)
        val retrieved = expandPrerequisites(
            selected = retrievedTools.filter { it.isAvailable },
            latestUserText = latestUserText,
            availableTools = availableTools
        )
        val meaningfulRetrieved = retrieved.filterNot(::isMetaTool)
        val previousTools = previous.toolNames.mapNotNull(toolsByName::get)
        val sameTurn = previous.lastMessageHash == latestUserText.hashCode()

        val resolved = when {
            meaningfulRetrieved.isNotEmpty() -> {
                val currentDomains = meaningfulRetrieved.flatMap(::domainTokens).toSet()
                val previousDomains = previousTools.flatMap(::domainTokens).toSet()
                if (currentDomains.intersect(previousDomains).isNotEmpty()) {
                    (retrieved + previousTools).distinctBy { it.name }
                } else {
                    retrieved.distinctBy { it.name }
                }
            }
            isContextDependent(latestUserText) && previousTools.isNotEmpty() -> {
                (previousTools + retrieved).distinctBy { it.name }
            }
            else -> retrieved.distinctBy { it.name }
        }.take(limit)

        val shouldRetain = meaningfulRetrieved.isNotEmpty() ||
            (isContextDependent(latestUserText) && previousTools.isNotEmpty())
        val idleTurns = when {
            shouldRetain -> 0
            sameTurn -> previous.idleTurns
            else -> previous.idleTurns + 1
        }
        val retainedNames = when {
            shouldRetain -> resolved.filterNot(::isMetaTool).map { it.name }
            idleTurns <= MAX_IDLE_TURNS -> previous.toolNames
            else -> emptyList()
        }
        write(
            key,
            WorkingSet(
                toolNames = retainedNames.take(limit),
                lastMessageHash = latestUserText.hashCode(),
                idleTurns = idleTurns
            )
        )
        return resolved
    }

    fun clear(conversationId: Long?) {
        prefs.edit().remove(storageKey(conversationId?.toString() ?: TRANSIENT_KEY)).apply()
    }

    private fun expandPrerequisites(
        selected: List<Tool>,
        latestUserText: String,
        availableTools: List<Tool>
    ): List<Tool> {
        val expanded = selected.toMutableList()
        val hasConcretePath = CONCRETE_PATH.containsMatchIn(latestUserText)
        if (hasConcretePath) return expanded.distinctBy { it.name }

        selected.forEach { consumer ->
            val requiredPathKeys = consumer.parameters.keys.filter(::isPathLikeKey)
            if (requiredPathKeys.isEmpty()) return@forEach
            val consumerDomains = domainTokens(consumer)
            availableTools.asSequence()
                .filter { it.isAvailable && it.name != consumer.name }
                .filter(::isReadOnlyDiscoveryTool)
                .filter { candidate ->
                    domainTokens(candidate).intersect(consumerDomains).isNotEmpty()
                }
                .forEach { candidate ->
                    if (expanded.none { it.name == candidate.name }) expanded += candidate
                }
        }
        return expanded.distinctBy { it.name }
    }

    private fun isContextDependent(text: String): Boolean {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return false
        return tokens.any { it in REFERENTIAL_TOKENS } ||
            REFERENTIAL_PHRASES.any { phrase -> text.contains(phrase, ignoreCase = true) } ||
            (tokens.size <= 3 && tokens.none { it in TOPIC_RESET_TOKENS })
    }

    private fun isReadOnlyDiscoveryTool(tool: Tool): Boolean {
        val operation = tokenize(tool.name).firstOrNull() ?: return false
        if (operation !in DISCOVERY_OPERATIONS) return false
        val normalizedDescription = tool.description.lowercase()
        return DESTRUCTIVE_OPERATIONS.none { it in operation || it in normalizedDescription }
    }

    private fun isMetaTool(tool: Tool): Boolean =
        tool.name == "list_tools" || domainTokens(tool) == setOf("tool")

    private fun domainTokens(tool: Tool): Set<String> {
        val nameTokens = tokenize(tool.name)
        val parameterTokens = tool.parameters.keys.flatMap(::tokenize)
        return (nameTokens + parameterTokens)
            .map(::singularize)
            .filterNot { it in OPERATION_TOKENS || it in SCHEMA_TOKENS }
            .toSet()
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length > 1 }

    private fun singularize(token: String): String = when {
        token.endsWith("ies") && token.length > 4 -> token.dropLast(3) + "y"
        token.endsWith("s") && token.length > 3 -> token.dropLast(1)
        else -> token
    }

    private fun isPathLikeKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized == "path" ||
            normalized.endsWith("_path") ||
            normalized == "uri" ||
            normalized.endsWith("_uri")
    }

    private fun read(key: String): WorkingSet {
        val storedNames = prefs.getString(storageKey(key), null)
            ?.let { raw ->
                runCatching {
                    val array = JSONArray(raw)
                    buildList {
                        for (index in 0 until array.length()) {
                            array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }.getOrDefault(emptyList())
            }
            .orEmpty()
        return WorkingSet(
            toolNames = storedNames,
            lastMessageHash = prefs.getInt(hashKey(key), 0),
            idleTurns = prefs.getInt(idleKey(key), 0)
        )
    }

    private fun write(key: String, workingSet: WorkingSet) {
        val names = JSONArray().apply {
            workingSet.toolNames.forEach(::put)
        }
        prefs.edit()
            .putString(storageKey(key), names.toString())
            .putInt(hashKey(key), workingSet.lastMessageHash)
            .putInt(idleKey(key), workingSet.idleTurns)
            .apply()
    }

    private fun storageKey(key: String) = "tools_$key"
    private fun hashKey(key: String) = "hash_$key"
    private fun idleKey(key: String) = "idle_$key"

    companion object {
        private const val PREFS_NAME = "conversation_tool_context"
        private const val TRANSIENT_KEY = "transient"
        private const val MAX_IDLE_TURNS = 1

        private val CONCRETE_PATH =
            Regex("""(?i)(?:^|\s)(?:/|content://|file://|[a-z]:\\)\S+""")
        private val DISCOVERY_OPERATIONS = setOf(
            "browse", "find", "list", "locate", "query", "search"
        )
        private val DESTRUCTIVE_OPERATIONS = setOf(
            "create", "delete", "move", "remove", "rename", "write"
        )
        private val OPERATION_TOKENS = DISCOVERY_OPERATIONS + DESTRUCTIVE_OPERATIONS + setOf(
            "add", "call", "capture", "control", "describe", "execute", "get", "guide",
            "launch", "open", "perform", "read", "retrieve", "run", "save", "send",
            "set", "share", "show", "take", "toggle"
        )
        private val SCHEMA_TOKENS = setOf(
            "argument", "id", "input", "name", "optional", "output", "page", "param",
            "parameter", "query", "root", "string", "text", "type", "value"
        )
        private val REFERENTIAL_TOKENS = setOf(
            "again", "former", "it", "latter", "same", "that", "them", "there",
            "these", "this", "those", "yourself"
        )
        private val REFERENTIAL_PHRASES = setOf(
            "do it", "do so", "go ahead", "keep going", "tool context"
        )
        private val TOPIC_RESET_TOKENS = setOf(
            "explain", "joke", "story", "weather", "why"
        )
    }
}
