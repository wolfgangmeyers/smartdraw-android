package com.smartdraw

import android.graphics.Bitmap
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.smartdraw.input.TouchInputHandler
import com.smartdraw.project.PaintingProject
import com.smartdraw.project.ProjectManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolSpecializationTest {

    @Test
    fun testToolModeEnumValues() {
        val modes = SmartDrawView.ToolMode.values()
        assertEquals(4, modes.size)
        assertTrue(modes.contains(SmartDrawView.ToolMode.PAINTBRUSH))
        assertTrue(modes.contains(SmartDrawView.ToolMode.PEN))
        assertTrue(modes.contains(SmartDrawView.ToolMode.PENCIL))
        assertTrue(modes.contains(SmartDrawView.ToolMode.SMUDGE))
    }

    @Test
    fun testPressureTransferFunctionPaintbrush() {
        // Paintbrush: R = baseRadius * pressure, Opacity = baseOpacity (constant)
        val baseRadius = 24.0f
        val baseOpacity = 0.8f

        // Test low pressure
        val (rLow, alphaLow) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            baseRadius = baseRadius,
            baseOpacity = baseOpacity,
            pressure = 0.2f
        )
        assertEquals(baseRadius * 0.2f, rLow, 0.001f)
        assertEquals(baseOpacity, alphaLow, 0.001f)

        // Test medium pressure
        val (rMed, alphaMed) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            baseRadius = baseRadius,
            baseOpacity = baseOpacity,
            pressure = 0.6f
        )
        assertEquals(baseRadius * 0.6f, rMed, 0.001f)
        assertEquals(baseOpacity, alphaMed, 0.001f)

        // Test max pressure
        val (rHigh, alphaHigh) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            baseRadius = baseRadius,
            baseOpacity = baseOpacity,
            pressure = 1.0f
        )
        assertEquals(baseRadius * 1.0f, rHigh, 0.001f)
        assertEquals(baseOpacity, alphaHigh, 0.001f)
    }

    @Test
    fun testPressureTransferFunctionPen() {
        // Pen: Zero pressure sensitivity. Radius = baseRadius, Opacity = baseOpacity (both constant)
        val baseRadius = 18.0f
        val baseOpacity = 0.95f

        val pressures = floatArrayOf(0.1f, 0.35f, 0.7f, 1.0f)
        for (p in pressures) {
            val (r, alpha) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
                toolMode = SmartDrawView.ToolMode.PEN,
                baseRadius = baseRadius,
                baseOpacity = baseOpacity,
                pressure = p
            )
            assertEquals("Pen radius must remain constant regardless of pressure", baseRadius, r, 0.001f)
            assertEquals("Pen opacity must remain constant regardless of pressure", baseOpacity, alpha, 0.001f)
        }
    }

    @Test
    fun testPressureTransferFunctionPencil() {
        // Pencil: Opacity modulates with pressure: alpha = baseOpacity * pressure. Radius = baseRadius (constant)
        val baseRadius = 12.0f
        val baseOpacity = 0.9f

        // Test low pressure (light touch = faint graphite)
        val (rLow, alphaLow) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.PENCIL,
            baseRadius = baseRadius,
            baseOpacity = baseOpacity,
            pressure = 0.25f
        )
        assertEquals(baseRadius, rLow, 0.001f)
        assertEquals(baseOpacity * 0.25f, alphaLow, 0.001f)

        // Test heavy pressure (firm press = dark/dense)
        val (rHigh, alphaHigh) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.PENCIL,
            baseRadius = baseRadius,
            baseOpacity = baseOpacity,
            pressure = 0.85f
        )
        assertEquals(baseRadius, rHigh, 0.001f)
        assertEquals(baseOpacity * 0.85f, alphaHigh, 0.001f)
    }

    @Test
    fun testPressureTransferFunctionSmudge() {
        // Smudge: Modulates both radius and opacity
        val baseRadius = 30.0f
        val baseOpacity = 0.5f

        val (r, alpha) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.SMUDGE,
            baseRadius = baseRadius,
            baseOpacity = baseOpacity,
            pressure = 0.5f
        )
        assertEquals(baseRadius * 0.5f, r, 0.001f)
        assertEquals(baseOpacity * 0.5f, alpha, 0.001f)
    }

    @Test
    fun testProjectPersistenceActiveToolSerialization() {
        val context = Robolectric.buildActivity(MainActivity::class.java).get()
        val pm = ProjectManager(context)
        val dummyBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val project = pm.createNewProject(1024, 1024, "Tool Test")
        assertEquals("PAINTBRUSH", project.activeTool)

        // Test serialization and saving with PEN
        project.activeTool = "PEN"
        pm.saveProject(project, dummyBmp, null, isDirty = true)

        val loadedPen = pm.loadProject(project.id)
        assertNotNull(loadedPen)
        assertEquals("PEN", loadedPen.project.activeTool)

        // Test PENCIL
        project.activeTool = "PENCIL"
        pm.saveProject(project, dummyBmp, null, isDirty = true)
        val loadedPencil = pm.loadProject(project.id)
        assertEquals("PENCIL", loadedPencil.project.activeTool)

        // Test SMUDGE
        project.activeTool = "SMUDGE"
        pm.saveProject(project, dummyBmp, null, isDirty = true)
        val loadedSmudge = pm.loadProject(project.id)
        assertEquals("SMUDGE", loadedSmudge.project.activeTool)

        // Test Backwards Compatibility with legacy "PENCIL" vs "PAINTBRUSH"
        project.activeTool = "PAINTBRUSH"
        pm.saveProject(project, dummyBmp, null, isDirty = true)
        val loadedBrush = pm.loadProject(project.id)
        assertEquals("PAINTBRUSH", loadedBrush.project.activeTool)
    }

    @Test
    fun testMainActivityToolbarIconUpdatesOnToolSelection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        activity.ioDispatcher = kotlinx.coroutines.Dispatchers.Main
        controller.start().resume().visible()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val btnActiveTool: ImageButton = activity.findViewById(R.id.btnActiveTool)
        val drawView: SmartDrawView = activity.findViewById(R.id.smartDrawView)

        // Initial default is Paintbrush
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, drawView.toolMode)
        assertEquals("Active Tool: Paintbrush (Tap for settings)", btnActiveTool.contentDescription)

        // Select Pen
        activity.onToolSelected(SmartDrawView.ToolMode.PEN)
        assertEquals(SmartDrawView.ToolMode.PEN, drawView.toolMode)
        assertEquals("Active Tool: Pen (Tap for settings)", btnActiveTool.contentDescription)

        // Select Pencil
        activity.onToolSelected(SmartDrawView.ToolMode.PENCIL)
        assertEquals(SmartDrawView.ToolMode.PENCIL, drawView.toolMode)
        assertEquals("Active Tool: Pencil (Tap for settings)", btnActiveTool.contentDescription)

        // Select Smudge
        activity.onToolSelected(SmartDrawView.ToolMode.SMUDGE)
        assertEquals(SmartDrawView.ToolMode.SMUDGE, drawView.toolMode)
        assertEquals("Active Tool: Smudge (Tap for settings)", btnActiveTool.contentDescription)

        // Select Paintbrush
        activity.onToolSelected(SmartDrawView.ToolMode.PAINTBRUSH)
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, drawView.toolMode)
        assertEquals("Active Tool: Paintbrush (Tap for settings)", btnActiveTool.contentDescription)
    }

    @Test
    fun testLayerDeleteConfirmationDialog() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        activity.ioDispatcher = kotlinx.coroutines.Dispatchers.Main
        controller.start().resume().visible()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val drawView: SmartDrawView = activity.findViewById(R.id.smartDrawView)

        // Add a second layer so layer count is 2
        drawView.addLayer("Layer 2")
        assertEquals(2, drawView.getLayers().size)

        val sheet = LayerManagerBottomSheet.newInstance()
        sheet.show(activity.supportFragmentManager, "test_layers")
        activity.supportFragmentManager.executePendingTransactions()

        val btnDelete: ImageButton = sheet.requireView().findViewById(R.id.btnDeleteLayer)
        assertTrue("Delete button should be enabled when > 1 layers exist", btnDelete.isEnabled)

        // Tap delete button -> triggers confirmation dialog
        btnDelete.performClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val dialog = ShadowAlertDialog.getLatestDialog() as? AlertDialog
        assertNotNull("Confirmation dialog should be displayed", dialog)

        val messageView = dialog!!.findViewById<TextView>(android.R.id.message)
        assertEquals("Are you sure you want to delete this layer? This action cannot be undone.", messageView?.text?.toString())

        // Click Cancel -> layer count remains 2
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(2, drawView.getLayers().size)

        // Tap delete again and click Positive ("Delete") -> layer count becomes 1
        btnDelete.performClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val confirmDialog = ShadowAlertDialog.getLatestDialog() as? AlertDialog
        assertNotNull(confirmDialog)
        confirmDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(1, drawView.getLayers().size)
    }
}
