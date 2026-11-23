package com.smartdraw.gles

/**
 * Minimal bounding square sub-rectangle pixel diff representing an atomic
 * canvas drawing modification (stroke or smudge) on a specific canvas layer.
 *
 * Coordinates (x, y) and dimensions (width, height) define the bounding box in canvas pixel space
 * with top-left origin (0, 0).
 *
 * [beforePixels] and [afterPixels] contain packed ARGB_8888 row-major pixels of size width * height.
 */
data class SubRectDiff(
    val layerId: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val beforePixels: IntArray,
    val afterPixels: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SubRectDiff

        if (layerId != other.layerId) return false
        if (x != other.x) return false
        if (y != other.y) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (!beforePixels.contentEquals(other.beforePixels)) return false
        if (!afterPixels.contentEquals(other.afterPixels)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = layerId.hashCode()
        result = 31 * result + x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + beforePixels.contentHashCode()
        result = 31 * result + afterPixels.contentHashCode()
        return result
    }
}
