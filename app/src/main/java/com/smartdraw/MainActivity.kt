package com.smartdraw

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.smartdraw.core.ColorSelectionUtil
import com.smartdraw.gallery.GalleryDialogFragment
import com.smartdraw.harness.BezierTestHarness
import com.smartdraw.harness.ReferenceImageGenerator
import com.smartdraw.project.AutoSaveManager
import com.smartdraw.project.PaintingProject
import com.smartdraw.project.ProjectManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(),
    MoreOptionsBottomSheet.Listener,
    ToolSettingsBottomSheet.Listener,
    ColorSettingsBottomSheet.Listener,
    GalleryDialogFragment.Listener {

    companion object {
        private const val TAG = "SmartDraw-Main"
        private const val MAX_IMAGE_DIMENSION = 2048
        const val MAX_GPU_TEXTURE_DIMENSION = 8192
        var defaultIoDispatcher: CoroutineDispatcher = Dispatchers.IO
    }

    var ioDispatcher: CoroutineDispatcher = defaultIoDispatcher

    lateinit var drawView: SmartDrawView
    private lateinit var btnActiveTool: ImageButton
    private lateinit var btnColorSettings: ImageButton
    private lateinit var btnEyedropper: ImageButton
    private lateinit var btnResetView: ImageButton
    private lateinit var btnLayers: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnMoreOptions: ImageButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private var currentOpacityPercent: Int = 30
    private var isSourceVisible: Boolean = true
    private var isCustomImageLoaded: Boolean = false
    private var currentReferenceBitmap: Bitmap? = null

    lateinit var projectManager: ProjectManager
    lateinit var autoSaveManager: AutoSaveManager
    var currentProject: PaintingProject? = null

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawView = findViewById(R.id.smartDrawView)
        btnActiveTool = findViewById(R.id.btnActiveTool)
        btnColorSettings = findViewById(R.id.btnColorSettings)
        btnEyedropper = findViewById(R.id.btnEyedropper)
        btnResetView = findViewById(R.id.btnResetView)
        btnLayers = findViewById(R.id.btnLayers)
        btnGallery = findViewById(R.id.btnGallery)
        btnMoreOptions = findViewById(R.id.btnMoreOptions)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        projectManager = ProjectManager(this)

        autoSaveManager = AutoSaveManager(
            projectManager = projectManager,
            coroutineScope = lifecycleScope,
            provider = object : AutoSaveManager.ProjectDataProvider {
                override fun getCurrentProject(): PaintingProject? {
                    val p = currentProject ?: return null
                    // Synchronize latest UI settings into currentProject
                    p.referenceOpacity = currentOpacityPercent / 100f
                    p.isReferenceVisible = isSourceVisible
                    p.useReferenceColors = drawView.useReferenceColors
                    p.isRatchetEnabled = drawView.isRatchetEnabled
                    p.activeTool = when (drawView.toolMode) {
                        SmartDrawView.ToolMode.PAINTBRUSH -> "PAINTBRUSH"
                        SmartDrawView.ToolMode.PEN -> "PEN"
                        SmartDrawView.ToolMode.SMUDGE -> "SMUDGE"
                    }
                    p.brushSize = drawView.getBrushSize()
                    p.brushOpacity = drawView.brushOpacity
                    p.brushSizeBrush = drawView.getToolRadius(SmartDrawView.ToolMode.PAINTBRUSH)
                    p.brushSizePen = drawView.getToolRadius(SmartDrawView.ToolMode.PEN)
                    p.brushSizeSmudge = drawView.getToolRadius(SmartDrawView.ToolMode.SMUDGE)
                    p.brushOpacityBrush = drawView.getToolOpacity(SmartDrawView.ToolMode.PAINTBRUSH)
                    p.brushOpacityPen = drawView.getToolOpacity(SmartDrawView.ToolMode.PEN)
                    p.brushOpacitySmudge = drawView.getToolOpacity(SmartDrawView.ToolMode.SMUDGE)
                    p.manualBrushColor = drawView.brushColor
                    p.layers = drawView.getLayers()
                    p.activeLayerId = drawView.getActiveLayerId()
                    return p
                }

                override fun requestBaseBitmap(onBitmapReady: (Bitmap) -> Unit) {
                    drawView.exportBaseBitmap(onBitmapReady)
                }

                override fun getReferenceBitmap(): Bitmap? {
                    return currentReferenceBitmap
                }
            }
        )

        drawView.onStrokeCompleted = {
            autoSaveManager.markDirty()
        }

        drawView.onLayersChanged = {
            autoSaveManager.markDirty()
        }

        drawView.onBrushColorChanged = { color ->
            currentProject?.manualBrushColor = color
            updateColorUI()
            autoSaveManager.markDirty()
        }

        drawView.onEyedropperStateChanged = { _ ->
            updateEyedropperUI()
        }

        drawView.onColorSampled = { sampledColor ->
            drawView.brushColor = sampledColor
            currentProject?.manualBrushColor = sampledColor
            drawView.useReferenceColors = false
            currentProject?.useReferenceColors = false
            updateColorUI()
            updateEyedropperUI()
            autoSaveManager.markDirty()
            val hex = ColorSelectionUtil.colorToHex(sampledColor)
            statusText.text = "Sampled color: $hex (Manual color mode)"
            Toast.makeText(this@MainActivity, "Picked color: $hex", Toast.LENGTH_SHORT).show()
        }

        // ActivityResultLauncher for selecting source/reference image from gallery or storage
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                loadImageFromUri(uri)
            }
        }

        // Toolbar Button Listeners
        btnActiveTool.setOnClickListener {
            showToolSettingsModal()
        }

        btnColorSettings.setOnClickListener {
            showColorSettingsModal()
        }

        btnEyedropper.setOnClickListener {
            if (drawView.isEyedropperActive) {
                drawView.isEyedropperActive = false
                statusText.text = "Eyedropper cancelled"
            } else {
                drawView.isEyedropperActive = true
                statusText.text = "Eyedropper active: Tap on canvas to sample color"
                Toast.makeText(this@MainActivity, "Tap or drag on canvas to pick a color", Toast.LENGTH_SHORT).show()
            }
            updateEyedropperUI()
        }

        btnResetView.setOnClickListener {
            drawView.resetView()
            statusText.text = "View reset to 1:1 centered"
        }

        btnLayers.setOnClickListener {
            LayerManagerBottomSheet.newInstance().show(supportFragmentManager, "LayerManagerBottomSheet")
        }

        btnGallery.setOnClickListener {
            openGallery()
        }

        btnMoreOptions.setOnClickListener {
            showMoreOptionsModal()
        }

        updateActiveToolUI()
        updateColorUI()
        updateEyedropperUI()

        updateActiveToolUI()

        // Session Restoration / Cold Start
        restoreOrInitializeSession()

        if (intent.getBooleanExtra("run_harness", false)) {
            runHarnessAsync()
        }
    }

    override fun onPause() {
        super.onPause()
        autoSaveManager.flushSaveSync()
    }

    override fun onStop() {
        super.onStop()
        autoSaveManager.flushSaveSync()
    }

    private fun restoreOrInitializeSession() {
        var activeProject = projectManager.getActiveProject()
        if (activeProject == null) {
            // First install or no projects: create initial project with blank canvas
            activeProject = projectManager.createNewProject(1024, 1024, "My First Painting")
        }

        loadProjectIntoEditor(activeProject.id)
    }

    fun loadProjectIntoEditor(projectId: String) {
        lifecycleScope.launch(ioDispatcher) {
            try {
                val loaded = projectManager.loadProject(projectId)
                val project = loaded.project
                withContext(Dispatchers.Main) {
                    currentProject = project
                    projectManager.setActiveProject(project.id)

                    // Canvas Dimensions & Base Layer
                    drawView.setCanvasDimensions(project.canvasWidth, project.canvasHeight)
                    if (project.layers.isNotEmpty()) {
                        drawView.setLayers(project.layers, project.activeLayerId)
                    }
                    if (loaded.baseBitmap != null) {
                        drawView.loadBaseBitmap(loaded.baseBitmap)
                    } else {
                        drawView.clearCanvas()
                    }

                    // Reference Image
                    val oldRefBitmap = currentReferenceBitmap
                    val newRefBitmap = loaded.referenceBitmap ?: ReferenceImageGenerator.createRadialGradient(project.canvasWidth, project.canvasHeight)
                    if (oldRefBitmap != null && oldRefBitmap != newRefBitmap && !oldRefBitmap.isRecycled) {
                        oldRefBitmap.recycle()
                    }
                    currentReferenceBitmap = newRefBitmap
                    drawView.setReferenceImage(newRefBitmap)
                    isCustomImageLoaded = (loaded.referenceBitmap != null)

                    // Reference Layer Visibility & Opacity
                    currentOpacityPercent = (project.referenceOpacity * 100f).roundToInt().coerceIn(0, 100)
                    isSourceVisible = project.isReferenceVisible
                    drawView.setReferenceOpacity(project.referenceOpacity)
                    drawView.setReferenceVisible(isSourceVisible)

                    // Tool & Brush Settings
                    drawView.brushColor = project.manualBrushColor
                    drawView.useReferenceColors = project.useReferenceColors
                    drawView.setRatchetEnabled(project.isRatchetEnabled)

                    drawView.setToolRadius(SmartDrawView.ToolMode.PAINTBRUSH, project.brushSizeBrush)
                    drawView.setToolRadius(SmartDrawView.ToolMode.PEN, project.brushSizePen)
                    drawView.setToolRadius(SmartDrawView.ToolMode.SMUDGE, project.brushSizeSmudge)
                    drawView.setToolOpacity(SmartDrawView.ToolMode.PAINTBRUSH, project.brushOpacityBrush)
                    drawView.setToolOpacity(SmartDrawView.ToolMode.PEN, project.brushOpacityPen)
                    drawView.setToolOpacity(SmartDrawView.ToolMode.SMUDGE, project.brushOpacitySmudge)

                    drawView.toolMode = when (project.activeTool) {
                        "SMUDGE" -> SmartDrawView.ToolMode.SMUDGE
                        "PEN" -> SmartDrawView.ToolMode.PEN
                        else -> SmartDrawView.ToolMode.PAINTBRUSH
                    }

                    updateActiveToolUI()
                    updateColorUI()
                    updateEyedropperUI()
                    drawView.resetView()

                    statusText.text = "${project.title} loaded"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load project $projectId", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to load project: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openGallery() {
        // Flush any pending auto-save before opening gallery
        autoSaveManager.flushSaveSync()

        val galleryDialog = GalleryDialogFragment.newInstance().apply {
            this.projectManager = this@MainActivity.projectManager
            this.listener = this@MainActivity
        }
        galleryDialog.show(supportFragmentManager, GalleryDialogFragment.TAG)
    }

    private fun promptNewPainting() {
        // Flush current active project first
        autoSaveManager.flushSaveSync()

        val input = EditText(this).apply {
            hint = "Painting Title (optional)"
        }

        AlertDialog.Builder(this)
            .setTitle("New Painting")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val title = input.text.toString().trim().takeIf { it.isNotEmpty() } ?: "Untitled Painting"
                lifecycleScope.launch(ioDispatcher) {
                    val newProject = projectManager.createNewProject(1024, 1024, title)
                    withContext(Dispatchers.Main) {
                        loadProjectIntoEditor(newProject.id)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- GalleryDialogFragment.Listener Implementation ---

    override fun onProjectSelected(project: PaintingProject) {
        if (currentProject?.id != project.id) {
            autoSaveManager.flushSaveSync()
            loadProjectIntoEditor(project.id)
        }
    }

    override fun onNewPaintingRequested() {
        promptNewPainting()
    }

    override fun onProjectDeleted(deletedProject: PaintingProject) {
        if (currentProject?.id == deletedProject.id) {
            autoSaveManager.cancelAutoSave()
            // Deleted project was active! Find next active or create new
            lifecycleScope.launch(ioDispatcher) {
                val nextActive = projectManager.getActiveProject()
                if (nextActive != null) {
                    withContext(Dispatchers.Main) {
                        loadProjectIntoEditor(nextActive.id)
                    }
                } else {
                    val newProj = projectManager.createNewProject(1024, 1024, "Untitled Painting")
                    withContext(Dispatchers.Main) {
                        loadProjectIntoEditor(newProj.id)
                    }
                }
            }
        }
    }

    // --- MoreOptionsBottomSheet.Listener Implementation ---

    override fun onOpenGalleryClicked() {
        openGallery()
    }

    override fun onNewPaintingClicked() {
        promptNewPainting()
    }

    private fun showToolSettingsModal() {
        val sheet = ToolSettingsBottomSheet.newInstance(
            toolMode = drawView.toolMode,
            brushRadius = drawView.getBrushSize(),
            brushOpacity = drawView.brushOpacity,
            brushColor = drawView.brushColor,
            useReferenceColors = drawView.useReferenceColors
        ).apply {
            this.listener = this@MainActivity
        }
        sheet.show(supportFragmentManager, ToolSettingsBottomSheet.TAG)
    }

    private fun showColorSettingsModal() {
        val sheet = ColorSettingsBottomSheet.newInstance(
            brushColor = drawView.brushColor,
            useReferenceColors = drawView.useReferenceColors
        ).apply {
            this.listener = this@MainActivity
        }
        sheet.show(supportFragmentManager, ColorSettingsBottomSheet.TAG)
    }

    private fun showMoreOptionsModal() {
        val sheet = MoreOptionsBottomSheet.newInstance(
            opacityPercent = currentOpacityPercent,
            isSourceVisible = isSourceVisible,
            isCustomImageLoaded = isCustomImageLoaded,
            isRatchetEnabled = drawView.isRatchetEnabled
        )
        sheet.listener = this
        sheet.show(supportFragmentManager, MoreOptionsBottomSheet.TAG)
    }

    // --- ToolSettingsBottomSheet.Listener Implementation ---

    private fun formatToolStatus(toolMode: SmartDrawView.ToolMode, size: Float): String {
        val name = when (toolMode) {
            SmartDrawView.ToolMode.PAINTBRUSH -> "Paintbrush"
            SmartDrawView.ToolMode.PEN -> "Pen"
            SmartDrawView.ToolMode.SMUDGE -> "Smudge"
        }
        val sizeFormatted = if (size % 1.0f == 0f) "${size.toInt()} px" else "${String.format(Locale.US, "%.1f", size)} px"
        return "Tool: $name (size: $sizeFormatted)"
    }

    override fun onToolSelected(toolMode: SmartDrawView.ToolMode) {
        drawView.toolMode = toolMode
        currentProject?.activeTool = when (toolMode) {
            SmartDrawView.ToolMode.PAINTBRUSH -> "PAINTBRUSH"
            SmartDrawView.ToolMode.PEN -> "PEN"
            SmartDrawView.ToolMode.SMUDGE -> "SMUDGE"
        }
        currentProject?.brushSize = drawView.getBrushSize()
        currentProject?.brushOpacity = drawView.brushOpacity
        autoSaveManager.markDirty()
        updateActiveToolUI()
        statusText.text = formatToolStatus(toolMode, drawView.getBrushSize())
    }

    override fun onBrushSizeChanged(radius: Float) {
        drawView.setBrushSize(radius)
        when (drawView.toolMode) {
            SmartDrawView.ToolMode.PAINTBRUSH -> currentProject?.brushSizeBrush = radius
            SmartDrawView.ToolMode.PEN -> currentProject?.brushSizePen = radius
            SmartDrawView.ToolMode.SMUDGE -> currentProject?.brushSizeSmudge = radius
        }
        currentProject?.brushSize = radius
        autoSaveManager.markDirty()
        statusText.text = formatToolStatus(drawView.toolMode, radius)
    }

    override fun onBrushOpacityChanged(opacity: Float) {
        drawView.brushOpacity = opacity
        when (drawView.toolMode) {
            SmartDrawView.ToolMode.PAINTBRUSH -> currentProject?.brushOpacityBrush = opacity
            SmartDrawView.ToolMode.PEN -> currentProject?.brushOpacityPen = opacity
            SmartDrawView.ToolMode.SMUDGE -> currentProject?.brushOpacitySmudge = opacity
        }
        currentProject?.brushOpacity = opacity
        autoSaveManager.markDirty()
        statusText.text = "Brush opacity: ${(opacity * 100f).roundToInt()}%"
    }

    override fun getToolRadius(toolMode: SmartDrawView.ToolMode): Float {
        return drawView.getToolRadius(toolMode)
    }

    override fun getToolOpacity(toolMode: SmartDrawView.ToolMode): Float {
        return drawView.getToolOpacity(toolMode)
    }

    // --- ColorSettingsBottomSheet.Listener Implementation ---

    override fun onBrushColorChanged(color: Int) {
        drawView.brushColor = color
        currentProject?.manualBrushColor = color
        autoSaveManager.markDirty()
        updateColorUI()
        val hex = ColorSelectionUtil.colorToHex(color)
        statusText.text = "Brush color: $hex"
    }

    override fun onAutoColorToggled(enabled: Boolean) {
        drawView.useReferenceColors = enabled
        currentProject?.useReferenceColors = enabled
        autoSaveManager.markDirty()
        updateColorUI()
        statusText.text = if (enabled) "Auto color sampling enabled" else "Manual color mode"
    }

    override fun onEyedropperRequested() {
        drawView.isEyedropperActive = true
        updateEyedropperUI()
        statusText.text = "Eyedropper active: Tap on canvas to sample color"
        Toast.makeText(this, "Tap or drag on canvas to pick a color", Toast.LENGTH_SHORT).show()
    }

    override fun onChangeSourceImageClicked() {
        pickImageLauncher.launch("image/*")
    }

    override fun onOpacityChanged(opacityPercent: Int) {
        currentOpacityPercent = opacityPercent
        val opacity = opacityPercent / 100f
        drawView.setReferenceOpacity(opacity)
        currentProject?.referenceOpacity = opacity
        if (opacityPercent > 0 && !isSourceVisible) {
            isSourceVisible = true
            drawView.setReferenceVisible(true)
            currentProject?.isReferenceVisible = true
        }
        autoSaveManager.markDirty()
        statusText.text = "Source image opacity: $opacityPercent%"
    }

    override fun onToggleSourceVisibilityClicked(currentlyVisible: Boolean) {
        isSourceVisible = currentlyVisible
        drawView.setReferenceVisible(isSourceVisible)
        currentProject?.isReferenceVisible = isSourceVisible
        autoSaveManager.markDirty()
        statusText.text = if (isSourceVisible) "Source image visible" else "Source image hidden"
    }

    override fun onRatchetModeToggled(enabled: Boolean) {
        drawView.setRatchetEnabled(enabled)
        currentProject?.isRatchetEnabled = enabled
        autoSaveManager.markDirty()
        statusText.text = if (enabled) "Ratchet mode enabled" else "Ratchet mode disabled"
    }

    override fun onClearPaintingClicked() {
        AlertDialog.Builder(this)
            .setTitle("Clear Canvas")
            .setMessage("Are you sure you want to clear your painting? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                drawView.clearCanvas()
                autoSaveManager.markDirty()
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
        when (drawView.toolMode) {
            SmartDrawView.ToolMode.PAINTBRUSH -> {
                btnActiveTool.setImageResource(R.drawable.ic_brush)
                btnActiveTool.contentDescription = "Active Tool: Paintbrush (Tap for settings)"
            }
            SmartDrawView.ToolMode.PEN -> {
                btnActiveTool.setImageResource(R.drawable.ic_pen)
                btnActiveTool.contentDescription = "Active Tool: Pen (Tap for settings)"
            }
            SmartDrawView.ToolMode.SMUDGE -> {
                btnActiveTool.setImageResource(R.drawable.ic_smudge)
                btnActiveTool.contentDescription = "Active Tool: Smudge (Tap for settings)"
            }
        }
    }

    private fun updateColorUI() {
        if (drawView.useReferenceColors) {
            btnColorSettings.setImageResource(R.drawable.ic_auto_color)
            ImageViewCompat.setImageTintList(btnColorSettings, ColorStateList.valueOf(Color.WHITE))
            btnColorSettings.imageTintList = ColorStateList.valueOf(Color.WHITE)
            btnColorSettings.colorFilter = null
            btnColorSettings.clearColorFilter()
            btnColorSettings.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnColorSettings.contentDescription = "Color: Auto Color Sampling (Tap for color settings)"
        } else {
            val density = resources.displayMetrics.density
            val sizePx = (24 * density).roundToInt()
            val strokePx = (1.5f * density).roundToInt().coerceAtLeast(1)
            val luminance = ColorUtils.calculateLuminance(drawView.brushColor)
            val strokeColor = if (luminance < 0.25) {
                Color.parseColor("#CCCCCC")
            } else {
                Color.parseColor("#333333")
            }
            val swatch = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(drawView.brushColor)
                setStroke(strokePx, strokeColor)
                setSize(sizePx, sizePx)
            }
            ImageViewCompat.setImageTintList(btnColorSettings, null)
            btnColorSettings.imageTintList = null
            btnColorSettings.colorFilter = null
            btnColorSettings.clearColorFilter()
            btnColorSettings.setImageDrawable(swatch)
            btnColorSettings.setBackgroundResource(R.drawable.btn_icon_selector)
            val hex = ColorSelectionUtil.colorToHex(drawView.brushColor)
            btnColorSettings.contentDescription = "Color: $hex (Tap for color settings)"
        }
    }

    private fun updateEyedropperUI() {
        if (drawView.isEyedropperActive) {
            btnEyedropper.setBackgroundResource(R.drawable.bg_tool_button_active)
            btnEyedropper.setColorFilter(Color.WHITE)
        } else {
            btnEyedropper.setBackgroundResource(R.drawable.btn_icon_selector)
            btnEyedropper.setColorFilter(0xFFB0B0B0.toInt())
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnActiveTool.isEnabled = enabled
        btnColorSettings.isEnabled = enabled
        btnEyedropper.isEnabled = enabled
        btnResetView.isEnabled = enabled
        btnGallery.isEnabled = enabled
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
                    currentReferenceBitmap?.recycle()
                    currentReferenceBitmap = finalBitmap

                    drawView.setCanvasDimensions(targetW, targetH)
                    drawView.setReferenceImage(finalBitmap)
                    drawView.useReferenceColors = true
                    updateColorUI()

                    currentProject?.let { p ->
                        p.canvasWidth = targetW
                        p.canvasHeight = targetH
                        p.useReferenceColors = true
                    }
                    autoSaveManager.markDirty()

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
                    autoSaveManager.markDirty()
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
