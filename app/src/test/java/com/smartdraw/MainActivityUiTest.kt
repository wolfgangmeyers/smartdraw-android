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

        val btnActiveTool: ImageButton = activity.findViewById(R.id.btnActiveTool)
        val btnAutoColor: ImageButton = activity.findViewById(R.id.btnAutoColor)
        val btnResetView: ImageButton = activity.findViewById(R.id.btnResetView)
        val btnMoreOptions: ImageButton = activity.findViewById(R.id.btnMoreOptions)
        val drawView: SmartDrawView = activity.findViewById(R.id.smartDrawView)

        assertNotNull(btnActiveTool)
        assertNotNull(btnAutoColor)
        assertNotNull(btnResetView)
        assertNotNull(btnMoreOptions)
        assertNotNull(drawView)

        // Default tool is Pencil
        assertEquals(SmartDrawView.ToolMode.PENCIL, drawView.toolMode)
        assertTrue(drawView.useReferenceColors)

        // Tool settings callbacks
        activity.onToolSelected(SmartDrawView.ToolMode.SMUDGE)
        assertEquals(SmartDrawView.ToolMode.SMUDGE, drawView.toolMode)

        activity.onToolSelected(SmartDrawView.ToolMode.PENCIL)
        assertEquals(SmartDrawView.ToolMode.PENCIL, drawView.toolMode)

        // Brush size and opacity changes
        activity.onBrushSizeChanged(32.0f)
        assertEquals(32.0f, drawView.getBrushSize(), 0.001f)

        activity.onBrushOpacityChanged(0.75f)
        assertEquals(0.75f, drawView.brushOpacity, 0.001f)

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

    @Test
    fun testToolSettingsBottomSheetLifecycle() {
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = SmartDrawView.ToolMode.PENCIL,
            brushRadius = 24.0f,
            brushOpacity = 0.8f
        )
        assertNotNull(sheet)
        assertEquals(SmartDrawView.ToolMode.PENCIL, sheet.currentTool)
        assertEquals(24.0f, sheet.currentBrushRadius, 0.001f)
        assertEquals(0.8f, sheet.currentBrushOpacity, 0.001f)
    }
}
