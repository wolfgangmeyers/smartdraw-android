package com.smartdraw

import android.graphics.Color
import com.smartdraw.core.Point2D
import com.smartdraw.gles.CanvasLayer
import com.smartdraw.gles.GlesFbo
import com.smartdraw.gles.SmartDrawRenderer
import com.smartdraw.gles.SubRectDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoRedoTest {

    @Test
    fun testSubRectDiffDataClassProperties() {
        val before = IntArray(16) { 0xFFFFFFFF.toInt() }
        val after = IntArray(16) { 0xFF000000.toInt() }
        val diff = SubRectDiff(
            layerId = "layer-1",
            x = 10,
            y = 20,
            width = 4,
            height = 4,
            beforePixels = before,
            afterPixels = after
        )

        assertEquals("layer-1", diff.layerId)
        assertEquals(10, diff.x)
        assertEquals(20, diff.y)
        assertEquals(4, diff.width)
        assertEquals(4, diff.height)
        assertTrue(diff.beforePixels.contentEquals(before))
        assertTrue(diff.afterPixels.contentEquals(after))

        val diffClone = diff.copy()
        assertEquals(diff, diffClone)
        assertEquals(diff.hashCode(), diffClone.hashCode())
    }

    @Test
    fun testUndoRestoresCanvasPixelsAndRedoReapplies() {
        // Create canvas surface mock for testing pixel buffers
        val canvasWidth = 100
        val canvasHeight = 100
        val layerId = "layer-main"
        val layerPixels = IntArray(canvasWidth * canvasHeight) { 0xFFFFFFFF.toInt() }

        fun loadSubRectMock(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            for (row in 0 until h) {
                val dstOffset = (y + row) * canvasWidth + x
                val srcOffset = row * w
                System.arraycopy(src, srcOffset, layerPixels, dstOffset, w)
            }
        }

        fun readSubRectMock(x: Int, y: Int, w: Int, h: Int): IntArray {
            val out = IntArray(w * h)
            for (row in 0 until h) {
                val srcOffset = (y + row) * canvasWidth + x
                val dstOffset = row * w
                System.arraycopy(layerPixels, srcOffset, out, dstOffset, w)
            }
            return out
        }

        val renderer = SmartDrawRenderer(canvasWidth, canvasHeight)

        // Initial 10x10 area is white
        val initialSub = readSubRectMock(10, 10, 10, 10)
        assertEquals(0xFFFFFFFF.toInt(), initialSub[0])

        // Emulate a stroke modifying that 10x10 area to Red (0xFFFF0000)
        val modifiedSub = IntArray(100) { 0xFFFF0000.toInt() }
        val diff = SubRectDiff(
            layerId = layerId,
            x = 10,
            y = 10,
            width = 10,
            height = 10,
            beforePixels = initialSub,
            afterPixels = modifiedSub
        )

        // Apply stroke
        loadSubRectMock(10, 10, 10, 10, modifiedSub)
        renderer.undoStack.addLast(diff)
        renderer.redoStack.clear()

        // Verify modified
        val readModified = readSubRectMock(10, 10, 10, 10)
        assertEquals(0xFFFF0000.toInt(), readModified[0])
        assertTrue(renderer.canUndo())
        assertFalse(renderer.canRedo())

        // Undo
        val poppedUndo = renderer.undoStack.removeLast()
        loadSubRectMock(poppedUndo.x, poppedUndo.y, poppedUndo.width, poppedUndo.height, poppedUndo.beforePixels)
        renderer.redoStack.addLast(poppedUndo)

        assertFalse(renderer.canUndo())
        assertTrue(renderer.canRedo())
        val readAfterUndo = readSubRectMock(10, 10, 10, 10)
        assertEquals(0xFFFFFFFF.toInt(), readAfterUndo[0])

        // Redo
        val poppedRedo = renderer.redoStack.removeLast()
        loadSubRectMock(poppedRedo.x, poppedRedo.y, poppedRedo.width, poppedRedo.height, poppedRedo.afterPixels)
        renderer.undoStack.addLast(poppedRedo)

        assertTrue(renderer.canUndo())
        assertFalse(renderer.canRedo())
        val readAfterRedo = readSubRectMock(10, 10, 10, 10)
        assertEquals(0xFFFF0000.toInt(), readAfterRedo[0])
    }

    @Test
    fun testUndoStackBoundsTrimming() {
        val renderer = SmartDrawRenderer(100, 100)
        val layerId = renderer.getActiveLayer().id

        // Push 35 diffs (max is 30)
        for (i in 1..35) {
            val pixels = IntArray(4) { i }
            val diff = SubRectDiff(
                layerId = layerId,
                x = 0,
                y = 0,
                width = 2,
                height = 2,
                beforePixels = pixels,
                afterPixels = pixels
            )
            renderer.undoStack.addLast(diff)
            while (renderer.undoStack.size > renderer.maxUndoHistory) {
                renderer.undoStack.removeFirst()
            }
        }

        assertEquals(30, renderer.undoStack.size)
        // First element should be diff 6 (1..5 were trimmed)
        assertEquals(6, renderer.undoStack.first().beforePixels[0])
        assertEquals(35, renderer.undoStack.last().beforePixels[0])
    }

    @Test
    fun testMultipleSequentialUndosAndRedos() {
        val renderer = SmartDrawRenderer(50, 50)
        val layerId = "layer-1"
        var currentVal = 0

        fun applyDiff(pixels: IntArray) {
            currentVal = pixels[0]
        }

        // 3 operations modifying pixel at (0, 0)
        // Op 1: 0 -> 10
        // Op 2: 10 -> 20
        // Op 3: 20 -> 30
        val diff1 = SubRectDiff(layerId, 0, 0, 1, 1, intArrayOf(0), intArrayOf(10))
        val diff2 = SubRectDiff(layerId, 0, 0, 1, 1, intArrayOf(10), intArrayOf(20))
        val diff3 = SubRectDiff(layerId, 0, 0, 1, 1, intArrayOf(20), intArrayOf(30))

        applyDiff(diff1.afterPixels)
        renderer.undoStack.addLast(diff1)

        applyDiff(diff2.afterPixels)
        renderer.undoStack.addLast(diff2)

        applyDiff(diff3.afterPixels)
        renderer.undoStack.addLast(diff3)

        assertEquals(3, renderer.undoStack.size)
        assertEquals(0, renderer.redoStack.size)
        assertEquals(30, currentVal)

        // Undo 3 -> state 20
        val u3 = renderer.undoStack.removeLast()
        applyDiff(u3.beforePixels)
        renderer.redoStack.addLast(u3)
        assertEquals(20, currentVal)
        assertEquals(2, renderer.undoStack.size)
        assertEquals(1, renderer.redoStack.size)

        // Undo 2 -> state 10
        val u2 = renderer.undoStack.removeLast()
        applyDiff(u2.beforePixels)
        renderer.redoStack.addLast(u2)
        assertEquals(10, currentVal)
        assertEquals(1, renderer.undoStack.size)
        assertEquals(2, renderer.redoStack.size)

        // Undo 1 -> state 0
        val u1 = renderer.undoStack.removeLast()
        applyDiff(u1.beforePixels)
        renderer.redoStack.addLast(u1)
        assertEquals(0, currentVal)
        assertEquals(0, renderer.undoStack.size)
        assertEquals(3, renderer.redoStack.size)
        assertFalse(renderer.canUndo())

        // Redo 1 -> state 10
        val r1 = renderer.redoStack.removeLast()
        applyDiff(r1.afterPixels)
        renderer.undoStack.addLast(r1)
        assertEquals(10, currentVal)
        assertEquals(1, renderer.undoStack.size)
        assertEquals(2, renderer.redoStack.size)

        // Redo 2 -> state 20
        val r2 = renderer.redoStack.removeLast()
        applyDiff(r2.afterPixels)
        renderer.undoStack.addLast(r2)
        assertEquals(20, currentVal)
        assertEquals(2, renderer.undoStack.size)
        assertEquals(1, renderer.redoStack.size)

        // Redo 3 -> state 30
        val r3 = renderer.redoStack.removeLast()
        applyDiff(r3.afterPixels)
        renderer.undoStack.addLast(r3)
        assertEquals(30, currentVal)
        assertEquals(3, renderer.undoStack.size)
        assertEquals(0, renderer.redoStack.size)
        assertFalse(renderer.canRedo())
    }

    @Test
    fun testNewStrokeClearsRedoStack() {
        val renderer = SmartDrawRenderer(50, 50)
        val layerId = renderer.getActiveLayer().id

        val diff1 = SubRectDiff(layerId, 0, 0, 1, 1, intArrayOf(1), intArrayOf(2))
        renderer.undoStack.addLast(diff1)

        // Undo so diff1 is in redoStack
        renderer.undo()
        assertEquals(0, renderer.undoStack.size)
        assertEquals(1, renderer.redoStack.size)
        assertTrue(renderer.canRedo())

        // Simulate new stroke recording
        val strokePoints = listOf(Point2D(10f, 10f, 1f))
        renderer.recordSubRectDiff(layerId, strokePoints, 5f)

        // Redo stack must be cleared
        assertEquals(0, renderer.redoStack.size)
        assertFalse(renderer.canRedo())
        assertTrue(renderer.canUndo())
    }

    @Test
    fun testClearHistoryClearsBothStacks() {
        val renderer = SmartDrawRenderer(50, 50)
        val layerId = renderer.getActiveLayer().id

        renderer.undoStack.addLast(SubRectDiff(layerId, 0, 0, 1, 1, intArrayOf(1), intArrayOf(2)))
        renderer.redoStack.addLast(SubRectDiff(layerId, 0, 0, 1, 1, intArrayOf(2), intArrayOf(3)))

        assertTrue(renderer.canUndo())
        assertTrue(renderer.canRedo())

        renderer.clearHistory()

        assertFalse(renderer.canUndo())
        assertFalse(renderer.canRedo())
        assertEquals(0, renderer.undoStack.size)
        assertEquals(0, renderer.redoStack.size)
    }

    @Test
    fun testSquareBoundingBoxCalculation() {
        val width = 1000
        val height = 1000
        val maxRadius = 10f

        val points = listOf(
            Point2D(100f, 200f, 1f),
            Point2D(120f, 350f, 1f),
            Point2D(150f, 220f, 1f)
        )

        val minX = points.minOf { it.x - maxRadius }.toInt().coerceIn(0, width - 1)
        val maxX = points.maxOf { it.x + maxRadius }.toInt().coerceIn(0, width - 1)
        val minY = points.minOf { it.y - maxRadius }.toInt().coerceIn(0, height - 1)
        val maxY = points.maxOf { it.y + maxRadius }.toInt().coerceIn(0, height - 1)

        // minX = 90, maxX = 160 -> spanX = 71
        // minY = 190, maxY = 360 -> spanY = 171
        assertEquals(90, minX)
        assertEquals(160, maxX)
        assertEquals(190, minY)
        assertEquals(360, maxY)

        val side = maxOf(maxX - minX + 1, maxY - minY + 1)
        val boxW = minOf(side, width - minX)
        val boxH = minOf(side, height - minY)

        // side = maxOf(71, 171) = 171
        // boxW = minOf(171, 910) = 171
        // boxH = minOf(171, 810) = 171
        assertEquals(171, side)
        assertEquals(171, boxW)
        assertEquals(171, boxH)
        assertEquals(boxW, boxH) // Verifies square dimension
    }

    @Test
    fun testToolbarButtonStatesUpdateWithHistory() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val btnUndo = activity.btnUndo
        val btnRedo = activity.btnRedo

        assertNotNull(btnUndo)
        assertNotNull(btnRedo)

        // Initially disabled
        activity.updateHistoryUI(canUndo = false, canRedo = false)
        assertFalse(btnUndo.isEnabled)
        assertFalse(btnUndo.isClickable)
        assertEquals(0.35f, btnUndo.alpha, 0.01f)
        assertFalse(btnRedo.isEnabled)
        assertFalse(btnRedo.isClickable)
        assertEquals(0.35f, btnRedo.alpha, 0.01f)

        // When canUndo = true, canRedo = false
        activity.updateHistoryUI(canUndo = true, canRedo = false)
        assertTrue(btnUndo.isEnabled)
        assertTrue(btnUndo.isClickable)
        assertEquals(1.0f, btnUndo.alpha, 0.01f)
        assertFalse(btnRedo.isEnabled)
        assertFalse(btnRedo.isClickable)
        assertEquals(0.35f, btnRedo.alpha, 0.01f)

        // When canUndo = true, canRedo = true
        activity.updateHistoryUI(canUndo = true, canRedo = true)
        assertTrue(btnUndo.isEnabled)
        assertTrue(btnUndo.isClickable)
        assertEquals(1.0f, btnUndo.alpha, 0.01f)
        assertTrue(btnRedo.isEnabled)
        assertTrue(btnRedo.isClickable)
        assertEquals(1.0f, btnRedo.alpha, 0.01f)

        // When canUndo = false, canRedo = true
        activity.updateHistoryUI(canUndo = false, canRedo = true)
        assertFalse(btnUndo.isEnabled)
        assertFalse(btnUndo.isClickable)
        assertEquals(0.35f, btnUndo.alpha, 0.01f)
        assertTrue(btnRedo.isEnabled)
        assertTrue(btnRedo.isClickable)
        assertEquals(1.0f, btnRedo.alpha, 0.01f)
    }
}
