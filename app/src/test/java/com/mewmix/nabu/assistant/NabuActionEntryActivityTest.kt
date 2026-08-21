package com.mewmix.nabu.assistant

import com.mewmix.nabu.ChatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NabuActionEntryActivityTest {
    @Test
    fun `falls back to trusted Chat trigger when accessibility surface is unavailable`() {
        val activity = Robolectric.buildActivity(NabuActionEntryActivity::class.java).create().get()

        val launched = shadowOf(activity).nextStartedActivity
        assertEquals(ChatActivity::class.java.name, launched.component?.className)
        assertTrue(launched.getBooleanExtra(ChatActivity.EXTRA_GLOBAL_TRIGGER, false))
        assertTrue(activity.isFinishing)
    }
}
