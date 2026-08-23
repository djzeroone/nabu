package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction

sealed interface DirectRequestResolution {
    data class Resolved(
        val action: UiActionStep,
        val reason: String,
        val completesGoalWhenVerified: Boolean,
        val displayLabel: String? = null
    ) : DirectRequestResolution

    data object NeedsObservation : DirectRequestResolution
    data object NeedsReasoning : DirectRequestResolution
}

/** Exact request routing only. Partial or duplicate matches deliberately remain unresolved. */
object DirectRequestResolver {
    fun resolve(
        request: String,
        apps: List<DeviceAction.AppCandidate>,
        supportedSystemActions: Set<String>
    ): DirectRequestResolution {
        val normalized = normalize(request).removePrefix("please ")
        globalAction(normalized, supportedSystemActions)?.let {
            return DirectRequestResolution.Resolved(it, "exact_global_action", true)
        }

        val launch = APP_LAUNCH.matchEntire(normalized)
        if (launch != null) {
            val requested = launch.groupValues[1].trim()
            exactApp(requested, apps)?.let { app ->
                return DirectRequestResolution.Resolved(
                    UiActionStep.OpenApp(app.packageName),
                    "exact_unique_launcher_label",
                    true,
                    app.label
                )
            }
        }

        // A complete launcher label embedded in a larger goal is a safe deterministic prefix.
        DeviceAction.explicitGoalAppCandidate(request, apps)?.let { app ->
            return DirectRequestResolution.Resolved(
                UiActionStep.OpenApp(app.packageName),
                "exact_launcher_label_prefix",
                false,
                app.label
            )
        }
        return if (looksLikeUiMutation(normalized)) {
            DirectRequestResolution.NeedsObservation
        } else {
            DirectRequestResolution.NeedsReasoning
        }
    }

    private fun exactApp(
        requested: String,
        apps: List<DeviceAction.AppCandidate>
    ): DeviceAction.AppCandidate? {
        val cleanedRequest = requested.trimEnd('.', '_')
        val matches = apps.filter { app ->
            normalize(app.label) == cleanedRequest || app.packageName.lowercase() == cleanedRequest
        }.distinctBy(DeviceAction.AppCandidate::packageName)
        return matches.singleOrNull()
    }

    private fun globalAction(normalized: String, supported: Set<String>): UiActionStep? {
        val (token, action) = when (normalized) {
            "press home", "go home", "go to home", "open home screen", "show home screen" ->
                "home" to UiActionStep.PressHome
            "press back", "go back" -> "back" to UiActionStep.PressBack
            "open recents", "show recents", "press recents" ->
                "recents" to UiActionStep.PressRecents
            "open notifications", "show notifications", "open notification shade" ->
                "notifications" to UiActionStep.OpenNotifications
            "open quick settings", "show quick settings" ->
                "quick_settings" to UiActionStep.OpenQuickSettings
            "dismiss notifications", "close notifications", "dismiss notification shade",
            "close notification shade" ->
                "dismiss_notification_shade" to UiActionStep.GlobalAction("dismiss_notification_shade")
            else -> return null
        }
        return action.takeIf { token in supported }
    }

    private fun looksLikeUiMutation(value: String): Boolean =
        UI_PREFIXES.any { value.startsWith("$it ") } || value in SCROLL_COMMANDS

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9._]+"), " ")
        .trim()

    private val APP_LAUNCH = Regex("(?:open|launch|start|show) (.+)")
    private val UI_PREFIXES = setOf("tap", "click", "activate", "press", "focus", "expand", "collapse", "set")
    private val SCROLL_COMMANDS = setOf("scroll up", "scroll down", "scroll left", "scroll right")
}
