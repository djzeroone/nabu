package com.mewmix.nabu.uiagent

import com.mewmix.nabu.tools.CapabilityId

enum class Operation {
    TAP,
    LONG_PRESS,
    TYPE_TEXT,
    SCROLL,
    PRESS_BACK,
    PRESS_HOME,
    PRESS_RECENTS,
    OPEN_NOTIFICATIONS,
    OPEN_QUICK_SETTINGS,
    OPEN_APP,
    OPEN_SETTINGS_PAGE,
    OPEN_URL,
    OPEN_CAMERA,
    WAIT,
    SHARE_TEXT,
    SHARE_CAPTURED_MEDIA,
    FOCUS
}

enum class ExpectedEffect {
    SURFACE_CHANGE,
    MUTATION,
    CONTENT_APPEAR,
    NO_CHANGE
}

data class PlannerElementId(val id: String)

data class UiQuery(
    val query: String,
    val expectedType: String? = null
)

sealed interface AgentDecision {
    data class Act(
        val operation: Operation,
        val target: PlannerElementId? = null,
        val arguments: Map<String, String> = emptyMap(),
        val expectedEffect: ExpectedEffect? = null
    ) : AgentDecision

    data class Query(
        val query: UiQuery
    ) : AgentDecision

    data class Delegate(
        val capability: CapabilityId,
        val objective: String
    ) : AgentDecision

    data class Ask(
        val question: String
    ) : AgentDecision

    data class Finish(
        val outcome: String
    ) : AgentDecision

    fun toUiActionPlan(goal: String, screenId: String): UiActionPlan {
        val step = when (this) {
            is Act -> {
                val uiTarget = target?.let { UiTarget(elementId = it.id, fallbackBounds = null) }
                when (operation) {
                    Operation.TAP -> UiActionStep.Tap(uiTarget ?: UiTarget(null, null))
                    Operation.LONG_PRESS -> UiActionStep.LongPress(uiTarget ?: UiTarget(null, null))
                    Operation.TYPE_TEXT -> UiActionStep.TypeText(text = arguments["text"] ?: "", target = uiTarget)
                    Operation.SCROLL -> UiActionStep.Scroll(
                        direction = ScrollDirection.valueOf(arguments["direction"]?.uppercase() ?: "DOWN"),
                        target = uiTarget
                    )
                    Operation.PRESS_BACK -> UiActionStep.PressBack
                    Operation.PRESS_HOME -> UiActionStep.PressHome
                    Operation.PRESS_RECENTS -> UiActionStep.PressRecents
                    Operation.OPEN_NOTIFICATIONS -> UiActionStep.OpenNotifications
                    Operation.OPEN_QUICK_SETTINGS -> UiActionStep.OpenQuickSettings
                    Operation.OPEN_APP -> {
                        UiActionStep.OpenApp(packageName = arguments["package_name"] ?: "")
                    }
                    Operation.OPEN_SETTINGS_PAGE -> UiActionStep.OpenSettingsPage(
                        page = SettingsPage.valueOf(arguments["page"]!!.uppercase()),
                        packageName = arguments["package_name"]
                    )
                    Operation.OPEN_URL -> UiActionStep.OpenUrl(arguments["url"] ?: "")
                    Operation.OPEN_CAMERA -> UiActionStep.OpenCamera(
                        mode = CameraMode.valueOf(arguments["mode"]!!.uppercase()),
                        facing = CameraFacing.valueOf(arguments["facing"]!!.uppercase())
                    )
                    Operation.WAIT -> UiActionStep.Wait(milliseconds = arguments["ms"]?.toLongOrNull() ?: 1000L)
                    Operation.SHARE_TEXT -> UiActionStep.ShareText(
                        text = arguments["text"]!!,
                        targetPackage = arguments["target_package"],
                        expectedDestination = arguments["expected_destination"]
                    )
                    Operation.SHARE_CAPTURED_MEDIA -> UiActionStep.ShareCapturedMedia(
                        targetPackage = arguments["target_package"] ?: "",
                        expectedDestination = arguments["expected_destination"] ?: ""
                    )
                    Operation.FOCUS -> UiActionStep.Focus(uiTarget ?: UiTarget(null, null))
                }
            }
            is Query -> UiActionStep.Done("Query not directly supported in UiActionPlan execution.")
            is Delegate -> UiActionStep.Done("Delegate not directly supported in UiActionPlan execution.")
            is Ask -> UiActionStep.AskUser(reason = question)
            is Finish -> UiActionStep.Done(summary = outcome)
        }
        return UiActionPlan(goal = goal, screenId = screenId, steps = listOf(step))
    }
}
