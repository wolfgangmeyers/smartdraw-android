package com.smartdraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
    private lateinit var btnResetView: Button
    private lateinit var btnRunHarness: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

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
        btnResetView = findViewById(R.id.btnResetView)
        btnRunHarness = findViewById(R.id.btnRunHarness)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        // ActivityResultLauncher for selecting source/reference image from gallery or storage
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                loadSelectedImage(uri)
            }
        }

        // Default reference image
        val refBitmap = ReferenceImageGenerator.createRadialGradient(1024, 1024)
        drawView.setReferenceImage(refBitmap)
        drawView.setReferenceOpacity(0.35f)

        btnPencil.setOnClickListener {
            drawView.toolMode = SmartDrawView.ToolMode.PENCIL
            statusText.text = "Tool: Pencil"
        }

        btnSmudge.setOnClickListener {
            drawView.toolMode = SmartDrawView.ToolMode.SMUDGE
            statusText.text = "Tool: Smudge"
        }

        btnLoadImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnAutoColor.setOnClickListener {
            drawView.useReferenceColors = !drawView.useReferenceColors
            updateAutoColorButton()
            statusText.text = if (drawView.useReferenceColors) "Auto color sampling enabled" else "Manual color mode"
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

    private fun loadSelectedImage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bitmap = decodeSampledBitmapFromUri(uri, MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION)
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        drawView.setReferenceImage(bitmap)
                        drawView.setReferenceOpacity(0.35f)
                        drawView.useReferenceColors = true
                        updateAutoColorButton()

                        val msg = "Loaded image (${bitmap.width}x${bitmap.height}) for Auto Color"
                        statusText.text = msg
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to decode selected image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from URI: $uri", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val origW = options.outWidth
        val origH = options.outHeight
        if (origW <= 0 || origH <= 0) return null

        var inSampleSize = 1
        val maxDim = max(origW, origH)
        val targetMax = max(maxWidth, maxHeight)
        while ((maxDim / inSampleSize) > targetMax) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        } ?: return null

        // Downscale precisely if still larger than target max dimension
        val currentMax = max(decoded.width, decoded.height)
        if (currentMax > targetMax) {
            val scale = targetMax.toFloat() / currentMax.toFloat()
            val newW = (decoded.width * scale).toInt()
            val newH = (decoded.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(decoded, newW, newH, true)
            if (scaled != decoded) {
                decoded.recycle()
            }
            return scaled
        }

        return decoded
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnPencil.isEnabled = enabled
        btnSmudge.isEnabled = enabled
        btnLoadImage.isEnabled = enabled
        btnAutoColor.isEnabled = enabled
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
