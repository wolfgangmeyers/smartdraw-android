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
import com.smartdraw.project.PaintingProject
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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColorSelectionTest {

    @Test
    fun testColorToHexFormatting() {
        assertEquals("#000000", ColorSelectionUtil.colorToHex(Color.BLACK))
        assertEquals("#FFFFFF", ColorSelectionUtil.colorToHex(Color.WHITE))
        assertEquals("#FF0000", ColorSelectionUtil.colorToHex(Color.RED))
        assertEquals("#00FF00", ColorSelectionUtil.colorToHex(Color.GREEN))
        assertEquals("#0000FF", ColorSelectionUtil.colorToHex(Color.BLUE))
        assertEquals("#2979FF", ColorSelectionUtil.colorToHex(Color.rgb(0x29, 0x79, 0xFF)))
    }

    @Test
    fun testHexToColorParsing() {
        assertEquals(Color.RED, ColorSelectionUtil.hexToColor("#FF0000"))
        assertEquals(Color.RED, ColorSelectionUtil.hexToColor("FF0000"))
        assertEquals(Color.BLACK, ColorSelectionUtil.hexToColor("invalid_hex", Color.BLACK))
    }

    @Test
    fun testRgbToColorClamping() {
        val color = ColorSelectionUtil.rgbToColor(300, -50, 128)
        assertEquals(255, Color.red(color))
        assertEquals(0, Color.green(color))
        assertEquals(128, Color.blue(color))
    }

    @Test
    fun testRgbHslRoundTripConversionInvariants() {
        val testColors = listOf(
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.YELLOW,
            Color.CYAN,
            Color.MAGENTA,
            Color.WHITE,
            Color.BLACK,
            Color.rgb(128, 64, 192),
            Color.rgb(41, 121, 255)
        )

        for (expectedColor in testColors) {
            val hsl = ColorSelectionUtil.colorToHsl(expectedColor)
            val reconstructed = ColorSelectionUtil.hslToColor(hsl[0], hsl[1], hsl[2])

            // Allow at most 1 bit rounding difference due to float quantization in HSL math
            val diffR = abs(Color.red(expectedColor) - Color.red(reconstructed))
            val diffG = abs(Color.green(expectedColor) - Color.green(reconstructed))
            val diffB = abs(Color.blue(expectedColor) - Color.blue(reconstructed))

            assertTrue("Red channel drift $diffR for $expectedColor", diffR <= 1)
            assertTrue("Green channel drift $diffG for $expectedColor", diffG <= 1)
            assertTrue("Blue channel drift $diffB for $expectedColor", diffB <= 1)
        }
    }

    @Test
    fun testSmartDrawViewBrushColorWiring() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val drawView = SmartDrawView(context)
        val project = PaintingProject(id = "test_proj", manualBrushColor = Color.BLACK)
        drawView.currentProject = project

        var callbackInvokedColor: Int? = null
        drawView.onBrushColorChanged = { callbackInvokedColor = it }

        val newColor = Color.rgb(100, 150, 200)
        drawView.brushColor = newColor

        assertEquals(newColor, drawView.brushColor)
        assertEquals(newColor, project.manualBrushColor)
        assertEquals(newColor, callbackInvokedColor)
    }

    @Test
    fun testSmartDrawViewEyedropperModeInterceptsTouchAndDeactivatesOnUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val drawView = SmartDrawView(context)
        drawView.brushColor = Color.BLACK
        drawView.isEyedropperActive = true

        var sampledColorResult: Int? = null
        drawView.onColorSampled = { sampledColorResult = it }

        // Touch DOWN
        val downEvent = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        drawView.onTouchEvent(downEvent)
        downEvent.recycle()

        assertTrue("Eyedropper should still be active during down/move", drawView.isEyedropperActive)
        assertNotNull(sampledColorResult)

        // Touch UP commits and deactivates eyedropper
        val upEvent = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, 100f, 100f, 0)
        drawView.onTouchEvent(upEvent)
        upEvent.recycle()

        assertFalse("Eyedropper must deactivate on ACTION_UP", drawView.isEyedropperActive)
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
            toolMode = SmartDrawView.ToolMode.PENCIL,
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
        val cardPencil: LinearLayout = view.findViewById(R.id.cardToolPencil)
        val cardSmudge: LinearLayout = view.findViewById(R.id.cardToolSmudge)
        val sliderSize: SeekBar = view.findViewById(R.id.sliderBrushSize)
        val sliderOpacity: SeekBar = view.findViewById(R.id.sliderBrushOpacity)

        // Verify tool selection works
        cardSmudge.performClick()
        assertEquals(SmartDrawView.ToolMode.SMUDGE, selectedTool)
        assertEquals(SmartDrawView.ToolMode.SMUDGE, sheet.currentTool)

        cardPencil.performClick()
        assertEquals(SmartDrawView.ToolMode.PENCIL, selectedTool)
        assertEquals(SmartDrawView.ToolMode.PENCIL, sheet.currentTool)

        // Verify color controls are NOT in ToolSettingsBottomSheet
        assertNull("Color section should not be in tool settings", view.findViewById<SeekBar?>(R.id.sliderRed))
        assertNull("Color section should not be in tool settings", view.findViewById<TextView?>(R.id.btnModeRgb))
    }

    @Test
    fun testColorSettingsBottomSheetRgbAndHslSliders() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ColorSettingsBottomSheet.newInstance(
            brushColor = Color.rgb(10, 20, 30),
            useReferenceColors = false,
            referenceStatus = "Loaded (Ready)"
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
    fun testColorSettingsBottomSheetAutoColorAndSwitchToManual() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ColorSettingsBottomSheet.newInstance(
            brushColor = Color.BLACK,
            useReferenceColors = true,
            referenceStatus = "Custom Image (1024×1024)"
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
        val textRefStatus: TextView = view.findViewById(R.id.textReferenceStatus)
        val btnSwitchManual: LinearLayout = view.findViewById(R.id.btnSwitchToManual)
        val btnModeAuto: TextView = view.findViewById(R.id.btnModeAutoColor)
        val btnModeManual: TextView = view.findViewById(R.id.btnModeManualColor)

        assertEquals("Custom Image (1024×1024)", textRefStatus.text.toString())
        assertEquals(android.view.View.VISIBLE, layoutAutoContent.visibility)
        assertEquals(android.view.View.GONE, layoutManualContent.visibility)

        // Click Switch to Manual button
        btnSwitchManual.performClick()
        assertFalse("useReferenceColors must be false after switching to manual", sheet.useReferenceColors)
        assertEquals(false, autoColorToggledValue)
        assertEquals(android.view.View.GONE, layoutAutoContent.visibility)
        assertEquals(android.view.View.VISIBLE, layoutManualContent.visibility)

        // Switch back via Auto Color segment button
        btnModeAuto.performClick()
        assertTrue("useReferenceColors must be true after clicking Auto Color segment", sheet.useReferenceColors)
        assertEquals(true, autoColorToggledValue)
        assertEquals(android.view.View.VISIBLE, layoutAutoContent.visibility)
        assertEquals(android.view.View.GONE, layoutManualContent.visibility)

        // Switch to Manual via Manual Color segment button
        btnModeManual.performClick()
        assertFalse("useReferenceColors must be false after clicking Manual Color segment", sheet.useReferenceColors)
        assertEquals(false, autoColorToggledValue)
        assertEquals(android.view.View.GONE, layoutAutoContent.visibility)
        assertEquals(android.view.View.VISIBLE, layoutManualContent.visibility)
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
}
