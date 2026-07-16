package com.mewmix.nabu.uiagent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationMediaManagerInstrumentedTest {
    @Test
    fun fileProviderCaptureIsWritableHashableAndDeleted() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = AutomationMediaManager.createCaptureOutputUri(
            context,
            AutomationMediaManager.IMAGE_MIME_TYPE
        )
        try {
            assertEquals("content", uri.scheme)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            }
            assertTrue(AutomationMediaManager.validateCaptureOutput(context, uri))
            assertTrue(AutomationMediaManager.contentSha256(context, uri)?.length == 64)
        } finally {
            AutomationMediaManager.cleanupAll(context, listOf(uri))
        }
        assertFalse(AutomationMediaManager.validateCaptureOutput(context, uri))
    }
}
