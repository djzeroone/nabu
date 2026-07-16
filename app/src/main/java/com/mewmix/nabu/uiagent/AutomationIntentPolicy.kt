package com.mewmix.nabu.uiagent

import android.content.Context
import android.content.pm.PackageManager

sealed interface IntentPolicyDecision {
    data object Allow : IntentPolicyDecision
    data class RequireConfirmation(val reason: String, val preview: String?) : IntentPolicyDecision
    data class Block(val reason: String) : IntentPolicyDecision
}

data class PolicyContext(
    val isScheduled: Boolean,
    val isDeviceLocked: Boolean,
    val destinationProvenance: String?, // e.g. "user-provided", "planner"
    val context: Context
)

object AutomationIntentPolicy {
    fun evaluate(action: UiActionStep, policyContext: PolicyContext): IntentPolicyDecision {
        if (policyContext.isDeviceLocked) {
            return IntentPolicyDecision.Block("Cannot execute intents while the device is locked.")
        }
        
        return when (action) {
            is UiActionStep.OpenApp -> evaluateOpenApp(action, policyContext)
            is UiActionStep.OpenSettingsPage -> evaluateOpenSettings(action, policyContext)
            is UiActionStep.OpenUrl -> evaluateOpenUrl(action, policyContext)
            is UiActionStep.ShareText -> evaluateShareText(action, policyContext)
            is UiActionStep.OpenCamera -> evaluateOpenCamera(action, policyContext)
            is UiActionStep.ShareCapturedMedia -> evaluateShareCapturedMedia(action, policyContext)
            else -> IntentPolicyDecision.Allow // Internal UI actions are governed by UiActionValidator
        }
    }

    private fun evaluateOpenApp(action: UiActionStep.OpenApp, policyContext: PolicyContext): IntentPolicyDecision {
        if (policyContext.isScheduled) {
            return IntentPolicyDecision.Block("Cannot open apps during scheduled background execution.")
        }
        if (policyContext.destinationProvenance == "planner") {
            val lower = action.packageName.lowercase()
            if (lower.contains("installer") || lower.contains("payment") || lower.contains("wallet") || 
                lower.contains("bank") || lower.contains("credential") || lower.contains("auth")) {
                return IntentPolicyDecision.Block("Planner cannot open installer, payment, or credential packages.")
            }
        }
        if (!isPackageInstalled(action.packageName, policyContext.context)) {
            return IntentPolicyDecision.Block("Target package '${action.packageName}' is not installed.")
        }
        return IntentPolicyDecision.Allow
    }

    private fun evaluateOpenSettings(action: UiActionStep.OpenSettingsPage, policyContext: PolicyContext): IntentPolicyDecision {
        if (policyContext.isScheduled) {
            return IntentPolicyDecision.Block("Cannot open settings during scheduled background execution.")
        }
        return when (action.page) {
            SettingsPage.DEVELOPER_OPTIONS,
            SettingsPage.WIRELESS_DEBUGGING,
            SettingsPage.APP_DETAILS,
            SettingsPage.ACCESSIBILITY -> 
                IntentPolicyDecision.RequireConfirmation("Navigating to sensitive settings page: ${action.page.name}", null)
            else -> IntentPolicyDecision.Allow
        }
    }

    private fun evaluateOpenUrl(action: UiActionStep.OpenUrl, policyContext: PolicyContext): IntentPolicyDecision {
        if (policyContext.isScheduled) {
            return IntentPolicyDecision.Block("Cannot open URLs during scheduled background execution.")
        }
        val lowerUrl = action.url.lowercase()
        if (lowerUrl.startsWith("file:") || lowerUrl.startsWith("javascript:") || lowerUrl.startsWith("intent:")) {
            return IntentPolicyDecision.Block("Unsafe URI scheme blocked: ${action.url}")
        }
        if (policyContext.destinationProvenance == "planner" && action.url.contains("@")) {
            return IntentPolicyDecision.Block("Planner cannot navigate to URLs containing embedded credentials.")
        }
        if (lowerUrl.startsWith("http://")) {
            return IntentPolicyDecision.RequireConfirmation("Opening unencrypted HTTP URL", action.url)
        }
        if (lowerUrl.startsWith("https://")) {
            return IntentPolicyDecision.Allow
        }
        return IntentPolicyDecision.Block("Unknown or unsupported URI scheme: ${action.url}")
    }

    private fun evaluateShareText(action: UiActionStep.ShareText, policyContext: PolicyContext): IntentPolicyDecision {
        if (policyContext.isScheduled) {
            return IntentPolicyDecision.Block("Cannot share text during scheduled background execution.")
        }
        if (action.targetPackage != null && !isPackageInstalled(action.targetPackage, policyContext.context)) {
            return IntentPolicyDecision.Block("Target package '${action.targetPackage}' is not installed.")
        }
        return IntentPolicyDecision.RequireConfirmation("Sending or sharing text", action.text)
    }

    private fun evaluateOpenCamera(action: UiActionStep.OpenCamera, policyContext: PolicyContext): IntentPolicyDecision {
        if (policyContext.isScheduled) {
            return IntentPolicyDecision.Block("Cannot open camera during scheduled background execution.")
        }
        return IntentPolicyDecision.Allow
    }

    private fun evaluateShareCapturedMedia(
        action: UiActionStep.ShareCapturedMedia,
        policyContext: PolicyContext
    ): IntentPolicyDecision {
        if (policyContext.isScheduled) {
            return IntentPolicyDecision.Block("Cannot share captured media during scheduled background execution.")
        }
        if (action.expectedDestination.isBlank()) {
            return IntentPolicyDecision.Block("Media sharing requires an explicit expected destination.")
        }
        if (!isPackageInstalled(action.targetPackage, policyContext.context)) {
            return IntentPolicyDecision.Block("Target package '${action.targetPackage}' is not installed.")
        }
        return IntentPolicyDecision.RequireConfirmation(
            "Opening a media composer with captured content",
            "${action.expectedDestination} via ${action.targetPackage}"
        )
    }

    private fun isPackageInstalled(packageName: String, context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            true
        }.getOrDefault(false)
    }
}
