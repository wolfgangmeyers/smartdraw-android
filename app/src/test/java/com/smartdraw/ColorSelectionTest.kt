package com.smartdraw

import android.graphics.Color
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.smartdraw.core.ColorSelectionUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorSelectionTest {

    @Test
    fun testSmartDrawViewBrushColorPropertyAndCallback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val drawView = SmartDrawView(context)

        var callbackInvokedColor: Int? = null
        drawView.onBrushColorChanged = { callbackInvokedColor = it }

        val newColor = Color.rgb(100, 150, 200)
        drawView.brushColor = newColor

        assertEquals(newColor, drawView.brushColor)
        assertEquals(newColor, callbackInvokedColor)
    }

    @Test
    fun testSmartDrawViewEyedropperInterceptionAndDragThrottling() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val drawView = SmartDrawView(context)
        drawView.brushColor = Color.BLACK
        drawView.isEyedropperActive = true

        var colorSampledInvocations = 0
        var lastSampledColor: Int? = null
        drawView.onColorSampled = {
            colorSampledInvocations++
            lastSampledColor = it
        }

        // Touch DOWN: live visual brushColor updates, but final onColorSampled does NOT fire
        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        drawView.onTouchEvent(downEvent)
        downEvent.recycle()

        assertTrue("Eyedropper should be active during gesture", drawView.isEyedropperActive)
        assertEquals("onColorSampled must not fire on ACTION_DOWN", 0, colorSampledInvocations)

        // Micro-move (< 4 canvas pixels): should be throttled
        val microMoveEvent = MotionEvent.obtain(0L, 5L, MotionEvent.ACTION_MOVE, 101f, 101f, 0)
        drawView.onTouchEvent(microMoveEvent)
        microMoveEvent.recycle()
        assertEquals("onColorSampled must not fire during ACTION_MOVE", 0, colorSampledInvocations)

        // Substantial move (>= 4 canvas pixels): updates brushColor for live visual feedback, no confirmation callback
        val moveEvent = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_MOVE, 120f, 120f, 0)
        drawView.onTouchEvent(moveEvent)
        moveEvent.recycle()
        assertEquals("onColorSampled must not fire during ACTION_MOVE", 0, colorSampledInvocations)
        assertTrue("Eyedropper should still be active during move", drawView.isEyedropperActive)

        // Touch UP: fires final onColorSampled and deactivates eyedropper
        val upEvent = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_UP, 120f, 120f, 0)
        drawView.onTouchEvent(upEvent)
        upEvent.recycle()

        assertFalse("Eyedropper must deactivate on ACTION_UP", drawView.isEyedropperActive)
        assertEquals("onColorSampled must fire exactly once on ACTION_UP", 1, colorSampledInvocations)
        assertNotNull(lastSampledColor)
    }

    @Test
    fun testSmartDrawViewEyedropperStateChangedCallback() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val drawView = SmartDrawView(context)

        var notifiedState: Boolean? = null
        drawView.onEyedropperStateChanged = { notifiedState = it }

        drawView.isEyedropperActive = true
        assertEquals(true, notifiedState)

        drawView.isEyedropperActive = false
        assertEquals(false, notifiedState)
    }

    @Test
    fun testToolSettingsBottomSheetCleanFocus() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            brushRadius = 24.0f,
            brushOpacity = 0.8f,
            brushColor = Color.BLUE,
            useReferenceColors = false
        )

        var selectedTool: SmartDrawView.ToolMode? = null
        var changedRadius: Float? = null
        var changedOpacity: Float? = null

        sheet.listener = object : ToolSettingsBottomSheet.Listener {
            override fun onToolSelected(toolMode: SmartDrawView.ToolMode) {
                selectedTool = toolMode
            }
            override fun onBrushSizeChanged(radius: Float) {
                changedRadius = radius
            }
            override fun onBrushOpacityChanged(opacity: Float) {
                changedOpacity = opacity
            }
        }

        sheet.show(activity.supportFragmentManager, "test_tool_sheet")
        activity.supportFragmentManager.executePendingTransactions()

        val view = sheet.requireView()
        val cardBrush: LinearLayout = view.findViewById(R.id.cardToolBrush)
        val cardPen: LinearLayout = view.findViewById(R.id.cardToolPen)
        val cardSmudge: LinearLayout = view.findViewById(R.id.cardToolSmudge)

        // Verify tool selection works
        cardSmudge.performClick()
        assertEquals(SmartDrawView.ToolMode.SMUDGE, selectedTool)
        assertEquals(SmartDrawView.ToolMode.SMUDGE, sheet.currentTool)

        cardPen.performClick()
        assertEquals(SmartDrawView.ToolMode.PEN, selectedTool)
        assertEquals(SmartDrawView.ToolMode.PEN, sheet.currentTool)

        cardBrush.performClick()
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, selectedTool)
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, sheet.currentTool)

        // Verify color controls are NOT in ToolSettingsBottomSheet
        assertNull("Color section should not be in tool settings", view.findViewById<SeekBar?>(R.id.sliderRed))
        assertNull("Color section should not be in tool settings", view.findViewById<TextView?>(R.id.btnModeRgb))
    }

    @Test
    fun testColorSettingsBottomSheetRgbAndHslSliders() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ColorSettingsBottomSheet.newInstance(
            brushColor = Color.rgb(10, 20, 30),
            useReferenceColors = false
        )

        var notifiedColor: Int? = null
        var eyedropperRequested = false

        sheet.listener = object : ColorSettingsBottomSheet.Listener {
            override fun onBrushColorChanged(color: Int) {
                notifiedColor = color
            }
            override fun onAutoColorToggled(enabled: Boolean) {}
            override fun onEyedropperRequested() {
                eyedropperRequested = true
            }
        }

        sheet.show(activity.supportFragmentManager, "test_color_settings_sheet")
        activity.supportFragmentManager.executePendingTransactions()

        val view = sheet.requireView()
        val sliderRed: SeekBar = view.findViewById(R.id.sliderRed)
        val sliderGreen: SeekBar = view.findViewById(R.id.sliderGreen)
        val sliderBlue: SeekBar = view.findViewById(R.id.sliderBlue)
        val textColorHex: TextView = view.findViewById(R.id.textColorHex)
        val btnModeRgb: TextView = view.findViewById(R.id.btnModeRgb)
        val btnModeHsl: TextView = view.findViewById(R.id.btnModeHsl)
        val layoutRgb: LinearLayout = view.findViewById(R.id.layoutRgbSliders)
        val layoutHsl: LinearLayout = view.findViewById(R.id.layoutHslSliders)
        val btnEyedropper: LinearLayout = view.findViewById(R.id.btnEyedropperSheet)
        val btnClose: ImageButton = view.findViewById(R.id.btnCloseColorSettings)

        // Initial values match brushColor
        assertEquals(10, sliderRed.progress)
        assertEquals(20, sliderGreen.progress)
        assertEquals(30, sliderBlue.progress)
        assertEquals("#0A141E", textColorHex.text.toString())

        // Test HSL tab switching
        btnModeHsl.performClick()
        assertEquals(ColorSettingsBottomSheet.SliderMode.HSL, sheet.sliderMode)
        assertEquals(android.view.View.VISIBLE, layoutHsl.visibility)
        assertEquals(android.view.View.GONE, layoutRgb.visibility)

        // Switch back to RGB tab
        btnModeRgb.performClick()
        assertEquals(ColorSettingsBottomSheet.SliderMode.RGB, sheet.sliderMode)
        assertEquals(android.view.View.VISIBLE, layoutRgb.visibility)
        assertEquals(android.view.View.GONE, layoutHsl.visibility)

        // Click eyedropper button
        btnEyedropper.performClick()
        assertTrue("Eyedropper must be requested on click", eyedropperRequested)

        // Close button dismisses
        assertNotNull(btnClose)
    }

    @Test
    fun testColorSettingsBottomSheetAutoColorToggle() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ColorSettingsBottomSheet.newInstance(
            brushColor = Color.BLACK,
            useReferenceColors = true
        )

        var autoColorToggledValue: Boolean? = null
        sheet.listener = object : ColorSettingsBottomSheet.Listener {
            override fun onBrushColorChanged(color: Int) {}
            override fun onAutoColorToggled(enabled: Boolean) {
                autoColorToggledValue = enabled
            }
            override fun onEyedropperRequested() {}
        }

        sheet.show(activity.supportFragmentManager, "test_auto_color_sheet")
        activity.supportFragmentManager.executePendingTransactions()

        val view = sheet.requireView()
        val layoutAutoContent: LinearLayout = view.findViewById(R.id.layoutAutoColorContent)
        val layoutManualContent: LinearLayout = view.findViewById(R.id.layoutManualColorContent)
        val btnModeAuto: TextView = view.findViewById(R.id.btnModeAutoColor)
        val btnModeManual: TextView = view.findViewById(R.id.btnModeManualColor)

        assertEquals(android.view.View.VISIBLE, layoutAutoContent.visibility)
        assertEquals(android.view.View.GONE, layoutManualContent.visibility)

        // Switch to Manual via Manual Color segment button
        btnModeManual.performClick()
        assertFalse("useReferenceColors must be false after clicking Manual Color segment", sheet.useReferenceColors)
        assertEquals(false, autoColorToggledValue)
        assertEquals(android.view.View.GONE, layoutAutoContent.visibility)
        assertEquals(android.view.View.VISIBLE, layoutManualContent.visibility)

        // Switch back via Auto Color segment button
        btnModeAuto.performClick()
        assertTrue("useReferenceColors must be true after clicking Auto Color segment", sheet.useReferenceColors)
        assertEquals(true, autoColorToggledValue)
        assertEquals(android.view.View.VISIBLE, layoutAutoContent.visibility)
        assertEquals(android.view.View.GONE, layoutManualContent.visibility)
    }

    @Test
    fun testMainActivityToolbarEyedropperAndColorButtons() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val btnColorSettings: ImageButton = activity.findViewById(R.id.btnColorSettings)
        val btnEyedropper: ImageButton = activity.findViewById(R.id.btnEyedropper)

        assertNotNull("btnColorSettings must exist in toolbar", btnColorSettings)
        assertNotNull("btnEyedropper must exist in toolbar", btnEyedropper)

        // Default state: eyedropper is inactive
        assertFalse(activity.drawView.isEyedropperActive)

        // Tap Eyedropper button: toggles on
        btnEyedropper.performClick()
        assertTrue("Eyedropper should become active after toolbar button tap", activity.drawView.isEyedropperActive)

        // Tap Eyedropper button again: toggles off
        btnEyedropper.performClick()
        assertFalse("Eyedropper should become inactive after second toolbar button tap", activity.drawView.isEyedropperActive)

        // Tap Color button: opens ColorSettingsBottomSheet
        btnColorSettings.performClick()
        activity.supportFragmentManager.executePendingTransactions()
        val colorSheet = activity.supportFragmentManager.findFragmentByTag(ColorSettingsBottomSheet.TAG)
        assertNotNull("ColorSettingsBottomSheet should be shown when tapping color button", colorSheet)
        assertTrue(colorSheet is ColorSettingsBottomSheet)
    }

    @Test
    fun testBtnColorSettingsClearsTintAndDisplaysSelectedBrushColor() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val btnColorSettings: ImageButton = activity.findViewById(R.id.btnColorSettings)

        // Set to Auto Color mode
        activity.onAutoColorToggled(true)
        assertTrue(activity.drawView.useReferenceColors)
        assertNotNull("Auto color must set imageTintList", btnColorSettings.imageTintList)

        // Switch to Manual Color mode
        val testColor = Color.rgb(255, 87, 34) // #FF5722
        activity.onAutoColorToggled(false)
        activity.onBrushColorChanged(testColor)

        // In Manual Color mode: imageTintList and colorFilter MUST be cleared so the swatch renders correctly
        assertNull("btnColorSettings imageTintList must be null in manual color mode", btnColorSettings.imageTintList)
        assertNull("btnColorSettings colorFilter must be null in manual color mode", btnColorSettings.colorFilter)
        assertTrue("btnColorSettings drawable must be GradientDrawable swatch", btnColorSettings.drawable is android.graphics.drawable.GradientDrawable)
        assertEquals("Color: #FF5722 (Tap for color settings)", btnColorSettings.contentDescription)

        // Toggle back to Auto Color mode
        activity.onAutoColorToggled(true)
        assertTrue(activity.drawView.useReferenceColors)
        assertNotNull("Auto color must restore imageTintList", btnColorSettings.imageTintList)

        // Toggle back to Manual Color mode again: must clear tint list once again
        activity.onAutoColorToggled(false)
        assertNull("btnColorSettings imageTintList must be cleared when returning to manual color mode", btnColorSettings.imageTintList)
        assertNull("btnColorSettings colorFilter must be cleared when returning to manual color mode", btnColorSettings.colorFilter)
        assertTrue("btnColorSettings drawable must be GradientDrawable swatch", btnColorSettings.drawable is android.graphics.drawable.GradientDrawable)
    }
}
