package com.smartdraw

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayerManagementTest {

    private lateinit var context: Context
    private lateinit var drawView: SmartDrawView

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        drawView = SmartDrawView(context)
    }

    @Test
    fun testInitialLayerState() {
        val layers = drawView.getLayers()
        assertEquals(1, layers.size)
        assertEquals("Layer 1", layers[0].name)
        assertEquals(1.0f, layers[0].opacity, 0.001f)
        assertTrue(layers[0].isVisible)
        assertEquals(layers[0].id, drawView.getActiveLayerId())
    }

    @Test
    fun testAddLayer() {
        val initialLayerId = drawView.getActiveLayerId()
        val layer2 = drawView.addLayer("Sketch")

        assertEquals("Sketch", layer2.name)
        assertEquals(2, drawView.getLayers().size)
        assertEquals(layer2.id, drawView.getActiveLayerId())

        // Added above initial layer
        val layers = drawView.getLayers()
        assertEquals(initialLayerId, layers[0].id)
        assertEquals(layer2.id, layers[1].id)
    }

    @Test
    fun testSelectLayer() {
        val l1Id = drawView.getActiveLayerId()!!
        val l2 = drawView.addLayer()
        assertEquals(l2.id, drawView.getActiveLayerId())

        assertTrue(drawView.selectLayer(l1Id))
        assertEquals(l1Id, drawView.getActiveLayerId())

        assertFalse(drawView.selectLayer("non-existent-id"))
        assertEquals(l1Id, drawView.getActiveLayerId())
    }

    @Test
    fun testDeleteLayer() {
        val l1Id = drawView.getActiveLayerId()!!
        // Cannot delete sole layer
        assertFalse(drawView.deleteLayer(l1Id))
        assertEquals(1, drawView.getLayers().size)

        val l2 = drawView.addLayer("Layer 2")
        val l3 = drawView.addLayer("Layer 3")
        assertEquals(3, drawView.getLayers().size)
        assertEquals(l3.id, drawView.getActiveLayerId())

        // Delete active layer
        assertTrue(drawView.deleteLayer(l3.id))
        assertEquals(2, drawView.getLayers().size)
        // Active layer falls back to remaining layer
        assertEquals(l2.id, drawView.getActiveLayerId())

        // Delete l2
        assertTrue(drawView.deleteLayer(l2.id))
        assertEquals(1, drawView.getLayers().size)
        assertEquals(l1Id, drawView.getActiveLayerId())

        // Cannot delete last layer
        assertFalse(drawView.deleteLayer(l1Id))
    }

    @Test
    fun testMoveLayer() {
        val l1 = drawView.getLayers()[0]
        val l2 = drawView.addLayer("Layer 2")
        val l3 = drawView.addLayer("Layer 3")

        // Order: [l1, l2, l3]
        assertEquals(listOf(l1.id, l2.id, l3.id), drawView.getLayers().map { it.id })

        // Move l1 from 0 to 2: [l2, l3, l1]
        assertTrue(drawView.moveLayer(0, 2))
        assertEquals(listOf(l2.id, l3.id, l1.id), drawView.getLayers().map { it.id })

        // Move up / move down
        assertTrue(drawView.moveLayerDown(l1.id)) // moves down from index 2 to 1
        assertEquals(listOf(l2.id, l1.id, l3.id), drawView.getLayers().map { it.id })

        assertTrue(drawView.moveLayerUp(l2.id)) // moves up from index 0 to 1
        assertEquals(listOf(l1.id, l2.id, l3.id), drawView.getLayers().map { it.id })
    }

    @Test
    fun testDuplicateLayer() {
        val l1 = drawView.getLayers()[0]
        val dup = drawView.duplicateLayer(l1.id)

        assertNotNull(dup)
        assertEquals("Layer 1 Copy", dup?.name)
        assertEquals(2, drawView.getLayers().size)
        assertEquals(dup?.id, drawView.getActiveLayerId())
    }

    @Test
    fun testMergeDown() {
        val l1 = drawView.getLayers()[0]
        // Cannot merge down bottom-most layer
        assertNull(drawView.mergeDown(l1.id))

        val l2 = drawView.addLayer("Layer 2")
        val merged = drawView.mergeDown(l2.id)

        assertNotNull(merged)
        assertEquals(l1.id, merged?.id)
        assertEquals(1, drawView.getLayers().size)
        assertEquals(l1.id, drawView.getActiveLayerId())
    }

    @Test
    fun testLayerOpacityAndVisibility() {
        val l1 = drawView.getLayers()[0]

        assertTrue(drawView.setLayerOpacity(l1.id, 0.45f))
        assertEquals(0.45f, drawView.getLayers()[0].opacity, 0.001f)

        // Clamping
        assertTrue(drawView.setLayerOpacity(l1.id, 1.5f))
        assertEquals(1.0f, drawView.getLayers()[0].opacity, 0.001f)

        assertTrue(drawView.setLayerVisibility(l1.id, false))
        assertFalse(drawView.getLayers()[0].isVisible)

        assertTrue(drawView.setLayerVisibility(l1.id, true))
        assertTrue(drawView.getLayers()[0].isVisible)
    }

    @Test
    fun testSetLayers() {
        val customLayers = listOf(
            com.smartdraw.project.LayerMetadata(id = "layer-a", name = "Background", opacity = 0.8f, isVisible = true),
            com.smartdraw.project.LayerMetadata(id = "layer-b", name = "Line Art", opacity = 1.0f, isVisible = true),
            com.smartdraw.project.LayerMetadata(id = "layer-c", name = "Highlights", opacity = 0.5f, isVisible = false)
        )
        drawView.setLayers(customLayers, "layer-b")

        val layers = drawView.getLayers()
        assertEquals(3, layers.size)
        assertEquals("Background", layers[0].name)
        assertEquals("Line Art", layers[1].name)
        assertEquals("Highlights", layers[2].name)
        assertEquals(0.8f, layers[0].opacity, 0.001f)
        assertFalse(layers[2].isVisible)
        assertEquals("layer-b", drawView.getActiveLayerId())
    }
}
