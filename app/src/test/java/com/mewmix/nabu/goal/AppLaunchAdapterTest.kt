package com.mewmix.nabu.goal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.uiagent.AgentDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class AppLaunchAdapterTest {

    private val mockContext = mock(Context::class.java)
    private val mockPackageManager = mock(PackageManager::class.java)

    private val mockBackend = object : LlmBackend {
        var responseToReturn = "com.google.android.youtube"
        override fun initialize() {}
        override fun supportsImageInput() = false
        override fun runtimeDescription() = "MockBackend"
        override fun sendMessage(
            conversation: List<LlmMessage>,
            resultListener: (String, Boolean) -> Unit
        ) {
            resultListener(responseToReturn, true)
        }
        override fun sendMessage(
            prompt: String,
            resultListener: (String, Boolean) -> Unit
        ) {
            resultListener(responseToReturn, true)
        }
        override fun cancel() {}
        override fun close() {}
    }

    @Test
    fun testAppLaunchResolvesPackageAndFiresIntent() = runBlocking {
        val mockIntent = mock(Intent::class.java)
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        `when`(mockPackageManager.getLaunchIntentForPackage(anyString())).thenReturn(mockIntent)

        val adapter = AppLaunchAdapter(mockContext, mockBackend)
        
        val result = adapter.executeStep("Open YouTube")
        
        assertTrue(result is AgentDecision.Finish)
        assertEquals("Successfully launched com.google.android.youtube", (result as AgentDecision.Finish).outcome)
    }

    @Test
    fun testAppLaunchFailsIfAppNotFound() = runBlocking {
        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        `when`(mockPackageManager.getLaunchIntentForPackage(anyString())).thenReturn(null)
        mockBackend.responseToReturn = "com.fake.app"

        val adapter = AppLaunchAdapter(mockContext, mockBackend)
        
        val result = adapter.executeStep("Open Fake App")
        
        assertTrue(result is AgentDecision.Finish)
        assertEquals("App not found: com.fake.app", (result as AgentDecision.Finish).outcome)
    }
}
