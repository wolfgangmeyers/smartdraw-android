package com.smartdraw

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smartdraw.harness.BezierTestHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BezierHarnessAndroidTest {

    private val TAG = "SmartDraw-Test"

    @Test
    fun testRunFullBezierHarness() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = File(context.filesDir, "test-output")
        outputDir.mkdirs()

        Log.i(TAG, "Starting BezierTestHarness in outputDir: ${outputDir.absolutePath}")
        val report = BezierTestHarness.runAll(outputDir)

        Log.i(TAG, "BezierTestHarness completed. Generated ${report.generatedFiles.size} files.")
        for (f in report.generatedFiles) {
            Log.i(TAG, "Generated file: ${f.absolutePath} (size: ${f.length()} bytes)")
            assertTrue("File ${f.name} should exist", f.exists())
            assertTrue("File ${f.name} should not be empty", f.length() > 1000)
        }

        assertTrue("Should have generated at least 6 image files", report.generatedFiles.size >= 6)
        assertTrue(
            "Steady improvement should decrease loss: initial=${report.steadyImprovementInitialLoss}, final=${report.steadyImprovementFinalLoss}",
            report.steadyImprovementFinalLoss < report.steadyImprovementInitialLoss
        )
        assertTrue(
            "Steady improvement should accept between 100 and 200 strokes: accepted=${report.steadyImprovementAccepted}",
            report.steadyImprovementAccepted in 100..200
        )
    }
}
