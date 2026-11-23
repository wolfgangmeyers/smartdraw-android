package com.smartdraw.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class ProjectManager(private val context: Context) {

    companion object {
        private const val TAG = "ProjectManager"
        const val MAX_THUMBNAIL_DIMENSION = 384
        private const val PROJECTS_DIR = "projects"
        private const val ACTIVE_PROJECT_FILE = "active_project.json"
        private const val METADATA_FILE = "metadata.json"
        private const val PAINTING_FILE = "painting.png"
        private const val SOURCE_FILE = "source.png"
        private const val THUMBNAIL_FILE = "thumbnail.png"
    }

    private val baseProjectsDir: File
        get() = File(context.filesDir, PROJECTS_DIR).apply {
            if (!exists()) mkdirs()
        }

    private val activeProjectPointerFile: File
        get() = File(baseProjectsDir, ACTIVE_PROJECT_FILE)

    fun getProjectDir(id: String): File {
        return File(baseProjectsDir, id)
    }

    fun getActiveProject(): PaintingProject? {
        val activeId = getActiveProjectId()
        if (activeId != null) {
            val project = getProject(activeId)
            if (project != null) return project
        }

        // Fallback: Scan projects and find the most recently updated one
        val allProjects = getAllProjects()
        val mostRecent = allProjects.firstOrNull()
        if (mostRecent != null) {
            setActiveProject(mostRecent.id)
            return mostRecent
        }

        return null
    }

    fun getActiveProjectId(): String? {
        val file = activeProjectPointerFile
        if (!file.exists()) return null
        return try {
            val jsonStr = file.readText()
            val json = JSONObject(jsonStr)
            json.optString("activeProjectId").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading active_project.json", e)
            null
        }
    }

    fun setActiveProject(id: String) {
        val file = activeProjectPointerFile
        val json = JSONObject().apply {
            put("activeProjectId", id)
        }
        atomicWriteText(file, json.toString(2))
    }

    fun getProject(id: String): PaintingProject? {
        val projectDir = getProjectDir(id)
        if (!projectDir.exists() || !projectDir.isDirectory) return null

        val metaFile = File(projectDir, METADATA_FILE)
        if (metaFile.exists()) {
            try {
                return parseMetadata(metaFile.readText())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse metadata for project $id", e)
            }
        }

        // Defensive fallback: Corrupted metadata.json, infer dimensions from painting.png
        val paintingFile = File(projectDir, PAINTING_FILE)
        if (paintingFile.exists()) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(paintingFile.absolutePath, options)
            val w = if (options.outWidth > 0) options.outWidth else 1024
            val h = if (options.outHeight > 0) options.outHeight else 1024
            val fallbackProject = PaintingProject(
                id = id,
                title = "Recovered Painting",
                canvasWidth = w,
                canvasHeight = h,
                createdAt = paintingFile.lastModified(),
                updatedAt = paintingFile.lastModified()
            )
            // Rewrite metadata for consistency
            writeMetadata(fallbackProject)
            return fallbackProject
        }

        return null
    }

    fun getAllProjects(): List<PaintingProject> {
        val dirs = baseProjectsDir.listFiles { file ->
            file.isDirectory && file.name != "." && file.name != ".."
        } ?: return emptyList()

        val projects = mutableListOf<PaintingProject>()
        for (dir in dirs) {
            val project = getProject(dir.name)
            if (project != null) {
                projects.add(project)
            }
        }

        // Sort descending by updatedAt
        projects.sortByDescending { it.updatedAt }
        return projects
    }

    fun createNewProject(
        width: Int = 1024,
        height: Int = 1024,
        title: String? = null
    ): PaintingProject {
        val id = UUID.randomUUID().toString()
        val projectDir = getProjectDir(id)
        if (!projectDir.exists()) {
            projectDir.mkdirs()
        }

        val now = System.currentTimeMillis()
        val project = PaintingProject(
            id = id,
            title = title ?: "Untitled Painting",
            canvasWidth = width,
            canvasHeight = height,
            createdAt = now,
            updatedAt = now
        )

        // Generate baseline blank white canvas bitmap and thumbnail
        val blankBitmap = createBlankBitmap(width, height)
        val thumbBitmap = createThumbnailBitmap(blankBitmap)

        saveProject(project, blankBitmap, null, isDirty = true)

        blankBitmap.recycle()
        thumbBitmap.recycle()

        setActiveProject(id)
        return project
    }

    fun saveProject(
        project: PaintingProject,
        baseBitmap: Bitmap,
        referenceBitmap: Bitmap?,
        isDirty: Boolean = true
    ) {
        val projectDir = getProjectDir(project.id)
        if (!projectDir.exists()) {
            projectDir.mkdirs()
        }

        if (isDirty) {
            project.updatedAt = System.currentTimeMillis()
        }

        // 1. Write painting.png atomically
        val paintingFile = File(projectDir, PAINTING_FILE)
        saveBitmapAtomically(paintingFile, baseBitmap)

        // 2. Write source.png (if reference bitmap exists, else delete if removed)
        val sourceFile = File(projectDir, SOURCE_FILE)
        if (referenceBitmap != null) {
            saveBitmapAtomically(sourceFile, referenceBitmap)
        } else if (sourceFile.exists()) {
            sourceFile.delete()
        }

        // 3. Generate & write thumbnail.png atomically (max dimension <= 384)
        val thumbBitmap = createThumbnailBitmap(baseBitmap)
        val thumbFile = File(projectDir, THUMBNAIL_FILE)
        saveBitmapAtomically(thumbFile, thumbBitmap)
        if (thumbBitmap != baseBitmap) {
            thumbBitmap.recycle()
        }

        // 4. Write metadata.json atomically
        writeMetadata(project)
    }

    fun loadProject(id: String): LoadedProjectData {
        val project = getProject(id) ?: throw IllegalArgumentException("Project not found: $id")
        val projectDir = getProjectDir(id)

        val paintingFile = File(projectDir, PAINTING_FILE)
        val baseBitmap = if (paintingFile.exists()) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            BitmapFactory.decodeFile(paintingFile.absolutePath, opts)
        } else null

        val sourceFile = File(projectDir, SOURCE_FILE)
        val refBitmap = if (sourceFile.exists()) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            BitmapFactory.decodeFile(sourceFile.absolutePath, opts)
        } else null

        return LoadedProjectData(project, baseBitmap, refBitmap)
    }

    fun deleteProject(id: String): Boolean {
        val projectDir = getProjectDir(id)
        val wasActive = getActiveProjectId() == id

        val deleted = deleteRecursively(projectDir)

        if (wasActive) {
            // Find next most recent project
            val remaining = getAllProjects()
            if (remaining.isNotEmpty()) {
                setActiveProject(remaining.first().id)
            } else {
                // Active file points to nothing or gets cleared
                if (activeProjectPointerFile.exists()) {
                    activeProjectPointerFile.delete()
                }
            }
        }

        return deleted
    }

    private fun deleteRecursively(fileOrDir: File): Boolean {
        if (!fileOrDir.exists()) return true
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
        }
        return fileOrDir.delete()
    }

    private fun writeMetadata(project: PaintingProject) {
        val projectDir = getProjectDir(project.id)
        val metaFile = File(projectDir, METADATA_FILE)
        val json = JSONObject().apply {
            put("id", project.id)
            put("title", project.title)
            put("canvasWidth", project.canvasWidth)
            put("canvasHeight", project.canvasHeight)
            put("createdAt", project.createdAt)
            put("updatedAt", project.updatedAt)
            put("referenceOpacity", project.referenceOpacity.toDouble())
            put("isReferenceVisible", project.isReferenceVisible)
            put("useReferenceColors", project.useReferenceColors)
            put("isRatchetEnabled", project.isRatchetEnabled)
            put("activeTool", project.activeTool)
            put("brushSize", project.brushSize.toDouble())
            put("brushOpacity", project.brushOpacity.toDouble())
            put("manualBrushColor", project.manualBrushColor)
        }
        atomicWriteText(metaFile, json.toString(2))
    }

    private fun parseMetadata(jsonStr: String): PaintingProject {
        val json = JSONObject(jsonStr)
        return PaintingProject(
            id = json.getString("id"),
            title = json.optString("title", "Untitled Painting"),
            canvasWidth = json.optInt("canvasWidth", 1024),
            canvasHeight = json.optInt("canvasHeight", 1024),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            referenceOpacity = json.optDouble("referenceOpacity", 0.3).toFloat(),
            isReferenceVisible = json.optBoolean("isReferenceVisible", true),
            useReferenceColors = json.optBoolean("useReferenceColors", true),
            isRatchetEnabled = json.optBoolean("isRatchetEnabled", false),
            activeTool = json.optString("activeTool", "PENCIL"),
            brushSize = json.optDouble("brushSize", 20.0).toFloat(),
            brushOpacity = json.optDouble("brushOpacity", 1.0).toFloat(),
            manualBrushColor = json.optInt("manualBrushColor", Color.BLACK)
        )
    }

    private fun saveBitmapAtomically(targetFile: File, bitmap: Bitmap) {
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileOutputStream(tmpFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.fd.sync()
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tmpFile.renameTo(targetFile)) {
            // Fallback copy if renameTo fails across file systems
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun atomicWriteText(targetFile: File, text: String) {
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
        }
    }

    fun createThumbnailBitmap(source: Bitmap): Bitmap {
        val maxDim = max(source.width, source.height)
        if (maxDim <= MAX_THUMBNAIL_DIMENSION) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        }
        val scale = MAX_THUMBNAIL_DIMENSION.toFloat() / maxDim
        val thumbW = max(1, (source.width * scale).roundToInt())
        val thumbH = max(1, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, thumbW, thumbH, true)
    }

    private fun createBlankBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        return bitmap
    }
}
