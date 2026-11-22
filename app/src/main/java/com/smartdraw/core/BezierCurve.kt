package com.smartdraw.core

import kotlin.math.max

sealed class BezierCurve {
    abstract val start: Point2D
    abstract val end: Point2D
    abstract fun evaluate(t: Float): Point2D

    private val samples = 128
    private val cumulativeLengths = FloatArray(samples + 1)
    var totalLength: Float = 0f
        protected set

    init {
        // We will initialize in subclass or after subclass fields are set
    }

    protected fun initArcLength(): Float {
        cumulativeLengths[0] = 0f
        var prev = evaluate(0f)
        var acc = 0f
        for (i in 1..samples) {
            val t = i.toFloat() / samples
            val curr = evaluate(t)
            acc += prev.distanceTo(curr)
            cumulativeLengths[i] = acc
            prev = curr
        }
        return acc
    }

    fun getTForDistance(distance: Float): Float {
        if (totalLength <= 0.0001f) return 0f
        val clampedDist = distance.coerceIn(0f, totalLength)
        if (clampedDist <= 0f) return 0f
        if (clampedDist >= totalLength) return 1f

        // Binary search for cumulative lengths interval
        var low = 0
        var high = samples
        while (low < high) {
            val mid = (low + high) / 2
            if (cumulativeLengths[mid] < clampedDist) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        val idx = if (low > 0) low - 1 else 0
        val s0 = cumulativeLengths[idx]
        val s1 = cumulativeLengths[idx + 1]
        val t0 = idx.toFloat() / samples
        val t1 = (idx + 1).toFloat() / samples

        val segLen = s1 - s0
        val frac = if (segLen > 1e-6f) (clampedDist - s0) / segLen else 0f
        return (t0 + frac * (t1 - t0)).coerceIn(0f, 1f)
    }

    fun evaluateAtDistance(distance: Float): Point2D {
        val t = getTForDistance(distance)
        return evaluate(t)
    }

    fun sampleUniform(stepDistance: Float): List<Point2D> {
        val length = totalLength
        val step = max(0.5f, stepDistance)
        if (length <= 0.001f) {
            return listOf(start)
        }

        val numSteps = (length / step).toInt()
        val result = ArrayList<Point2D>(numSteps + 2)
        var currentDist = 0f
        while (currentDist < length) {
            result.add(evaluateAtDistance(currentDist))
            currentDist += step
        }
        // Always include end point
        result.add(evaluate(1.0f))
        return result
    }

    data class Quadratic(
        val p0: Point2D,
        val p1: Point2D,
        val p2: Point2D
    ) : BezierCurve() {
        override val start: Point2D = p0
        override val end: Point2D = p2

        init {
            @Suppress("LeakingThis")
            totalLength = initArcLength()
        }

        override fun evaluate(t: Float): Point2D {
            val clamped = t.coerceIn(0f, 1f)
            val u = 1f - clamped
            val tt = clamped * clamped
            val uu = u * u

            val x = uu * p0.x + 2f * u * clamped * p1.x + tt * p2.x
            val y = uu * p0.y + 2f * u * clamped * p1.y + tt * p2.y
            val pressure = uu * p0.pressure + 2f * u * clamped * p1.pressure + tt * p2.pressure

            return Point2D(x, y, pressure)
        }
    }

    data class Cubic(
        val p0: Point2D,
        val p1: Point2D,
        val p2: Point2D,
        val p3: Point2D
    ) : BezierCurve() {
        override val start: Point2D = p0
        override val end: Point2D = p3

        init {
            @Suppress("LeakingThis")
            totalLength = initArcLength()
        }

        override fun evaluate(t: Float): Point2D {
            val clamped = t.coerceIn(0f, 1f)
            val u = 1f - clamped
            val tt = clamped * clamped
            val uu = u * u
            val uuu = uu * u
            val ttt = tt * clamped

            val x = uuu * p0.x + 3f * uu * clamped * p1.x + 3f * u * tt * p2.x + ttt * p3.x
            val y = uuu * p0.y + 3f * uu * clamped * p1.y + 3f * u * tt * p2.y + ttt * p3.y
            val pressure = uuu * p0.pressure + 3f * uu * clamped * p1.pressure + 3f * u * tt * p2.pressure + ttt * p3.pressure

            return Point2D(x, y, pressure)
        }
    }
}
