package com.mewmix.nabu.goal

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.uiagent.AgentDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class DeviceDiagnosticsAdapterTest {

    private val mockContext = mock(Context::class.java)
    private val mockConnectivityManager = mock(ConnectivityManager::class.java)
    private val mockNetwork = mock(Network::class.java)
    private val mockCapabilities = mock(NetworkCapabilities::class.java)

    private val mockBackend = object : LlmBackend {
        var receivedUserContent = ""
        override fun initialize() {}
        override fun supportsImageInput() = false
        override fun runtimeDescription() = "MockBackend"
        override fun sendMessage(
            conversation: List<LlmMessage>,
            resultListener: (String, Boolean) -> Unit
        ) {
            receivedUserContent = conversation.find { it.role == "user" }?.content ?: ""
            resultListener("The battery is at 80% and Wi-Fi is connected.", true)
        }
        override fun sendMessage(
            prompt: String,
            resultListener: (String, Boolean) -> Unit
        ) {
            resultListener("The battery is at 80% and Wi-Fi is connected.", true)
        }
        override fun cancel() {}
        override fun close() {}
    }

    @Test
    fun testDeviceDiagnosticsReturnsExpectedStatus() = runBlocking {
        val mockIntent = mock(Intent::class.java)
        `when`(mockIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).thenReturn(80)
        `when`(mockIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)).thenReturn(100)
        
        `when`(mockContext.registerReceiver(any(), any())).thenReturn(mockIntent)
        `when`(mockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mockConnectivityManager)
        `when`(mockConnectivityManager.activeNetwork).thenReturn(mockNetwork)
        `when`(mockConnectivityManager.getNetworkCapabilities(mockNetwork)).thenReturn(mockCapabilities)
        `when`(mockCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)

        val adapter = DeviceDiagnosticsAdapter(mockContext, mockBackend)
        
        val result = adapter.executeStep("Check the battery and wifi status.")
        
        assertTrue(mockBackend.receivedUserContent.contains("Battery Level: 80%"))
        assertTrue(mockBackend.receivedUserContent.contains("Network State: Connected (Wi-Fi)"))
        
        assertTrue(result is AgentDecision.Finish)
        assertEquals("The battery is at 80% and Wi-Fi is connected.", (result as AgentDecision.Finish).outcome)
    }
}
