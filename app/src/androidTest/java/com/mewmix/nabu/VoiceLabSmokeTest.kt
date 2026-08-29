package com.mewmix.nabu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mewmix.nabu.voicelab.VoiceLabTestTags
import org.junit.Rule
import org.junit.Test

class VoiceLabSmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun voiceLabIsReachableAndInspectable() {
        composeTestRule.onNodeWithText("MORE").performClick()
        composeTestRule.onNodeWithContentDescription("Voice Lab").performClick()

        composeTestRule.onNodeWithTag(VoiceLabTestTags.Screen).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VoiceLabTestTags.ScriptInput).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VoiceLabTestTags.EngineSelector).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VoiceLabTestTags.VoiceSelector).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VoiceLabTestTags.ParameterControls).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VoiceLabTestTags.PreviewButton).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VoiceLabTestTags.RenderFullButton).assertIsDisplayed()
        composeTestRule.onNodeWithTag(VoiceLabTestTags.PlaybackControls).assertIsDisplayed()
    }
}
