package com.mewmix.nabu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.mewmix.nabu.voicelab.VoiceLabTestTags
import org.junit.Rule
import org.junit.Test

class VoiceLabSmokeTest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    @Test
    fun voiceLabIsReachableAndInspectable() {
        launchMainActivityForSmokeTest("VoiceLab").use {
            composeTestRule.waitForNodeWithTag(VoiceLabTestTags.Screen)
            composeTestRule.onNodeWithTag(VoiceLabTestTags.Screen).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.ScriptInput).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.EngineSelector).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.VoiceSelector).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.ParameterControls).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.PreviewButton).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.RenderFullButton).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.RuntimeDiagnostics).assertIsDisplayed()
            composeTestRule.onNodeWithTag(VoiceLabTestTags.PlaybackControls).assertIsDisplayed()
        }
    }
}
