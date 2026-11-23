package com.smartdraw

import android.graphics.Color
import android.view.MotionEvent
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
    fun testToolSettingsBottomSheetRgbAndHslSliders() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = SmartDrawView.ToolMode.PENCIL,
            brushRadius = 20.0f,
            brushOpacity = 1.0f,
            brushColor = Color.rgb(10, 20, 30),
            useReferenceColors = false
        )

        var notifiedColor: Int? = null
        var eyedropperRequested = false

        sheet.listener = object : ToolSettingsBottomSheet.Listener {
            override fun onToolSelected(toolMode: SmartDrawView.ToolMode) {}
            override fun onBrushSizeChanged(radius: Float) {}
            override fun onBrushOpacityChanged(opacity: Float) {}
            override fun onBrushColorChanged(color: Int) {
                notifiedColor = color
            }
            override fun onEyedropperRequested() {
                eyedropperRequested = true
            }
        }

        sheet.show(activity.supportFragmentManager, "test_color_sheet")
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
        val sliderHue: SeekBar = view.findViewById(R.id.sliderHue)
        val sliderSat: SeekBar = view.findViewById(R.id.sliderSaturation)
        val sliderLight: SeekBar = view.findViewById(R.id.sliderLightness)
        val btnEyedropper: LinearLayout = view.findViewById(R.id.btnEyedropper)

        // Initial values match brushColor
        assertEquals(10, sliderRed.progress)
        assertEquals(20, sliderGreen.progress)
        assertEquals(30, sliderBlue.progress)
        assertEquals("#0A141E", textColorHex.text.toString())

        // Move Red slider via user drag simulation
        sliderRed.progress = 255
        // Manually trigger listener as user change
        sliderRed.callOnClick() // or trigger progress change
        // Directly test HSL tab switching
        btnModeHsl.performClick()
        assertEquals(ToolSettingsBottomSheet.ColorMode.HSL, sheet.colorMode)
        assertEquals(android.view.View.VISIBLE, layoutHsl.visibility)
        assertEquals(android.view.View.GONE, layoutRgb.visibility)

        // Switch back to RGB tab
        btnModeRgb.performClick()
        assertEquals(ToolSettingsBottomSheet.ColorMode.RGB, sheet.colorMode)
        assertEquals(android.view.View.VISIBLE, layoutRgb.visibility)
        assertEquals(android.view.View.GONE, layoutHsl.visibility)

        // Click eyedropper button
        btnEyedropper.performClick()
        assertTrue("Eyedropper must be requested on click", eyedropperRequested)
    }

    @Test
    fun testToolSettingsBottomSheetAutoColorNoticeAndSwitchToManual() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = SmartDrawView.ToolMode.PENCIL,
            brushRadius = 20.0f,
            brushOpacity = 1.0f,
            brushColor = Color.BLACK,
            useReferenceColors = true
        )

        var autoColorToggledValue: Boolean? = null
        sheet.listener = object : ToolSettingsBottomSheet.Listener {
            override fun onToolSelected(toolMode: SmartDrawView.ToolMode) {}
            override fun onBrushSizeChanged(radius: Float) {}
            override fun onBrushOpacityChanged(opacity: Float) {}
            override fun onAutoColorToggled(enabled: Boolean) {
                autoColorToggledValue = enabled
            }
        }

        sheet.show(activity.supportFragmentManager, "test_auto_color_sheet")
        activity.supportFragmentManager.executePendingTransactions()

        val view = sheet.requireView()
        val layoutAutoNotice: LinearLayout = view.findViewById(R.id.layoutAutoColorNotice)
        val layoutManual: LinearLayout = view.findViewById(R.id.layoutManualColorControls)
        val btnDisableAuto: LinearLayout = view.findViewById(R.id.btnDisableAutoColor)

        assertEquals(android.view.View.VISIBLE, layoutAutoNotice.visibility)
        assertEquals(android.view.View.GONE, layoutManual.visibility)

        btnDisableAuto.performClick()
        assertFalse("useReferenceColors must be false after switching to manual", sheet.useReferenceColors)
        assertEquals(false, autoColorToggledValue)
        assertEquals(android.view.View.GONE, layoutAutoNotice.visibility)
        assertEquals(android.view.View.VISIBLE, layoutManual.visibility)
    }
}
