package com.mewmix.nabu.goal

import android.content.Context
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.ConstrainedDecisionDecoder

class CapabilityRegistryManager(
    private val context: Context,
    private val backend: LlmBackend,
    private val decoder: ConstrainedDecisionDecoder
) {
    val adapters: Map<CapabilityId, SurfaceAdapter> by lazy {
        mapOf(
            CapabilityId.APP_LAUNCH to AppLaunchAdapter(context, backend),
            CapabilityId.DEVICE_DIAGNOSTICS to DeviceDiagnosticsAdapter(context, backend),
            CapabilityId.SCREEN_DESCRIBE to ScreenDescribeAdapter(context, backend),
            CapabilityId.UI_GUIDE to UiGuideAdapter(context, backend, decoder),
            CapabilityId.UI_ACT to UiAutomationAdapter(context, backend, decoder)
        )
    }
}
