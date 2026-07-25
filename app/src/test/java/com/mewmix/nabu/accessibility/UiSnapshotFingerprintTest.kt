package com.mewmix.nabu.accessibility

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiSnapshotFingerprintTest {
    @Test
    fun `duplicate captures have the same state fingerprint`() {
        val first = snapshot(text = "Settings")
        val second = snapshot(text = "Settings")

        assertEquals(first.stateFingerprint, second.stateFingerprint)
    }

    @Test
    fun `semantic state change has a different fingerprint`() {
        val before = snapshot(text = "Wi-Fi")
        val after = snapshot(text = "Wi-Fi off")

        assertNotEquals(before.stateFingerprint, after.stateFingerprint)
    }

    private fun snapshot(text: String) = UiSnapshot(
        id = java.util.UUID.randomUUID().toString(),
        capturedAtMs = 1L,
        packageName = "com.android.settings",
        windowTitle = "Settings",
        rotation = 0,
        displayBounds = Rect(0, 0, 1080, 2400),
        rootNode = UiNode(
            treePath = "0",
            packageName = "com.android.settings",
            resourceId = "android:id/content",
            className = "android.widget.FrameLayout",
            text = text,
            contentDescription = "",
            isCheckable = false,
            isChecked = false,
            isClickable = false,
            isEnabled = true,
            isEditable = false,
            isFocusable = false,
            isFocused = false,
            isVisibleToUser = true,
            isScrollable = false,
            isLongClickable = false,
            isPassword = false,
            isSelected = false,
            boundsInScreen = Rect(0, 0, 1080, 2400),
            children = emptyList()
        )
    )
}
