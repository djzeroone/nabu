package com.mewmix.nabu.accessibility

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {
            "Gesture points must be finite normalized coordinates."
        }
    }
}

data class GestureStrokePlan(
    val points: List<NormalizedPoint>,
    val startTimeMs: Long,
    val durationMs: Long
) {
    init {
        require(points.isNotEmpty() && points.size <= BoundedGestureCatalog.MAX_POINTS_PER_STROKE)
        require(startTimeMs >= 0L)
        require(durationMs in BoundedGestureCatalog.MIN_DURATION_MS..BoundedGestureCatalog.MAX_DURATION_MS)
    }
}

data class BoundedGesturePlan(val token: String, val strokes: List<GestureStrokePlan>) {
    init {
        require(strokes.isNotEmpty() && strokes.size <= BoundedGestureCatalog.MAX_DEFAULT_STROKES)
        require(strokes.maxOf { it.startTimeMs + it.durationMs } <= BoundedGestureCatalog.MAX_TOTAL_DURATION_MS)
    }
}

object BoundedGestureCatalog {
    const val MAX_POINTS_PER_STROKE = 8
    const val MAX_DEFAULT_STROKES = 2
    const val MIN_DURATION_MS = 40L
    const val MAX_DURATION_MS = 5_000L
    const val MAX_TOTAL_DURATION_MS = 6_000L

    val plannerTokens = setOf(
        "tap_point",
        "double_tap",
        "long_press_point",
        "press_and_hold",
        "swipe_path",
        "drag_drop",
        "polyline_drag",
        "pinch_in",
        "pinch_out",
        "two_finger_swipe"
    )

    fun build(
        token: String,
        start: NormalizedPoint?,
        end: NormalizedPoint?,
        center: NormalizedPoint?,
        points: List<NormalizedPoint> = emptyList(),
        durationMs: Long = defaultDuration(token)
    ): BoundedGesturePlan {
        require(token in plannerTokens) { "Unknown bounded gesture '$token'." }
        return when (token) {
            "tap_point" -> single(token, listOf(requireNotNull(start)), 80L)
            "double_tap" -> {
                val point = requireNotNull(start)
                BoundedGesturePlan(
                    token,
                    listOf(
                        GestureStrokePlan(listOf(point), 0, 70),
                        GestureStrokePlan(listOf(point), 160, 70)
                    )
                )
            }
            "long_press_point" -> single(token, listOf(requireNotNull(start)), 650L)
            "press_and_hold" -> single(token, listOf(requireNotNull(start)), durationMs.coerceIn(200, MAX_DURATION_MS))
            "swipe_path", "drag_drop" -> single(
                token,
                listOf(requireNotNull(start), requireNotNull(end)),
                durationMs
            )
            "polyline_drag" -> {
                require(points.size in 2..MAX_POINTS_PER_STROKE) { "Polyline requires 2..$MAX_POINTS_PER_STROKE points." }
                single(token, points, durationMs)
            }
            "pinch_in", "pinch_out" -> pinch(token, requireNotNull(center), durationMs)
            "two_finger_swipe" -> {
                val from = requireNotNull(start)
                val to = requireNotNull(end)
                val offset = 0.08f
                BoundedGesturePlan(
                    token,
                    listOf(
                        GestureStrokePlan(listOf(from.offset(-offset, 0f), to.offset(-offset, 0f)), 0, durationMs),
                        GestureStrokePlan(listOf(from.offset(offset, 0f), to.offset(offset, 0f)), 0, durationMs)
                    )
                )
            }
            else -> error("Unreachable gesture token")
        }
    }

    fun parsePoints(raw: String): List<NormalizedPoint> {
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(';')
        require(parts.size <= MAX_POINTS_PER_STROKE) { "Too many gesture points." }
        return parts.map { pair ->
            val coordinates = pair.split(',')
            require(coordinates.size == 2) { "Each gesture point must be x,y." }
            NormalizedPoint(
                coordinates[0].trim().toFloatOrNull() ?: error("Invalid gesture x coordinate."),
                coordinates[1].trim().toFloatOrNull() ?: error("Invalid gesture y coordinate.")
            )
        }
    }

    private fun single(token: String, points: List<NormalizedPoint>, durationMs: Long) =
        BoundedGesturePlan(token, listOf(GestureStrokePlan(points, 0, durationMs)))

    private fun pinch(token: String, center: NormalizedPoint, durationMs: Long): BoundedGesturePlan {
        val inner = 0.05f
        val outer = 0.22f
        val fromDistance = if (token == "pinch_in") outer else inner
        val toDistance = if (token == "pinch_in") inner else outer
        return BoundedGesturePlan(
            token,
            listOf(
                GestureStrokePlan(
                    listOf(center.offset(-fromDistance, 0f), center.offset(-toDistance, 0f)),
                    0,
                    durationMs
                ),
                GestureStrokePlan(
                    listOf(center.offset(fromDistance, 0f), center.offset(toDistance, 0f)),
                    0,
                    durationMs
                )
            )
        )
    }

    private fun NormalizedPoint.offset(dx: Float, dy: Float): NormalizedPoint =
        NormalizedPoint((x + dx).coerceIn(0f, 1f), (y + dy).coerceIn(0f, 1f))

    private fun defaultDuration(token: String): Long = when (token) {
        "drag_drop", "polyline_drag" -> 700L
        "pinch_in", "pinch_out", "two_finger_swipe" -> 350L
        else -> 250L
    }
}
