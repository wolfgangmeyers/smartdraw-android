package com.smartdraw

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.smartdraw.harness.BezierTestHarness
import com.smartdraw.harness.ReferenceImageGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var drawView: SmartDrawView
    private lateinit var btnPencil: Button
    private lateinit var btnSmudge: Button
    private lateinit var btnAutoColor: Button
    private lateinit var btnRunHarness: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawView = findViewById(R.id.smartDrawView)
        btnPencil = findViewById(R.id.btnPencil)
        btnSmudge = findViewById(R.id.btnSmudge)
        btnAutoColor = findViewById(R.id.btnAutoColor)
        btnRunHarness = findViewById(R.id.btnRunHarness)
        statusText = findViewById(R.id.statusText)

        // Default reference image
        val refBitmap = ReferenceImageGenerator.createRadialGradient(1024, 1024)
        drawView.setReferenceImage(refBitmap)
        drawView.setReferenceOpacity(0.3f)

        btnPencil.setOnClickListener {
            drawView.toolMode = SmartDrawView.ToolMode.PENCIL
            statusText.text = "Tool: Pencil"
        }

        btnSmudge.setOnClickListener {
            drawView.toolMode = SmartDrawView.ToolMode.SMUDGE
            statusText.text = "Tool: Smudge"
        }

        btnAutoColor.setOnClickListener {
            drawView.useReferenceColors = !drawView.useReferenceColors
            btnAutoColor.text = if (drawView.useReferenceColors) "Auto Color: ON" else "Auto Color: OFF"
            statusText.text = if (drawView.useReferenceColors) "Auto color sampling enabled" else "Manual color mode"
        }

        btnRunHarness.setOnClickListener {
            runHarnessAsync()
        }

        if (intent.getBooleanExtra("run_harness", false)) {
            runHarnessAsync()
        }
    }

    private fun runHarnessAsync() {
        statusText.text = "Running Bezier Test Harness..."
        btnRunHarness.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            val outputDir = File(filesDir, "test-output")
            val report = BezierTestHarness.runAll(outputDir)

            withContext(Dispatchers.Main) {
                btnRunHarness.isEnabled = true
                statusText.text = "Done! Generated ${report.generatedFiles.size} PNGs. Loop improvement: ${String.format("%.1f", (report.steadyImprovementInitialLoss - report.steadyImprovementFinalLoss) / report.steadyImprovementInitialLoss * 100)}%"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        drawView.release()
    }
}
