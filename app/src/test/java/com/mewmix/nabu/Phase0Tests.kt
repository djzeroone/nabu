package com.mewmix.nabu

import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mewmix.nabu.accessibility.AccessibilityToolHandler
import com.mewmix.nabu.accessibility.NabuAccessibilityService
import com.mewmix.nabu.tools.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.R])
class Phase0Tests {

    @Test
    fun testGlobalTriggerIntent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = ChatActivity.createGlobalTriggerIntent(context)
        
        assertEquals(intent.component?.className, ChatActivity::class.java.name)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
    }
    
    @Suppress("DEPRECATION")
    @Test
    fun testPasswordRedaction() {
        val node = AccessibilityNodeInfo.obtain()
        node.className = "android.widget.EditText"
        node.text = "secretpassword"
        node.contentDescription = "secretpassword_desc"
        node.isPassword = true

        val service = NabuAccessibilityService()
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val parent = document.createElement("hierarchy")
        document.appendChild(parent)

        val buildXmlTreeMethod = NabuAccessibilityService::class.java.getDeclaredMethod(
            "buildXmlTree",
            AccessibilityNodeInfo::class.java,
            org.w3c.dom.Element::class.java,
            org.w3c.dom.Document::class.java,
            String::class.java
        )
        buildXmlTreeMethod.isAccessible = true
        buildXmlTreeMethod.invoke(service, node, parent, document, "0")

        val generatedNode = parent.firstChild as org.w3c.dom.Element
        
        assertEquals("android.widget.EditText", generatedNode.getAttribute("class"))
        
        val expectedTextRedaction = "•".repeat("secretpassword".length)
        val expectedDescRedaction = "•".repeat("secretpassword_desc".length)
        
        assertEquals(expectedTextRedaction, generatedNode.getAttribute("text"))
        assertEquals(expectedDescRedaction, generatedNode.getAttribute("content-desc"))
    }

}
