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
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.smartdraw.harness.BezierTestHarness
import com.smartdraw.harness.ReferenceImageGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity(), MoreOptionsBottomSheet.Listener {

    private lateinit var drawView: SmartDrawView
    private lateinit var btnLoadImage: ImageButton
    private lateinit var btnPencil: ImageButton
    private lateinit var btnSmudge: ImageButton
    private lateinit var btnAutoColor: ImageButton
    private lateinit var btnResetView: ImageButton
    private lateinit var btnMoreOptions: ImageButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private var currentOpacityPercent: Int = 30
    private var isSourceVisible: Boolean = true

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "SmartDraw-Main"
        private const val MAX_IMAGE_DIMENSION = 2048
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawView = findViewById(R.id.smartDrawView)
        btnLoadImage = findViewById(R.id.btnLoadImage)
        btnPencil = findViewById(R.id.btnPencil)
        btnSmudge = findViewById(R.id.btnSmudge)
        btnAutoColor = findViewById(R.id.btnAutoColor)
        btnResetView = findViewById(R.id.btnResetView)
        btnMoreOptions = findViewById(R.id.btnMoreOptions)
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
        drawView.toolMode = SmartDrawView.ToolMode.PENCIL

        updateToolSelectionUI()
        updateAutoColorUI()

        // Load default reference image to start
        val refBitmap = ReferenceImageGenerator.createRadialGradient(1024, 1024)
        drawView.setReferenceImage(refBitmap)
        drawView.setReferenceOpacity(currentOpacityPercent / 100f)
        drawView.setReferenceVisible(isSourceVisible)

        btnLoadImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnPencil.setOnClickListener {
            drawView.toolMode = SmartDrawView.ToolMode.PENCIL
            updateToolSelectionUI()
            statusText.text = "Tool: Pencil"
        }

        btnSmudge.setOnClickListener {
            drawView.toolMode = SmartDrawView.ToolMode.SMUDGE
            updateToolSelectionUI()
            statusText.text = "Tool: Smudge"
        }

        btnAutoColor.setOnClickListener {
            drawView.useReferenceColors = !drawView.useReferenceColors
            updateAutoColorUI()
            statusText.text = if (drawView.useReferenceColors) "Auto color sampling enabled" else "Manual color mode"
        }

        btnResetView.setOnClickListener {
            drawView.resetView()
            statusText.text = "View reset to 1:1 centered"
        }

        btnMoreOptions.setOnClickListener {
            showMoreOptionsModal()
        }

        if (intent.getBooleanExtra("run_harness", false)) {
            runHarnessAsync()
        }
    }

    private fun showMoreOptionsModal() {
        val sheet = MoreOptionsBottomSheet.newInstance(currentOpacityPercent, isSourceVisible)
        sheet.listener = this
        sheet.show(supportFragmentManager, MoreOptionsBottomSheet.TAG)
    }

    // --- MoreOptionsBottomSheet.Listener Implementation ---

    override fun onChangeSourceImageClicked() {
        pickImageLauncher.launch("image/*")
    }

    override fun onOpacityChanged(opacityPercent: Int) {
        currentOpacityPercent = opacityPercent
        val opacity = opacityPercent / 100f
        drawView.setReferenceOpacity(opacity)
        if (opacityPercent > 0 && !isSourceVisible) {
            isSourceVisible = true
            drawView.setReferenceVisible(true)
        }
        statusText.text = "Source image opacity: $opacityPercent%"
    }

    override fun onToggleSourceVisibilityClicked(currentlyVisible: Boolean) {
        isSourceVisible = currentlyVisible
        drawView.setReferenceVisible(isSourceVisible)
        statusText.text = if (isSourceVisible) "Source image visible" else "Source image hidden"
    }

    override fun onClearPaintingClicked() {
        AlertDialog.Builder(this)
            .setTitle("Clear Canvas")
            .setMessage("Are you sure you want to clear your painting? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                drawView.clearCanvas()
                statusText.text = "Canvas cleared"
                Toast.makeText(this, "Canvas cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResetCanvasViewClicked() {
        drawView.resetView()
        statusText.text = "View reset to 1:1 centered"
    }

    override fun onRunTestHarnessClicked() {
        runHarnessAsync()
    }

    // --- UI State Helpers ---

    private fun updateToolSelectionUI() {
        if (drawView.toolMode == SmartDrawView.ToolMode.PENCIL) {
            btnPencil.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnPencil.setColorFilter(getColor(android.R.color.white))
            btnSmudge.setBackgroundResource(R.drawable.bg_tool_button_inactive)
            btnSmudge.setColorFilter(0xFFB0B0B0.toInt())
        } else {
            btnPencil.setBackgroundResource(R.drawable.bg_tool_button_inactive)
            btnPencil.setColorFilter(0xFFB0B0B0.toInt())
            btnSmudge.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnSmudge.setColorFilter(getColor(android.R.color.white))
        }
    }

    private fun updateAutoColorUI() {
        if (drawView.useReferenceColors) {
            btnAutoColor.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnAutoColor.setColorFilter(getColor(android.R.color.white))
        } else {
            btnAutoColor.setBackgroundResource(R.drawable.bg_tool_button_inactive)
            btnAutoColor.setColorFilter(0xFFB0B0B0.toInt())
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        statusText.text = "Loading source image..."
        progressBar.visibility = View.VISIBLE

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
                        progressBar.visibility = View.GONE
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
                    progressBar.visibility = View.GONE
                    // Replace source image on GPU and ReferenceColorSampler without clearing the user's painting
                    drawView.setReferenceImage(finalBitmap)
                    drawView.setReferenceOpacity(currentOpacityPercent / 100f)
                    drawView.setReferenceVisible(isSourceVisible)
                    drawView.useReferenceColors = true
                    updateAutoColorUI()

                    val msg = "Source image updated (${finalBitmap.width}x${finalBitmap.height})"
                    statusText.text = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    statusText.text = "Error loading image"
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnLoadImage.isEnabled = enabled
        btnPencil.isEnabled = enabled
        btnSmudge.isEnabled = enabled
        btnAutoColor.isEnabled = enabled
        btnResetView.isEnabled = enabled
        btnMoreOptions.isEnabled = enabled
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
