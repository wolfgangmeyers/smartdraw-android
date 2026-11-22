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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), MoreOptionsBottomSheet.Listener, ToolSettingsBottomSheet.Listener {

    private lateinit var drawView: SmartDrawView
    private lateinit var btnActiveTool: ImageButton
    private lateinit var btnAutoColor: ImageButton
    private lateinit var btnResetView: ImageButton
    private lateinit var btnMoreOptions: ImageButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private var currentOpacityPercent: Int = 30
    private var isSourceVisible: Boolean = true
    private var isCustomImageLoaded: Boolean = false

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    companion object {
        private const val TAG = "SmartDraw-Main"
        private const val MAX_IMAGE_DIMENSION = 2048
        const val MAX_GPU_TEXTURE_DIMENSION = 8192
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawView = findViewById(R.id.smartDrawView)
        btnActiveTool = findViewById(R.id.btnActiveTool)
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

        // Default: Auto Color is ON for SmartDraw, default tool is Pencil
        drawView.useReferenceColors = true
        drawView.toolMode = SmartDrawView.ToolMode.PENCIL

        updateActiveToolUI()
        updateAutoColorUI()

        // Load default reference image to start
        val refBitmap = ReferenceImageGenerator.createRadialGradient(1024, 1024)
        drawView.setReferenceImage(refBitmap)
        drawView.setReferenceOpacity(currentOpacityPercent / 100f)
        drawView.setReferenceVisible(isSourceVisible)

        btnActiveTool.setOnClickListener {
            showToolSettingsModal()
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

    private fun showToolSettingsModal() {
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = drawView.toolMode,
            brushRadius = drawView.getBrushSize(),
            brushOpacity = drawView.brushOpacity
        )
        sheet.listener = this
        sheet.show(supportFragmentManager, ToolSettingsBottomSheet.TAG)
    }

    private fun showMoreOptionsModal() {
        val sheet = MoreOptionsBottomSheet.newInstance(
            opacityPercent = currentOpacityPercent,
            isSourceVisible = isSourceVisible,
            isCustomImageLoaded = isCustomImageLoaded
        )
        sheet.listener = this
        sheet.show(supportFragmentManager, MoreOptionsBottomSheet.TAG)
    }

    // --- ToolSettingsBottomSheet.Listener Implementation ---

    override fun onToolSelected(toolMode: SmartDrawView.ToolMode) {
        drawView.toolMode = toolMode
        updateActiveToolUI()
        val name = if (toolMode == SmartDrawView.ToolMode.PENCIL) "Pencil" else "Smudge"
        statusText.text = "Tool: $name (size: ${drawView.getBrushSize().roundToInt()} px)"
    }

    override fun onBrushSizeChanged(radius: Float) {
        drawView.setBrushSize(radius)
        val name = if (drawView.toolMode == SmartDrawView.ToolMode.PENCIL) "Pencil" else "Smudge"
        statusText.text = "Tool: $name (size: ${radius.roundToInt()} px)"
    }

    override fun onBrushOpacityChanged(opacity: Float) {
        drawView.brushOpacity = opacity
        statusText.text = "Brush opacity: ${(opacity * 100f).roundToInt()}%"
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

    private fun updateActiveToolUI() {
        if (drawView.toolMode == SmartDrawView.ToolMode.PENCIL) {
            btnActiveTool.setImageResource(R.drawable.ic_pencil)
            btnActiveTool.contentDescription = "Active Tool: Pencil (Tap for settings)"
        } else {
            btnActiveTool.setImageResource(R.drawable.ic_smudge)
            btnActiveTool.contentDescription = "Active Tool: Smudge (Tap for settings)"
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

    private fun setButtonsEnabled(enabled: Boolean) {
        btnActiveTool.isEnabled = enabled
        btnAutoColor.isEnabled = enabled
        btnResetView.isEnabled = enabled
        btnMoreOptions.isEnabled = enabled
    }

    private fun loadImageFromUri(uri: Uri) {
        statusText.text = "Reading image info..."
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Decode bounds first
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }

                val origW = options.outWidth
                val origH = options.outHeight

                if (origW <= 0 || origH <= 0) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Failed to read image dimensions", Toast.LENGTH_SHORT).show()
                        statusText.text = "Failed to read image dimensions"
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    showImportResolutionDialog(uri, origW, origH)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inspecting image bounds", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    statusText.text = "Error reading image"
                }
            }
        }
    }

    private fun showImportResolutionDialog(uri: Uri, origW: Int, origH: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_import_image, null)
        val textOriginal = dialogView.findViewById<TextView>(R.id.textOriginalDimensions)
        val textScale = dialogView.findViewById<TextView>(R.id.textScaleMultiplier)
        val sliderScale = dialogView.findViewById<android.widget.SeekBar>(R.id.sliderScale)
        val textResult = dialogView.findViewById<TextView>(R.id.textResultDimensions)
        val textGpuWarning = dialogView.findViewById<TextView>(R.id.textGpuLimitWarning)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancelImport)
        val btnConfirm = dialogView.findViewById<android.widget.Button>(R.id.btnConfirmImport)

        val origMp = (origW.toLong() * origH.toLong()) / 1_000_000.0
        textOriginal.text = "Original: $origW × $origH (%.1f MP)".format(origMp)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        fun updateResolutionPreview(scale: Int) {
            val targetW = origW * scale
            val targetH = origH * scale
            val targetMp = (targetW.toLong() * targetH.toLong()) / 1_000_000.0

            textScale.text = "${scale}x"
            textResult.text = "Canvas Resolution: $targetW × $targetH (%.1f MP)".format(targetMp)

            if (targetW > MAX_GPU_TEXTURE_DIMENSION || targetH > MAX_GPU_TEXTURE_DIMENSION) {
                textGpuWarning.visibility = View.VISIBLE
                textGpuWarning.text = "Warning: Exceeds GPU limit ($MAX_GPU_TEXTURE_DIMENSION px). Please select a lower scale."
                btnConfirm.isEnabled = false
            } else {
                textGpuWarning.visibility = View.GONE
                btnConfirm.isEnabled = true
            }
        }

        // Slider progress: 0 to 7 -> scale: 1 to 8
        // Default to 1x (or 2x if original is small)
        val defaultScale = if (max(origW, origH) <= 1024) 2 else 1
        sliderScale.progress = defaultScale - 1
        updateResolutionPreview(defaultScale)

        sliderScale.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = progress + 1
                updateResolutionPreview(scale)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            val selectedScale = sliderScale.progress + 1
            performImageImport(uri, origW * selectedScale, origH * selectedScale)
        }

        dialog.show()
    }

    private fun performImageImport(uri: Uri, targetW: Int, targetH: Int) {
        statusText.text = "Importing image and creating canvas (${targetW}×${targetH})..."
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
                        Toast.makeText(this@MainActivity, "Failed to decode image", Toast.LENGTH_SHORT).show()
                        statusText.text = "Failed to decode image"
                    }
                    return@launch
                }

                val finalBitmap = if (loadedBitmap.width != targetW || loadedBitmap.height != targetH) {
                    Bitmap.createScaledBitmap(loadedBitmap, targetW, targetH, true)
                } else {
                    loadedBitmap
                }

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    isCustomImageLoaded = true
                    drawView.setCanvasDimensions(targetW, targetH)
                    drawView.setReferenceImage(finalBitmap)
                    drawView.useReferenceColors = true
                    updateAutoColorUI()
                    statusText.text = "Canvas created: ${targetW}×${targetH} px"
                    Toast.makeText(this@MainActivity, "Loaded ${targetW}×${targetH} canvas", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing image", e)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Import error: ${e.message}", Toast.LENGTH_SHORT).show()
                    statusText.text = "Import error"
                }
            }
        }
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
}
