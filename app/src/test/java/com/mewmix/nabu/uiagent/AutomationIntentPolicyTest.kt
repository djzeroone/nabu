package com.mewmix.nabu.uiagent

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AutomationIntentPolicyTest {

    private lateinit var mockContext: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setup() {
        packageManager = mock(PackageManager::class.java)
        mockContext = mock(Context::class.java)
        `when`(mockContext.packageManager).thenReturn(packageManager)
    }

    private fun mockPackageInstalled(packageName: String, installed: Boolean) {
        if (installed) {
            `when`(packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA))
                .thenReturn(PackageInfo())
        } else {
            `when`(packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA))
                .thenThrow(PackageManager.NameNotFoundException())
        }
    }

    @Test
    fun `locked device blocks all intents`() {
        val policyContext = PolicyContext(false, true, "planner", mockContext)
        val action = UiActionStep.OpenApp("com.example.app")
        val decision = AutomationIntentPolicy.evaluate(action, policyContext)
        assertTrue(decision is IntentPolicyDecision.Block)
    }

    @Test
    fun `scheduled context blocks interactive intents`() {
        val policyContext = PolicyContext(true, false, "planner", mockContext)
        
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenApp("pkg"), policyContext) is IntentPolicyDecision.Block)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.WIFI, null), policyContext) is IntentPolicyDecision.Block)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("https://example.com"), policyContext) is IntentPolicyDecision.Block)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.ShareText("text", null), policyContext) is IntentPolicyDecision.Block)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenCamera(CameraMode.PHOTO, CameraFacing.UNSPECIFIED), policyContext) is IntentPolicyDecision.Block)
    }

    @Test
    fun `open app checks installation`() {
        val policyContext = PolicyContext(false, false, "planner", mockContext)
        
        mockPackageInstalled("com.installed", true)
        mockPackageInstalled("com.missing", false)
        
        assertEquals(IntentPolicyDecision.Allow, AutomationIntentPolicy.evaluate(UiActionStep.OpenApp("com.installed"), policyContext))
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenApp("com.missing"), policyContext) is IntentPolicyDecision.Block)
    }

    @Test
    fun `open settings requires confirmation for sensitive pages`() {
        val policyContext = PolicyContext(false, false, "planner", mockContext)
        
        assertEquals(IntentPolicyDecision.Allow, AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.WIFI, null), policyContext))
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.DEVELOPER_OPTIONS, null), policyContext) is IntentPolicyDecision.RequireConfirmation)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.WIRELESS_DEBUGGING, null), policyContext) is IntentPolicyDecision.RequireConfirmation)
    }

    @Test
    fun `open url enforces safe schemes`() {
        val policyContext = PolicyContext(false, false, "planner", mockContext)
        
        assertEquals(IntentPolicyDecision.Allow, AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("https://safe.com"), policyContext))
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("http://unencrypted.com"), policyContext) is IntentPolicyDecision.RequireConfirmation)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("file:///sdcard/passwords.txt"), policyContext) is IntentPolicyDecision.Block)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("javascript:alert(1)"), policyContext) is IntentPolicyDecision.Block)
        assertTrue(AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("intent://details?id=com.malware#Intent;scheme=android-app;end"), policyContext) is IntentPolicyDecision.Block)
    }
}
