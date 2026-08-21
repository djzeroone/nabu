package com.mewmix.nabu.assistant

import android.app.Activity
import android.os.Bundle
import com.mewmix.nabu.ChatActivity
import com.mewmix.nabu.accessibility.NabuAccessibilityService

/**
 * Trusted in-app trampoline used by Quick Settings to collapse SystemUI before showing Nabu's
 * lightweight action surface. It never accepts request text or other untrusted extras.
 */
class NabuActionEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!NabuAccessibilityService.requestActionSurface()) {
            startActivity(ChatActivity.createGlobalTriggerIntent(this))
        }
        finish()
    }
}
