package com.mewmix.nabu.uiagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

fun UiActionStep.toJson(): JsonObject {
    val obj = JsonObject()
    when (this) {
        is UiActionStep.Tap -> {
            obj.addProperty("action", "tap")
            obj.add("target", this.target.toJson())
        }
        is UiActionStep.LongPress -> {
            obj.addProperty("action", "long_press")
            obj.add("target", this.target.toJson())
        }
        is UiActionStep.TypeText -> {
            obj.addProperty("action", "type_text")
            obj.addProperty("text", this.text)
            this.target?.let { obj.add("target", it.toJson()) }
        }
        UiActionStep.PressBack -> obj.addProperty("action", "press_back")
        UiActionStep.PressHome -> obj.addProperty("action", "press_home")
        UiActionStep.PressRecents -> obj.addProperty("action", "press_recents")
        UiActionStep.OpenNotifications -> obj.addProperty("action", "open_notifications")
        UiActionStep.OpenQuickSettings -> obj.addProperty("action", "open_quick_settings")
        is UiActionStep.Scroll -> {
            obj.addProperty("action", "scroll")
            obj.addProperty("direction", this.direction.name)
            this.target?.let { obj.add("target", it.toJson()) }
        }
        is UiActionStep.Wait -> {
            obj.addProperty("action", "wait")
            obj.addProperty("milliseconds", this.milliseconds)
        }
        is UiActionStep.Assert -> {
            obj.addProperty("action", "assert")
            val cond = JsonObject()
            this.condition.elementId?.let { cond.addProperty("element_id", it) }
            this.condition.textContains?.let { cond.addProperty("text_contains", it) }
            this.condition.checked?.let { cond.addProperty("checked", it) }
            obj.add("condition", cond)
        }
        is UiActionStep.AskUser -> {
            obj.addProperty("action", "ask_user")
            obj.addProperty("reason", this.reason)
        }
        is UiActionStep.Done -> {
            obj.addProperty("action", "done")
            obj.addProperty("summary", this.summary)
        }
        is UiActionStep.OpenApp -> {
            obj.addProperty("action", "open_app")
            obj.addProperty("package_name", this.packageName)
        }
        is UiActionStep.OpenSettingsPage -> {
            obj.addProperty("action", "open_settings_page")
            obj.addProperty("page", this.page.name)
            this.packageName?.let { obj.addProperty("package_name", it) }
        }
        is UiActionStep.OpenUrl -> {
            obj.addProperty("action", "open_url")
            obj.addProperty("url", this.url)
        }
        is UiActionStep.ShareText -> {
            obj.addProperty("action", "share_text")
            obj.addProperty("text", this.text)
            this.targetPackage?.let { obj.addProperty("target_package", it) }
            this.expectedDestination?.let { obj.addProperty("expected_destination", it) }
        }
        is UiActionStep.OpenCamera -> {
            obj.addProperty("action", "open_camera")
            obj.addProperty("mode", this.mode.name)
            obj.addProperty("facing", this.facing.name)
        }
        is UiActionStep.ShareCapturedMedia -> {
            obj.addProperty("action", "share_captured_media")
            obj.addProperty("target_package", this.targetPackage)
            obj.addProperty("expected_destination", this.expectedDestination)
        }
    }
    return obj
}

fun UiTarget.toJson(): JsonObject {
    val obj = JsonObject()
    this.elementId?.let { obj.addProperty("element_id", it) }
    this.textContains?.let { obj.addProperty("text_contains", it) }
    this.fallbackBounds?.let { bounds ->
        val arr = JsonArray()
        arr.add(bounds.left)
        arr.add(bounds.top)
        arr.add(bounds.right)
        arr.add(bounds.bottom)
        obj.add("fallback_bounds", arr)
    }
    return obj
}
