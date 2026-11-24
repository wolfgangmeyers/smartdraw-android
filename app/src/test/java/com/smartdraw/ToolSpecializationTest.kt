package com.smartdraw

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.smartdraw.core.BezierCurve
import com.smartdraw.core.Point2D
import com.smartdraw.gles.SmartDrawRenderer
import com.smartdraw.input.TouchInputHandler
import com.smartdraw.project.PaintingProject
import com.smartdraw.project.ProjectManager
import com.smartdraw.ui.BrushPreviewView
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

    @org.junit.Before
    fun setUp() {
        MainActivity.defaultIoDispatcher = kotlinx.coroutines.Dispatchers.Main
    }

    @org.junit.After
    fun tearDown() {
        MainActivity.defaultIoDispatcher = kotlinx.coroutines.Dispatchers.IO
    }

    @Test
    fun testToolModeEnumValues() {
        val modes = SmartDrawView.ToolMode.values()
        assertEquals(4, modes.size)
        assertTrue(modes.contains(SmartDrawView.ToolMode.PAINTBRUSH))
        assertTrue(modes.contains(SmartDrawView.ToolMode.PEN))
        assertTrue(modes.contains(SmartDrawView.ToolMode.ERASER))
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
    fun testPressureTransferFunctionEraser() {
        // Eraser: R = baseRadius * pressure, Opacity = baseOpacity (constant)
        val baseRadius = 20.0f
        val baseOpacity = 1.0f

        val (rLow, alphaLow) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.ERASER,
            baseRadius = baseRadius,
            baseOpacity = baseOpacity,
            pressure = 0.25f
        )
        assertEquals(baseRadius * 0.25f, rLow, 0.001f)
        assertEquals(baseOpacity, alphaLow, 0.001f)

        val (rHigh, alphaHigh) = TouchInputHandler.computeEffectiveRadiusAndOpacity(
            toolMode = SmartDrawView.ToolMode.ERASER,
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
    fun testRendererStrokeRadiiConstantForPenAndModulatesForBrush() {
        val renderer = SmartDrawRenderer(256, 256)
        val baseRadius = 16.0f

        // Curve with varying touch pressures from light (0.1) to firm (0.95)
        val p0 = Point2D(10f, 10f, 0.1f)
        val p1 = Point2D(50f, 50f, 0.5f)
        val p2 = Point2D(100f, 100f, 0.95f)
        val curve = BezierCurve.Quadratic(p0, p1, p2)

        // 1. PEN: activeStrokeRadii must be strictly constant across all sampled points
        renderer.clearActiveStroke()
        renderer.drawActiveStrokeSegment(
            curve = curve,
            radius = baseRadius,
            opacity = 1.0f,
            previewColor = Color.BLACK,
            spacingRatio = 0.2f,
            toolMode = SmartDrawView.ToolMode.PEN
        )
        assertTrue(renderer.activeStrokeRadii.isNotEmpty())
        for (r in renderer.activeStrokeRadii) {
            assertEquals("PEN activeStrokeRadii must be strictly constant", baseRadius, r, 0.001f)
        }

        // 2. PAINTBRUSH: activeStrokeRadii must modulate with touch pressure
        renderer.clearActiveStroke()
        renderer.drawActiveStrokeSegment(
            curve = curve,
            radius = baseRadius,
            opacity = 1.0f,
            previewColor = Color.BLACK,
            spacingRatio = 0.2f,
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH
        )
        assertTrue(renderer.activeStrokeRadii.isNotEmpty())
        val distinctRadii = renderer.activeStrokeRadii.toSet()
        assertTrue("PAINTBRUSH activeStrokeRadii must vary with pressure", distinctRadii.size > 1)
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

        // Test ERASER
        project.activeTool = "ERASER"
        pm.saveProject(project, dummyBmp, null, isDirty = true)
        val loadedEraser = pm.loadProject(project.id)
        assertEquals("ERASER", loadedEraser.project.activeTool)

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

        // Select Eraser
        activity.onToolSelected(SmartDrawView.ToolMode.ERASER)
        assertEquals(SmartDrawView.ToolMode.ERASER, drawView.toolMode)
        assertEquals("Active Tool: Eraser (Tap for settings)", btnActiveTool.contentDescription)

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

    @Test
    fun testPerToolRadiusAndOpacityPersistenceInSmartDrawView() {
        val context = Robolectric.buildActivity(MainActivity::class.java).get()
        val drawView = SmartDrawView(context)

        // Initial default tool is PAINTBRUSH
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, drawView.toolMode)
        drawView.setBrushSize(10.0f)
        drawView.brushOpacity = 0.8f
        assertEquals(10.0f, drawView.getBrushSize(), 0.001f)
        assertEquals(0.8f, drawView.brushOpacity, 0.001f)
        assertEquals(10.0f, drawView.getToolRadius(SmartDrawView.ToolMode.PAINTBRUSH), 0.001f)

        // Switch to PEN
        drawView.toolMode = SmartDrawView.ToolMode.PEN
        assertEquals(SmartDrawView.ToolMode.PEN, drawView.toolMode)
        // Set pen size to 0.5 px, opacity 1.0
        drawView.setBrushSize(0.5f)
        drawView.brushOpacity = 1.0f
        assertEquals(0.5f, drawView.getBrushSize(), 0.001f)
        assertEquals(1.0f, drawView.brushOpacity, 0.001f)
        assertEquals(0.5f, drawView.getToolRadius(SmartDrawView.ToolMode.PEN), 0.001f)

        // Switch to ERASER
        drawView.toolMode = SmartDrawView.ToolMode.ERASER
        assertEquals(SmartDrawView.ToolMode.ERASER, drawView.toolMode)
        drawView.setBrushSize(25.0f)
        drawView.brushOpacity = 0.9f
        assertEquals(25.0f, drawView.getBrushSize(), 0.001f)
        assertEquals(0.9f, drawView.brushOpacity, 0.001f)
        assertEquals(25.0f, drawView.getToolRadius(SmartDrawView.ToolMode.ERASER), 0.001f)

        // Switch to SMUDGE
        drawView.toolMode = SmartDrawView.ToolMode.SMUDGE
        assertEquals(SmartDrawView.ToolMode.SMUDGE, drawView.toolMode)
        drawView.setBrushSize(35.0f)
        drawView.brushOpacity = 0.4f
        assertEquals(35.0f, drawView.getBrushSize(), 0.001f)
        assertEquals(0.4f, drawView.brushOpacity, 0.001f)
        assertEquals(35.0f, drawView.getToolRadius(SmartDrawView.ToolMode.SMUDGE), 0.001f)

        // Switch back to PAINTBRUSH: must restore 10.0px and 0.8 opacity
        drawView.toolMode = SmartDrawView.ToolMode.PAINTBRUSH
        assertEquals(10.0f, drawView.getBrushSize(), 0.001f)
        assertEquals(0.8f, drawView.brushOpacity, 0.001f)

        // Switch back to PEN: must restore 0.5px and 1.0 opacity
        drawView.toolMode = SmartDrawView.ToolMode.PEN
        assertEquals(0.5f, drawView.getBrushSize(), 0.001f)
        assertEquals(1.0f, drawView.brushOpacity, 0.001f)

        // Switch back to ERASER: must restore 25.0px and 0.9 opacity
        drawView.toolMode = SmartDrawView.ToolMode.ERASER
        assertEquals(25.0f, drawView.getBrushSize(), 0.001f)
        assertEquals(0.9f, drawView.brushOpacity, 0.001f)

        // Switch back to SMUDGE: must restore 35.0px and 0.4 opacity
        drawView.toolMode = SmartDrawView.ToolMode.SMUDGE
        assertEquals(35.0f, drawView.getBrushSize(), 0.001f)
        assertEquals(0.4f, drawView.brushOpacity, 0.001f)
    }

    @Test
    fun testToolSettingsBottomSheetSyncsSlidersOnToolSwitch() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            brushRadius = 10.0f,
            brushOpacity = 0.8f
        )

        val toolRadii = mutableMapOf(
            SmartDrawView.ToolMode.PAINTBRUSH to 10.0f,
            SmartDrawView.ToolMode.PEN to 0.5f,
            SmartDrawView.ToolMode.ERASER to 25.0f,
            SmartDrawView.ToolMode.SMUDGE to 35.0f
        )
        val toolOpacities = mutableMapOf(
            SmartDrawView.ToolMode.PAINTBRUSH to 0.8f,
            SmartDrawView.ToolMode.PEN to 1.0f,
            SmartDrawView.ToolMode.ERASER to 0.9f,
            SmartDrawView.ToolMode.SMUDGE to 0.4f
        )

        var selectedTool: SmartDrawView.ToolMode? = null

        sheet.listener = object : ToolSettingsBottomSheet.Listener {
            override fun onToolSelected(toolMode: SmartDrawView.ToolMode) {
                selectedTool = toolMode
            }

            override fun onBrushSizeChanged(radius: Float) {
                selectedTool?.let { toolRadii[it] = radius }
            }

            override fun onBrushOpacityChanged(opacity: Float) {
                selectedTool?.let { toolOpacities[it] = opacity }
            }

            override fun getToolRadius(toolMode: SmartDrawView.ToolMode): Float {
                return toolRadii[toolMode] ?: 20.0f
            }

            override fun getToolOpacity(toolMode: SmartDrawView.ToolMode): Float {
                return toolOpacities[toolMode] ?: 1.0f
            }
        }

        sheet.show(activity.supportFragmentManager, "test_sync")
        activity.supportFragmentManager.executePendingTransactions()

        val view = sheet.requireView()
        val cardPen: LinearLayout = view.findViewById(R.id.cardToolPen)
        val cardBrush: LinearLayout = view.findViewById(R.id.cardToolBrush)
        val cardEraser: LinearLayout = view.findViewById(R.id.cardToolEraser)
        val cardSmudge: LinearLayout = view.findViewById(R.id.cardToolSmudge)
        val textSizeValue: TextView = view.findViewById(R.id.textSizeValue)
        val textOpacityValue: TextView = view.findViewById(R.id.textBrushOpacityValue)
        val brushPreview: BrushPreviewView = view.findViewById(R.id.brushPreview)

        // Initially Paintbrush at 10px, 80%
        assertEquals("10 px", textSizeValue.text)
        assertEquals("80%", textOpacityValue.text)
        assertEquals(10.0f, brushPreview.brushRadius, 0.001f)

        // Switch to Pen: should immediately sync sliders to 0.5px, 100%
        cardPen.performClick()
        assertEquals(SmartDrawView.ToolMode.PEN, selectedTool)
        assertEquals("0.5 px", textSizeValue.text)
        assertEquals("100%", textOpacityValue.text)
        assertEquals(0.5f, brushPreview.brushRadius, 0.001f)

        // Switch to Eraser: should immediately sync sliders to 25px, 90%
        cardEraser.performClick()
        assertEquals(SmartDrawView.ToolMode.ERASER, selectedTool)
        assertEquals("25 px", textSizeValue.text)
        assertEquals("90%", textOpacityValue.text)
        assertEquals(25.0f, brushPreview.brushRadius, 0.001f)

        // Switch to Smudge: should immediately sync sliders to 35px, 40%
        cardSmudge.performClick()
        assertEquals(SmartDrawView.ToolMode.SMUDGE, selectedTool)
        assertEquals("35 px", textSizeValue.text)
        assertEquals("40%", textOpacityValue.text)
        assertEquals(35.0f, brushPreview.brushRadius, 0.001f)

        // Switch back to Paintbrush: should immediately sync back to 10px, 80%
        cardBrush.performClick()
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, selectedTool)
        assertEquals("10 px", textSizeValue.text)
        assertEquals("80%", textOpacityValue.text)
        assertEquals(10.0f, brushPreview.brushRadius, 0.001f)
    }

    @Test
    fun testPerToolPersistenceInProjectManager() {
        val context = Robolectric.buildActivity(MainActivity::class.java).get()
        val pm = ProjectManager(context)
        val dummyBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

        val project = pm.createNewProject(1024, 1024, "Per-Tool Persistence Test")
        project.activeTool = "PEN"
        project.brushSize = 0.5f
        project.brushSizeBrush = 12.0f
        project.brushSizePen = 0.5f
        project.brushSizeEraser = 22.0f
        project.brushSizeSmudge = 45.0f
        project.brushOpacityBrush = 0.75f
        project.brushOpacityPen = 1.0f
        project.brushOpacityEraser = 0.85f
        project.brushOpacitySmudge = 0.35f

        pm.saveProject(project, dummyBmp, null, isDirty = true)

        val loaded = pm.loadProject(project.id).project
        assertNotNull(loaded)
        assertEquals("PEN", loaded.activeTool)
        assertEquals(0.5f, loaded.brushSize, 0.001f)
        assertEquals(12.0f, loaded.brushSizeBrush, 0.001f)
        assertEquals(0.5f, loaded.brushSizePen, 0.001f)
        assertEquals(22.0f, loaded.brushSizeEraser, 0.001f)
        assertEquals(45.0f, loaded.brushSizeSmudge, 0.001f)
        assertEquals(0.75f, loaded.brushOpacityBrush, 0.001f)
        assertEquals(1.0f, loaded.brushOpacityPen, 0.001f)
        assertEquals(0.85f, loaded.brushOpacityEraser, 0.001f)
        assertEquals(0.35f, loaded.brushOpacitySmudge, 0.001f)
    }
}
