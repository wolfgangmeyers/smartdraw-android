package com.smartdraw

import android.widget.ImageButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityUiTest {

    @Test
    fun testToolbarButtonsExistAndAreImageButtons() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val btnLoadImage: ImageButton = activity.findViewById(R.id.btnLoadImage)
        val btnPencil: ImageButton = activity.findViewById(R.id.btnPencil)
        val btnSmudge: ImageButton = activity.findViewById(R.id.btnSmudge)
        val btnAutoColor: ImageButton = activity.findViewById(R.id.btnAutoColor)
        val btnResetView: ImageButton = activity.findViewById(R.id.btnResetView)
        val btnMoreOptions: ImageButton = activity.findViewById(R.id.btnMoreOptions)
        val drawView: SmartDrawView = activity.findViewById(R.id.smartDrawView)

        assertNotNull(btnLoadImage)
        assertNotNull(btnPencil)
        assertNotNull(btnSmudge)
        assertNotNull(btnAutoColor)
        assertNotNull(btnResetView)
        assertNotNull(btnMoreOptions)
        assertNotNull(drawView)

        // Default tool is Pencil
        assertEquals(SmartDrawView.ToolMode.PENCIL, drawView.toolMode)
        assertTrue(drawView.useReferenceColors)

        // Switch to smudge
        btnSmudge.performClick()
        assertEquals(SmartDrawView.ToolMode.SMUDGE, drawView.toolMode)

        // Switch back to pencil
        btnPencil.performClick()
        assertEquals(SmartDrawView.ToolMode.PENCIL, drawView.toolMode)

        // Toggle auto color
        btnAutoColor.performClick()
        assertEquals(false, drawView.useReferenceColors)
        btnAutoColor.performClick()
        assertEquals(true, drawView.useReferenceColors)
    }

    @Test
    fun testMoreOptionsModalListeners() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val drawView: SmartDrawView = activity.findViewById(R.id.smartDrawView)

        // Test opacity callback
        activity.onOpacityChanged(45)
        // Test clear painting
        activity.onClearPaintingClicked()
        // Test reset view
        activity.onResetCanvasViewClicked()
        assertEquals(1.0f, drawView.viewportScale, 0.001f)
        assertEquals(0f, drawView.viewportRotationDegrees, 0.001f)
    }
}
