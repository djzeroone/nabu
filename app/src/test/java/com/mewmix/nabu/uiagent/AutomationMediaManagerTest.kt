package com.mewmix.nabu.uiagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomationMediaManagerTest {
    @Test(expected = IllegalArgumentException::class)
    fun `capture output rejects arbitrary MIME types before creating a file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AutomationMediaManager.createCaptureOutputUri(context, "application/octet-stream")
    }
}
