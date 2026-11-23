package com.smartdraw

import android.widget.ImageButton
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
class MainActivityUiTest {

    @org.junit.Before
    fun setUp() {
        MainActivity.defaultIoDispatcher = kotlinx.coroutines.Dispatchers.Main
    }

    @org.junit.After
    fun tearDown() {
        MainActivity.defaultIoDispatcher = kotlinx.coroutines.Dispatchers.IO
    }


    @Test
    fun testToolbarButtonsExistAndAreImageButtons() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val btnActiveTool: ImageButton = activity.findViewById(R.id.btnActiveTool)
        val btnColorSettings: ImageButton = activity.findViewById(R.id.btnColorSettings)
        val btnEyedropper: ImageButton = activity.findViewById(R.id.btnEyedropper)
        val btnResetView: ImageButton = activity.findViewById(R.id.btnResetView)
        val btnLayers: ImageButton = activity.findViewById(R.id.btnLayers)
        val btnGallery: ImageButton = activity.findViewById(R.id.btnGallery)
        val btnMoreOptions: ImageButton = activity.findViewById(R.id.btnMoreOptions)
        val drawView: SmartDrawView = activity.findViewById(R.id.smartDrawView)

        assertNotNull(btnActiveTool)
        assertNotNull(btnColorSettings)
        assertNotNull(btnEyedropper)
        assertNotNull(btnResetView)
        assertNotNull(btnLayers)
        assertNotNull(btnGallery)
        assertNotNull(btnMoreOptions)
        assertNotNull(drawView)

        // Default tool is Paintbrush
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, drawView.toolMode)
        assertTrue(drawView.useReferenceColors)

        // Tool settings callbacks
        activity.onToolSelected(SmartDrawView.ToolMode.PAINTBRUSH)
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, drawView.toolMode)

        activity.onToolSelected(SmartDrawView.ToolMode.PEN)
        assertEquals(SmartDrawView.ToolMode.PEN, drawView.toolMode)

        activity.onToolSelected(SmartDrawView.ToolMode.PENCIL)
        assertEquals(SmartDrawView.ToolMode.PENCIL, drawView.toolMode)

        activity.onToolSelected(SmartDrawView.ToolMode.SMUDGE)
        assertEquals(SmartDrawView.ToolMode.SMUDGE, drawView.toolMode)

        // Brush size and opacity changes
        activity.onBrushSizeChanged(32.0f)
        assertEquals(32.0f, drawView.getBrushSize(), 0.001f)

        activity.onBrushOpacityChanged(0.75f)
        assertEquals(0.75f, drawView.brushOpacity, 0.001f)

        // Toggle auto color callback
        activity.onAutoColorToggled(false)
        assertEquals(false, drawView.useReferenceColors)
        activity.onAutoColorToggled(true)
        assertEquals(true, drawView.useReferenceColors)

        // Eyedropper toggle via toolbar
        btnEyedropper.performClick()
        assertTrue(drawView.isEyedropperActive)
        btnEyedropper.performClick()
        assertFalse(drawView.isEyedropperActive)
    }

    @Test
    fun testMoreOptionsModalListeners() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        activity.ioDispatcher = kotlinx.coroutines.Dispatchers.Main
        controller.start().resume().visible()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val drawView: SmartDrawView = activity.findViewById(R.id.smartDrawView)

        // Test opacity callback
        activity.onOpacityChanged(45)
        // Test ratchet mode toggle callback
        activity.onRatchetModeToggled(true)
        assertTrue(drawView.isRatchetEnabled)
        assertEquals(true, activity.currentProject?.isRatchetEnabled)
        activity.onRatchetModeToggled(false)
        assertFalse(drawView.isRatchetEnabled)
        assertEquals(false, activity.currentProject?.isRatchetEnabled)
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

    @Test
    fun testSessionRestorationLoadsActiveProject() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        activity.ioDispatcher = kotlinx.coroutines.Dispatchers.Main
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertNotNull(activity.currentProject)
        assertNotNull(activity.projectManager.getActiveProject())
        assertEquals(activity.currentProject?.id, activity.projectManager.getActiveProjectId())

        // Create new project and switch
        val newProj = activity.projectManager.createNewProject(1200, 1600, "Portrait Test")
        activity.loadProjectIntoEditor(newProj.id)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("Portrait Test", activity.currentProject?.title)
        assertEquals(1200, activity.drawView.canvasWidth)
        assertEquals(1600, activity.drawView.canvasHeight)
    }
    @Test
    fun testClickEllipsisOpensMoreOptionsBottomSheet() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val btnMoreOptions: ImageButton = activity.findViewById(R.id.btnMoreOptions)
        assertNotNull(btnMoreOptions)
        btnMoreOptions.performClick()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val fragment = activity.supportFragmentManager.findFragmentByTag(MoreOptionsBottomSheet.TAG) as? MoreOptionsBottomSheet
        assertNotNull(fragment)
        assertNotNull(fragment?.view)
        val switchRatchet: androidx.appcompat.widget.SwitchCompat? = fragment?.view?.findViewById(R.id.switchRatchetMode)
        assertNotNull(switchRatchet)
        assertEquals(activity.drawView.isRatchetEnabled, switchRatchet?.isChecked)
    }

    @Test
    fun testClickLayersButtonOpensLayerManagerBottomSheet() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val btnLayers: ImageButton = activity.findViewById(R.id.btnLayers)
        assertNotNull(btnLayers)
        btnLayers.performClick()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val fragment = activity.supportFragmentManager.findFragmentByTag("LayerManagerBottomSheet") as? LayerManagerBottomSheet
        assertNotNull(fragment)
        assertNotNull(fragment?.view)

        val btnAddLayer: ImageButton? = fragment?.view?.findViewById(R.id.btnAddLayer)
        assertNotNull(btnAddLayer)
        btnAddLayer?.performClick()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(2, activity.drawView.getLayers().size)
    }
}
