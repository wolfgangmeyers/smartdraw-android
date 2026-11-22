package com.smartdraw.core

import kotlin.math.hypot

data class Point2D(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val color: Int? = null
) {
    fun distanceTo(other: Point2D): Float = hypot(x - other.x, y - other.y)

    operator fun plus(other: Point2D): Point2D =
        Point2D(x + other.x, y + other.y, (pressure + other.pressure) * 0.5f)

    operator fun minus(other: Point2D): Point2D =
        Point2D(x - other.x, y - other.y)

    operator fun times(scalar: Float): Point2D =
        Point2D(x * scalar, y * scalar, pressure)

    companion object {
        fun lerp(a: Point2D, b: Point2D, t: Float): Point2D {
            val clampedT = t.coerceIn(0.0f, 1.0f)
            return Point2D(
                x = a.x + (b.x - a.x) * clampedT,
                y = a.y + (b.y - a.y) * clampedT,
                pressure = a.pressure + (b.pressure - a.pressure) * clampedT,
                color = if (clampedT < 0.5f) a.color else b.color
            )
        }
    }
}
