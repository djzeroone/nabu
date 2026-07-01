package com.mewmix.nabu.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackCoordinatorTest {
    @Test
    fun newOwnerCancelsPreviousPlaybackAndInvalidatesItsToken() {
        val firstOwner = Any()
        val secondOwner = Any()
        var firstCancelled = 0
        var secondCancelled = 0

        val firstToken = SpeechPlaybackCoordinator.begin(firstOwner) { firstCancelled++ }
        assertTrue(SpeechPlaybackCoordinator.isCurrent(firstOwner, firstToken))

        val secondToken = SpeechPlaybackCoordinator.begin(secondOwner) { secondCancelled++ }

        assertEquals(1, firstCancelled)
        assertFalse(SpeechPlaybackCoordinator.isCurrent(firstOwner, firstToken))
        assertTrue(SpeechPlaybackCoordinator.isCurrent(secondOwner, secondToken))

        SpeechPlaybackCoordinator.cancel(secondOwner)
        assertEquals(1, secondCancelled)
        assertFalse(SpeechPlaybackCoordinator.isCurrent(secondOwner, secondToken))
    }
}
