package com.smartdraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
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
    private lateinit var btnPencil: Button
    private lateinit var btnSmudge: Button
    private lateinit var btnLoadImage: Button
    private lateinit var btnAutoColor: Button
    private lateinit var btnRefOpacity: Button
    private lateinit var btnClearCanvas: Button
    private lateinit var btnResetView: Button
    private lateinit var btnRunHarness: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val opacities = floatArrayOf(0.15f, 0.3f, 0.5f, 0.8f, 1.0f, 0.0f)
    private var opacityIndex = 1 // 0.3f default

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "SmartDraw-Main"
        private const val MAX_IMAGE_DIMENSION = 2048
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawView = findViewById(R.id.smartDrawView)
        btnPencil = findViewById(R.id.btnPencil)
        btnSmudge = findViewById(R.id.btnSmudge)
        btnLoadImage = findViewById(R.id.btnLoadImage)
        btnAutoColor = findViewById(R.id.btnAutoColor)
        btnRefOpacity = findViewById(R.id.btnRefOpacity)
        btnClearCanvas = findViewById(R.id.btnClearCanvas)
        btnResetView = findViewById(R.id.btnResetView)
        btnRunHarness = findViewById(R.id.btnRunHarness)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        // ActivityResultLauncher for selecting source/reference image from gallery or storage
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                loadImageFromUri(uri)
            }
        }

        // Default: Auto Color is ON for SmartDraw
        drawView.useReferenceColors = true
        updateAutoColorButton()

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
            updateAutoColorButton()
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

        btnResetView.setOnClickListener {
            drawView.resetView()
            statusText.text = "View reset to 1:1 centered"
        }

        btnRunHarness.setOnClickListener {
            runHarnessAsync()
        }

        if (intent.getBooleanExtra("run_harness", false)) {
            runHarnessAsync()
        }
    }

    private fun updateAutoColorButton() {
        btnAutoColor.text = if (drawView.useReferenceColors) "Auto Color: ON" else "Auto Color: OFF"
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
                val maxDim = MAX_IMAGE_DIMENSION
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
                    drawView.useReferenceColors = true
                    updateAutoColorButton()

                    val msg = "Loaded image (${finalBitmap.width}x${finalBitmap.height}) for Auto Color"
                    statusText.text = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    statusText.text = "Error loading image"
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnPencil.isEnabled = enabled
        btnSmudge.isEnabled = enabled
        btnLoadImage.isEnabled = enabled
        btnAutoColor.isEnabled = enabled
        btnRefOpacity.isEnabled = enabled
        btnClearCanvas.isEnabled = enabled
        btnResetView.isEnabled = enabled
        btnRunHarness.isEnabled = enabled
    }

    private fun runHarnessAsync() {
        statusText.text = "Running Bezier Test Harness..."
        progressBar.visibility = View.VISIBLE
        setButtonsEnabled(false)

        CoroutineScope(Dispatchers.IO).launch {
            val internalOutputDir = File(filesDir, "test-output")
            val externalPicturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val externalOutputDir = if (externalPicturesDir != null) File(externalPicturesDir, "test-output") else null

            val report = BezierTestHarness.runAll(internalOutputDir)

            // Copy output files to external files pictures directory for easy access
            if (externalOutputDir != null) {
                externalOutputDir.mkdirs()
                for (file in report.generatedFiles) {
                    try {
                        file.copyTo(File(externalOutputDir, file.name), overwrite = true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to copy test file to external pictures: ${file.name}", e)
                    }
                }
            }

            // Load final composited output onto drawView so the user sees the rendered painting
            val showcaseFile = File(internalOutputDir, "test_steady_improvement_painting.png").let {
                if (it.exists()) it else File(internalOutputDir, "test_bezier_curves_showcase.png")
            }

            val finalBitmap = if (showcaseFile.exists()) {
                BitmapFactory.decodeFile(showcaseFile.absolutePath)
            } else null

            withContext(Dispatchers.Main) {
                if (finalBitmap != null) {
                    drawView.loadPaintingBitmap(finalBitmap)
                }

                setButtonsEnabled(true)
                progressBar.visibility = View.GONE

                val improvement = if (report.steadyImprovementInitialLoss > 0.0) {
                    String.format("%.1f", (report.steadyImprovementInitialLoss - report.steadyImprovementFinalLoss) / report.steadyImprovementInitialLoss * 100)
                } else "0.0"

                val completionMessage = "Test harness complete! Saved ${report.generatedFiles.size} images."
                statusText.text = "$completionMessage Loop: $improvement% improvement."
                Toast.makeText(this@MainActivity, completionMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        drawView.release()
    }
}
