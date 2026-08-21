package com.mewmix.nabu.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedGestureCatalogTest {
    @Test
    fun `double tap timing is owned by one bounded plan`() {
        val plan = BoundedGestureCatalog.build(
            "double_tap",
            start = NormalizedPoint(0.5f, 0.5f),
            end = null,
            center = null
        )

        assertEquals(2, plan.strokes.size)
        assertTrue(plan.strokes[1].startTimeMs > plan.strokes[0].durationMs)
    }

    @Test
    fun `drag drop is one owned stroke`() {
        val plan = BoundedGestureCatalog.build(
            "drag_drop",
            NormalizedPoint(0.2f, 0.2f),
            NormalizedPoint(0.8f, 0.8f),
            null
        )

        assertEquals(1, plan.strokes.size)
        assertEquals(2, plan.strokes.single().points.size)
    }

    @Test
    fun `pinch creates two simultaneous strokes`() {
        val plan = BoundedGestureCatalog.build(
            "pinch_out",
            null,
            null,
            NormalizedPoint(0.5f, 0.5f)
        )

        assertEquals(2, plan.strokes.size)
        assertTrue(plan.strokes.all { it.startTimeMs == 0L })
    }

    @Test
    fun `unbounded or oversized polyline is rejected`() {
        val badCoordinate = runCatching { NormalizedPoint(1.1f, 0.5f) }
        val tooMany = runCatching {
            BoundedGestureCatalog.parsePoints((0..8).joinToString(";") { "0.5,0.5" })
        }

        assertTrue(badCoordinate.isFailure)
        assertTrue(tooMany.isFailure)
    }
}
