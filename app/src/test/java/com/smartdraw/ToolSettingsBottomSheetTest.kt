package com.smartdraw

import android.widget.LinearLayout
import android.widget.SeekBar
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
            toolMode = SmartDrawView.ToolMode.PENCIL,
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
        val cardPencil: LinearLayout = view.findViewById(R.id.cardToolPencil)
        val cardSmudge: LinearLayout = view.findViewById(R.id.cardToolSmudge)
        val sliderSize: SeekBar = view.findViewById(R.id.sliderBrushSize)
        val sliderOpacity: SeekBar = view.findViewById(R.id.sliderBrushOpacity)
        val brushPreview: BrushPreviewView = view.findViewById(R.id.brushPreview)

        assertNotNull(cardPencil)
        assertNotNull(cardSmudge)
        assertNotNull(sliderSize)
        assertNotNull(sliderOpacity)
        assertNotNull(brushPreview)

        // Switch to Smudge
        cardSmudge.performClick()
        assertEquals(SmartDrawView.ToolMode.SMUDGE, selectedTool)

        // Switch back to Pencil
        cardPencil.performClick()
        assertEquals(SmartDrawView.ToolMode.PENCIL, selectedTool)

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
