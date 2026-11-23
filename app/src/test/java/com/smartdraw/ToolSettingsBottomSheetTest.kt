package com.smartdraw

import android.widget.ImageView
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
        val cardPencil: LinearLayout = view.findViewById(R.id.cardToolPencil)
        val cardSmudge: LinearLayout = view.findViewById(R.id.cardToolSmudge)
        val sectionTitle: TextView = view.findViewById(R.id.textSettingsSectionTitle)

        val sliderSize: SeekBar = view.findViewById(R.id.sliderBrushSize)
        val sliderOpacity: SeekBar = view.findViewById(R.id.sliderBrushOpacity)
        val brushPreview: BrushPreviewView = view.findViewById(R.id.brushPreview)

        assertNotNull(cardBrush)
        assertNotNull(cardPen)
        assertNotNull(cardPencil)
        assertNotNull(cardSmudge)
        assertNotNull(sliderSize)
        assertNotNull(sliderOpacity)
        assertNotNull(brushPreview)

        // Default initial is Paintbrush
        assertEquals(SmartDrawView.ToolMode.PAINTBRUSH, sheet.currentTool)
        assertEquals("PAINTBRUSH SETTINGS", sectionTitle.text)

        // Switch to Pen
        cardPen.performClick()
        assertEquals(SmartDrawView.ToolMode.PEN, selectedTool)
        assertEquals(SmartDrawView.ToolMode.PEN, sheet.currentTool)
        assertEquals("PEN SETTINGS", sectionTitle.text)

        // Switch to Pencil
        cardPencil.performClick()
        assertEquals(SmartDrawView.ToolMode.PENCIL, selectedTool)
        assertEquals(SmartDrawView.ToolMode.PENCIL, sheet.currentTool)
        assertEquals("PENCIL SETTINGS", sectionTitle.text)

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

        // Move size slider
        sliderSize.progress = 30
        assertEquals(32.0f, changedRadius!!, 0.01f)
        assertEquals(32.0f, brushPreview.brushRadius, 0.01f)

        // Move opacity slider
        sliderOpacity.progress = 49 // 50%
        assertEquals(0.5f, changedOpacity!!, 0.01f)
        assertEquals(0.5f, brushPreview.brushOpacity, 0.01f)
    }
}
