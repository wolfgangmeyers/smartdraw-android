package com.smartdraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.smartdraw.harness.BezierTestHarness
import com.smartdraw.harness.ReferenceImageGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var drawView: SmartDrawView
    private lateinit var btnLoadImage: Button
    private lateinit var btnPencil: Button
    private lateinit var btnSmudge: Button
    private lateinit var btnAutoColor: Button
    private lateinit var btnRefOpacity: Button
    private lateinit var btnClearCanvas: Button
    private lateinit var btnRunHarness: Button
    private lateinit var statusText: TextView

    private val opacities = floatArrayOf(0.15f, 0.3f, 0.5f, 0.8f, 1.0f, 0.0f)
    private var opacityIndex = 1 // 0.3f default

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadImageFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawView = findViewById(R.id.smartDrawView)
        btnLoadImage = findViewById(R.id.btnLoadImage)
        btnPencil = findViewById(R.id.btnPencil)
        btnSmudge = findViewById(R.id.btnSmudge)
        btnAutoColor = findViewById(R.id.btnAutoColor)
        btnRefOpacity = findViewById(R.id.btnRefOpacity)
        btnClearCanvas = findViewById(R.id.btnClearCanvas)
        btnRunHarness = findViewById(R.id.btnRunHarness)
        statusText = findViewById(R.id.statusText)

        // Default: Auto Color is ON for SmartDraw
        drawView.useReferenceColors = true
        btnAutoColor.text = "Auto Color: ON"

        // Load default reference image to start
        val refBitmap = ReferenceImageGenerator.createRadialGradient(1024, 1024)
        drawView.setReferenceImage(refBitmap)
        drawView.setReferenceOpacity(opacities[opacityIndex])

        btnLoadImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

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

        btnRefOpacity.setOnClickListener {
            opacityIndex = (opacityIndex + 1) % opacities.size
            val opacity = opacities[opacityIndex]
            drawView.setReferenceOpacity(opacity)
            drawView.setReferenceVisible(opacity > 0.001f)
            val pct = (opacity * 100).toInt()
            btnRefOpacity.text = if (opacity <= 0.001f) "Ref: OFF" else "Ref: $pct%"
            statusText.text = "Reference opacity: $pct%"
        }

        btnClearCanvas.setOnClickListener {
            drawView.clearCanvas()
            statusText.text = "Canvas cleared"
        }

        btnRunHarness.setOnClickListener {
            runHarnessAsync()
        }

        if (intent.getBooleanExtra("run_harness", false)) {
            runHarnessAsync()
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        statusText.text = "Loading image..."
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val loadedBitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                } else {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }

                if (loadedBitmap == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                        statusText.text = "Failed to load image"
                    }
                    return@launch
                }

                // Scale to max 2048x2048 preserving aspect ratio for mobile GPU memory
                val maxDim = 2048
                val maxSide = max(loadedBitmap.width, loadedBitmap.height)
                val finalBitmap = if (maxSide > maxDim) {
                    val scale = maxDim.toFloat() / maxSide
                    val w = (loadedBitmap.width * scale).toInt()
                    val h = (loadedBitmap.height * scale).toInt()
                    Bitmap.createScaledBitmap(loadedBitmap, w, h, true)
                } else {
                    loadedBitmap
                }

                withContext(Dispatchers.Main) {
                    drawView.setReferenceImage(finalBitmap)
                    drawView.clearCanvas() // Fresh canvas for new reference photo
                    statusText.text = "Loaded: ${finalBitmap.width}x${finalBitmap.height}"
                    Toast.makeText(this@MainActivity, "Loaded image (${finalBitmap.width}x${finalBitmap.height})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SmartDraw", "Error loading image", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    statusText.text = "Error loading image"
                }
            }
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
