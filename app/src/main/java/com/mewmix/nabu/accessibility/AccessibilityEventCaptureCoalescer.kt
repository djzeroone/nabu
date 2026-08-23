package com.mewmix.nabu.accessibility

/** Thread-safe burst accounting for leading-edge plus trailing Accessibility captures. */
internal class AccessibilityEventCaptureCoalescer {
    data class EventPlan(
        val generation: Long,
        val captureLeading: Boolean
    )

    private var generation = 0L
    private var burstActive = false

    @Synchronized
    fun onMeaningfulEvent(): EventPlan {
        generation += 1L
        val captureLeading = !burstActive
        burstActive = true
        return EventPlan(generation, captureLeading)
    }

    @Synchronized
    fun isCurrent(generation: Long): Boolean =
        burstActive && this.generation == generation

    @Synchronized
    fun finishTrailing(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        burstActive = false
        return true
    }

    @Synchronized
    fun reset() {
        generation += 1L
        burstActive = false
    }
}
