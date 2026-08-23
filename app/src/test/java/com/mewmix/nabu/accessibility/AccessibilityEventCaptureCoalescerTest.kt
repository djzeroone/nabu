package com.mewmix.nabu.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEventCaptureCoalescerTest {
    @Test
    fun `first event captures leading edge and burst events share one trailing generation`() {
        val coalescer = AccessibilityEventCaptureCoalescer()

        val first = coalescer.onMeaningfulEvent()
        val second = coalescer.onMeaningfulEvent()

        assertTrue(first.captureLeading)
        assertFalse(second.captureLeading)
        assertFalse(coalescer.isCurrent(first.generation))
        assertTrue(coalescer.isCurrent(second.generation))
        assertFalse(coalescer.finishTrailing(first.generation))
        assertTrue(coalescer.finishTrailing(second.generation))
    }

    @Test
    fun `event after trailing capture starts a new leading edge`() {
        val coalescer = AccessibilityEventCaptureCoalescer()
        val first = coalescer.onMeaningfulEvent()
        assertTrue(coalescer.finishTrailing(first.generation))

        assertTrue(coalescer.onMeaningfulEvent().captureLeading)
    }

    @Test
    fun `reset invalidates scheduled trailing capture`() {
        val coalescer = AccessibilityEventCaptureCoalescer()
        val pending = coalescer.onMeaningfulEvent()

        coalescer.reset()

        assertFalse(coalescer.isCurrent(pending.generation))
        assertFalse(coalescer.finishTrailing(pending.generation))
        assertTrue(coalescer.onMeaningfulEvent().captureLeading)
    }
}
