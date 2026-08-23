package com.mewmix.nabu.uiagent

import com.mewmix.nabu.actions.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRequestResolverTest {
    private val apps = listOf(
        DeviceAction.AppCandidate("Spotify", "com.spotify.music"),
        DeviceAction.AppCandidate("Telegram", "org.telegram.messenger"),
        DeviceAction.AppCandidate("Mail", "example.mail.one"),
        DeviceAction.AppCandidate("Mail", "example.mail.two")
    )

    @Test
    fun `exact unique app launch is terminal and needs no model`() {
        val result = resolve("Open Spotify") as DirectRequestResolution.Resolved
        assertEquals(UiActionStep.OpenApp("com.spotify.music"), result.action)
        assertTrue(result.completesGoalWhenVerified)
        assertEquals("Spotify", result.displayLabel)
        assertEquals(
            UiActionStep.OpenApp("com.spotify.music"),
            (resolve("Open Spotify.") as DirectRequestResolution.Resolved).action
        )
    }

    @Test
    fun `package name can resolve exact launch`() {
        val result = resolve("open org.telegram.messenger") as DirectRequestResolution.Resolved
        assertEquals(UiActionStep.OpenApp("org.telegram.messenger"), result.action)
    }

    @Test
    fun `ambiguous launcher label is never guessed`() {
        assertTrue(resolve("open Mail") is DirectRequestResolution.NeedsReasoning)
    }

    @Test
    fun `exact app in complex goal becomes nonterminal prefix`() {
        val result = resolve("Open Spotify and find yesterday's playlist") as DirectRequestResolution.Resolved
        assertEquals(UiActionStep.OpenApp("com.spotify.music"), result.action)
        assertFalse(result.completesGoalWhenVerified)
    }

    @Test
    fun `advertised globals resolve without observation`() {
        assertEquals(UiActionStep.PressHome, resolved("go home", setOf("home")))
        assertEquals(UiActionStep.OpenQuickSettings, resolved("show quick settings", setOf("quick_settings")))
        assertEquals(
            UiActionStep.GlobalAction("dismiss_notification_shade"),
            resolved("dismiss notification shade", setOf("dismiss_notification_shade"))
        )
    }

    @Test
    fun `unadvertised global is not executed`() {
        assertTrue(resolve("go home") is DirectRequestResolution.NeedsReasoning)
    }

    @Test
    fun `semantic tap requests observation before reasoning`() {
        assertTrue(resolve("tap Search") is DirectRequestResolution.NeedsObservation)
    }

    private fun resolve(request: String, system: Set<String> = emptySet()) =
        DirectRequestResolver.resolve(request, apps, system)

    private fun resolved(request: String, system: Set<String>) =
        (resolve(request, system) as DirectRequestResolution.Resolved).action
}
