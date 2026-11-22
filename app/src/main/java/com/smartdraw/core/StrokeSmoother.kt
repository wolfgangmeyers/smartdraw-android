package com.smartdraw.core

class StrokeSmoother(
    private val onCurveGenerated: (BezierCurve) -> Unit
) {
    private val rawPoints = ArrayList<Point2D>()
    private var lastMidpoint: Point2D? = null

    fun reset() {
        rawPoints.clear()
        lastMidpoint = null
    }

    fun onPointDown(point: Point2D) {
        reset()
        rawPoints.add(point)
    }

    fun onPointMove(point: Point2D) {
        // Discard identical consecutive points
        val last = rawPoints.lastOrNull()
        if (last != null && last.distanceTo(point) < 0.5f) {
            return
        }

        rawPoints.add(point)

        if (rawPoints.size == 2 && lastMidpoint == null) {
            val p0 = rawPoints[0]
            val p1 = rawPoints[1]
            val mid = Point2D.lerp(p0, p1, 0.5f)
            // Initial segment from p0 to mid
            val quad = BezierCurve.Quadratic(p0, Point2D.lerp(p0, mid, 0.5f), mid)
            lastMidpoint = mid
            onCurveGenerated(quad)
        } else if (rawPoints.size >= 3) {
            val prevMid = lastMidpoint ?: Point2D.lerp(rawPoints[0], rawPoints[1], 0.5f)
            val pCtrl = rawPoints[rawPoints.size - 2]
            val pCurr = rawPoints.last()
            val nextMid = Point2D.lerp(pCtrl, pCurr, 0.5f)

            val quad = BezierCurve.Quadratic(prevMid, pCtrl, nextMid)
            lastMidpoint = nextMid
            onCurveGenerated(quad)
        }
    }

    fun onPointUp(point: Point2D) {
        rawPoints.add(point)
        val prevMid = lastMidpoint
        if (prevMid != null) {
            val lastRaw = rawPoints.last()
            val quad = BezierCurve.Quadratic(prevMid, lastRaw, lastRaw)
            onCurveGenerated(quad)
        } else if (rawPoints.size >= 2) {
            val p0 = rawPoints[0]
            val p1 = rawPoints.last()
            val quad = BezierCurve.Quadratic(p0, Point2D.lerp(p0, p1, 0.5f), p1)
            onCurveGenerated(quad)
        } else if (rawPoints.size == 1) {
            // Single tap point
            val p0 = rawPoints[0]
            val quad = BezierCurve.Quadratic(p0, p0, p0)
            onCurveGenerated(quad)
        }
        reset()
    }
}
