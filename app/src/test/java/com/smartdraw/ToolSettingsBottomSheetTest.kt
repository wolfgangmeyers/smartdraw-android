package com.smartdraw

import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.smartdraw.ui.BrushPreviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolSettingsBottomSheetTest {

    @Test
    fun testToolSelectionAndSlidersInSheet() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = SmartDrawView.ToolMode.PAINTBRUSH,
            brushRadius = 16.0f,
            brushOpacity = 1.0f
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

        sheet.show(activity.supportFragmentManager, "test")
        activity.supportFragmentManager.executePendingTransactions()

        val view = sheet.requireView()
        val cardBrush: LinearLayout = view.findViewById(R.id.cardToolBrush)
        val cardPen: LinearLayout = view.findViewById(R.id.cardToolPen)
        val cardEraser: LinearLayout = view.findViewById(R.id.cardToolEraser)
        val cardSmudge: LinearLayout = view.findViewById(R.id.cardToolSmudge)
        val sectionTitle: TextView = view.findViewById(R.id.textSettingsSectionTitle)

        val sliderSize: SeekBar = view.findViewById(R.id.sliderBrushSize)
        val sliderOpacity: SeekBar = view.findViewById(R.id.sliderBrushOpacity)
        val brushPreview: BrushPreviewView = view.findViewById(R.id.brushPreview)
        val textSizeValue: TextView = view.findViewById(R.id.textSizeValue)

        assertNotNull(cardBrush)
        assertNotNull(cardPen)
        assertNotNull(cardEraser)
        assertNotNull(cardSmudge)
        assertNotNull(sliderSize)
        assertNotNull(sliderOpacity)
        assertNotNull(brushPreview)

        // Default initial is Paintbrush
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, sheet.currentTool)
        assertEquals("PAINTBRUSH SETTINGS", sectionTitle.text)
        assertEquals("16 px", textSizeValue.text)

        // Switch to Pen
        cardPen.performClick()
        assertEquals(SmartDrawView.ToolMode.PEN, selectedTool)
        assertEquals(SmartDrawView.ToolMode.PEN, sheet.currentTool)
        assertEquals("PEN SETTINGS", sectionTitle.text)

        // Switch to Eraser
        cardEraser.performClick()
        assertEquals(SmartDrawView.ToolMode.ERASER, selectedTool)
        assertEquals(SmartDrawView.ToolMode.ERASER, sheet.currentTool)
        assertEquals("ERASER SETTINGS", sectionTitle.text)

        // Switch to Smudge
        cardSmudge.performClick()
        assertEquals(SmartDrawView.ToolMode.SMUDGE, selectedTool)
        assertEquals(SmartDrawView.ToolMode.SMUDGE, sheet.currentTool)
        assertEquals("SMUDGE SETTINGS", sectionTitle.text)

        // Switch back to Paintbrush
        cardBrush.performClick()
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, selectedTool)
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, sheet.currentTool)
        assertEquals("PAINTBRUSH SETTINGS", sectionTitle.text)

        // Test minimum brush size: progress 0 maps to 0.5 px
        sliderSize.progress = 0
        assertEquals(0.5f, changedRadius!!, 0.01f)
        assertEquals(0.5f, brushPreview.brushRadius, 0.01f)
        assertEquals("0.5 px", textSizeValue.text)

        // Test fine step: progress 1 maps to 1.0 px ("1 px")
        sliderSize.progress = 1
        assertEquals(1.0f, changedRadius!!, 0.01f)
        assertEquals(1.0f, brushPreview.brushRadius, 0.01f)
        assertEquals("1 px", textSizeValue.text)

        // Test fine step: progress 2 maps to 1.5 px
        sliderSize.progress = 2
        assertEquals(1.5f, changedRadius!!, 0.01f)
        assertEquals(1.5f, brushPreview.brushRadius, 0.01f)
        assertEquals("1.5 px", textSizeValue.text)

        // Test larger size: progress 63 maps to 32.0 px
        sliderSize.progress = 63
        assertEquals(32.0f, changedRadius!!, 0.01f)
        assertEquals(32.0f, brushPreview.brushRadius, 0.01f)
        assertEquals("32 px", textSizeValue.text)

        // Move opacity slider
        sliderOpacity.progress = 49 // 50%
        assertEquals(0.5f, changedOpacity!!, 0.01f)
        assertEquals(0.5f, brushPreview.brushOpacity, 0.01f)
    }

    @Test
    fun testBrushRadiusHelpers() {
        assertEquals("0.5 px", ToolSettingsBottomSheet.formatRadiusText(0.5f))
        assertEquals("1 px", ToolSettingsBottomSheet.formatRadiusText(1.0f))
        assertEquals("1.5 px", ToolSettingsBottomSheet.formatRadiusText(1.5f))
        assertEquals("2 px", ToolSettingsBottomSheet.formatRadiusText(2.0f))
        assertEquals("100 px", ToolSettingsBottomSheet.formatRadiusText(100.0f))

        assertEquals(0, ToolSettingsBottomSheet.radiusToProgress(0.5f))
        assertEquals(1, ToolSettingsBottomSheet.radiusToProgress(1.0f))
        assertEquals(2, ToolSettingsBottomSheet.radiusToProgress(1.5f))
        assertEquals(31, ToolSettingsBottomSheet.radiusToProgress(16.0f))
        assertEquals(199, ToolSettingsBottomSheet.radiusToProgress(100.0f))

        assertEquals(0.5f, ToolSettingsBottomSheet.progressToRadius(0), 0.001f)
        assertEquals(1.0f, ToolSettingsBottomSheet.progressToRadius(1), 0.001f)
        assertEquals(1.5f, ToolSettingsBottomSheet.progressToRadius(2), 0.001f)
        assertEquals(16.0f, ToolSettingsBottomSheet.progressToRadius(31), 0.001f)
        assertEquals(100.0f, ToolSettingsBottomSheet.progressToRadius(199), 0.001f)
    }
}
