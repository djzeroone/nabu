package com.mewmix.nabu.uiagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class AutomationFlowMode {
    CONTROL,
    GUIDE
}

data class SavedAutomationFlow(
    val name: String,
    val goal: String,
    val mode: AutomationFlowMode,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

/**
 * Small, durable library of semantic action flows.
 *
 * Flows intentionally save an outcome and execution mode rather than stale coordinates or
 * accessibility node IDs. Replays observe the live screen and re-plan every action, which makes
 * them repeatable across app restarts and modest layout changes.
 */
object AutomationFlowStore {
    private const val PREFS = "automation_flows"
    private const val KEY_FLOWS = "flows"

    fun save(
        context: Context,
        name: String,
        goal: String,
        mode: AutomationFlowMode
    ): SavedAutomationFlow {
        val normalizedName = normalizeName(name)
        require(normalizedName.isNotBlank()) { "Flow name is blank." }
        val normalizedGoal = goal.trim()
        require(normalizedGoal.isNotBlank()) { "Flow goal is blank." }
        val existing = list(context).firstOrNull {
            it.name.equals(normalizedName, ignoreCase = true)
        }
        val now = System.currentTimeMillis()
        val saved = SavedAutomationFlow(
            name = normalizedName,
            goal = normalizedGoal,
            mode = mode,
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now
        )
        val updated = list(context)
            .filterNot { it.name.equals(normalizedName, ignoreCase = true) } + saved
        write(context, updated)
        return saved
    }

    fun get(context: Context, name: String): SavedAutomationFlow? {
        val normalized = normalizeName(name)
        return list(context).firstOrNull { it.name.equals(normalized, ignoreCase = true) }
    }

    fun list(context: Context): List<SavedAutomationFlow> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FLOWS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val name = item.optString("name").trim()
                    val goal = item.optString("goal").trim()
                    val mode = runCatching {
                        AutomationFlowMode.valueOf(item.optString("mode"))
                    }.getOrDefault(AutomationFlowMode.CONTROL)
                    if (name.isNotBlank() && goal.isNotBlank()) {
                        add(
                            SavedAutomationFlow(
                                name = name,
                                goal = goal,
                                mode = mode,
                                createdAtMs = item.optLong("created_at_ms"),
                                updatedAtMs = item.optLong("updated_at_ms")
                            )
                        )
                    }
                }
            }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun delete(context: Context, name: String): Boolean {
        val normalized = normalizeName(name)
        val current = list(context)
        val updated = current.filterNot { it.name.equals(normalized, ignoreCase = true) }
        if (updated.size == current.size) return false
        write(context, updated)
        return true
    }

    private fun write(context: Context, flows: List<SavedAutomationFlow>) {
        val array = JSONArray()
        flows.forEach { flow ->
            array.put(
                JSONObject()
                    .put("name", flow.name)
                    .put("goal", flow.goal)
                    .put("mode", flow.mode.name)
                    .put("created_at_ms", flow.createdAtMs)
                    .put("updated_at_ms", flow.updatedAtMs)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FLOWS, array.toString())
            .apply()
    }

    private fun normalizeName(name: String): String =
        name.trim().trim('"', '\'', '.', ':').replace(Regex("""\s+"""), " ").take(80)
}
