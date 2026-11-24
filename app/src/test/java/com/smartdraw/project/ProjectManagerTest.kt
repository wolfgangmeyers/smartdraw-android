package com.smartdraw.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectManagerTest {

    private lateinit var context: Context
    private lateinit var projectManager: ProjectManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clean up filesDir
        val projectsDir = File(context.filesDir, "projects")
        if (projectsDir.exists()) {
            projectsDir.deleteRecursively()
        }
        projectManager = ProjectManager(context)
    }

    @Test
    fun testInitialStateHasNoProjects() {
        assertNull(projectManager.getActiveProject())
        assertTrue(projectManager.getAllProjects().isEmpty())
    }

    @Test
    fun testCreateNewProjectCreatesValidFolderAndFiles() {
        val project = projectManager.createNewProject(800, 600, "Sunset Sketch")

        assertNotNull(project)
        assertEquals("Sunset Sketch", project.title)
        assertEquals(800, project.canvasWidth)
        assertEquals(600, project.canvasHeight)

        // Verify folder and files on disk
        val projectDir = projectManager.getProjectDir(project.id)
        assertTrue(projectDir.exists())
        assertTrue(projectDir.isDirectory)

        val paintingFile = File(projectDir, "painting.png")
        val thumbnailFile = File(projectDir, "thumbnail.png")
        val metadataFile = File(projectDir, "metadata.json")

        assertTrue("painting.png must exist", paintingFile.exists())
        assertTrue("thumbnail.png must exist", thumbnailFile.exists())
        assertTrue("metadata.json must exist", metadataFile.exists())

        // Verify active project pointer
        val active = projectManager.getActiveProject()
        assertNotNull(active)
        assertEquals(project.id, active?.id)
        assertEquals("Sunset Sketch", active?.title)
    }

    @Test
    fun testSaveAndLoadProjectWithReferenceImage() {
        val project = projectManager.createNewProject(512, 512, "Study 1")

        // Create dummy base bitmap and reference bitmap
        val baseBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setPixel(10, 10, Color.RED)
        }
        val refBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            setPixel(20, 20, Color.BLUE)
        }

        project.brushSize = 35.0f
        project.activeTool = "SMUDGE"
        project.referenceOpacity = 0.65f
        project.isReferenceVisible = true
        project.useReferenceColors = false
        project.isRatchetEnabled = true

        projectManager.saveProject(
            project = project,
            baseBitmap = baseBmp,
            referenceBitmap = refBmp,
            isDirty = true
        )

        // Load project
        val loaded = projectManager.loadProject(project.id)
        assertNotNull(loaded.project)
        assertEquals(35.0f, loaded.project.brushSize, 0.001f)
        assertEquals("SMUDGE", loaded.project.activeTool)
        assertEquals(0.65f, loaded.project.referenceOpacity, 0.001f)
        assertFalse(loaded.project.useReferenceColors)
        assertTrue(loaded.project.isRatchetEnabled)

        assertNotNull(loaded.baseBitmap)
        assertEquals(512, loaded.baseBitmap?.width)
        assertEquals(512, loaded.baseBitmap?.height)

        assertNotNull(loaded.referenceBitmap)
        assertEquals(512, loaded.referenceBitmap?.width)

        baseBmp.recycle()
        refBmp.recycle()
        loaded.baseBitmap?.recycle()
        loaded.referenceBitmap?.recycle()
    }

    @Test
    fun testThumbnailGenerationDimensions() {
        // Test high resolution bitmap downscaling <= 384
        val highResBmp = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888)
        val thumb = projectManager.createThumbnailBitmap(highResBmp)

        assertTrue(thumb.width <= 384)
        assertTrue(thumb.height <= 384)
        assertEquals(384, thumb.height)
        assertEquals(288, thumb.width) // 2400 * (384 / 3200) = 288

        highResBmp.recycle()
        thumb.recycle()
    }

    @Test
    fun testDeleteActiveProjectSwitchesToNextRecent() {
        val proj1 = projectManager.createNewProject(1024, 1024, "Project 1")
        Thread.sleep(20)
        val proj2 = projectManager.createNewProject(1024, 1024, "Project 2")

        assertEquals(proj2.id, projectManager.getActiveProjectId())

        // Delete active project (proj2)
        val deleted = projectManager.deleteProject(proj2.id)
        assertTrue(deleted)

        // proj1 should now become active
        val active = projectManager.getActiveProject()
        assertNotNull(active)
        assertEquals(proj1.id, active?.id)

        // Delete proj1 as well
        val deleted1 = projectManager.deleteProject(proj1.id)
        assertTrue(deleted1)
        assertNull(projectManager.getActiveProject())
        assertTrue(projectManager.getAllProjects().isEmpty())
    }

    @Test
    fun testCorruptedMetadataFallback() {
        val project = projectManager.createNewProject(640, 480, "Corruptible")
        val metaFile = File(projectManager.getProjectDir(project.id), "metadata.json")
        // Corrupt metadata.json
        metaFile.writeText("{ invalid json ...")

        val recovered = projectManager.getProject(project.id)
        assertNotNull(recovered)
        assertEquals(640, recovered?.canvasWidth)
        assertEquals(480, recovered?.canvasHeight)
    }

    @Test
    fun testManualBrushColorPersistenceAndDefault() {
        val project = projectManager.createNewProject(512, 512, "Color Test")
        assertEquals(Color.BLACK, project.manualBrushColor)

        val customColor = Color.rgb(255, 87, 34) // Deep Orange
        project.manualBrushColor = customColor

        val dummyBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        projectManager.saveProject(project, dummyBmp, null, isDirty = true)
        dummyBmp.recycle()

        val loaded = projectManager.loadProject(project.id)
        assertEquals(customColor, loaded.project.manualBrushColor)
    }

    @Test
    fun testBackwardsCompatibilityMissingManualBrushColor() {
        // Simulate an older project created without manualBrushColor in metadata.json
        val id = java.util.UUID.randomUUID().toString()
        val dir = projectManager.getProjectDir(id).apply { mkdirs() }
        val legacyJson = """
            {
              "id": "$id",
              "title": "Legacy Project",
              "canvasWidth": 800,
              "canvasHeight": 600,
              "createdAt": 1700000000000,
              "updatedAt": 1700000000000,
              "referenceOpacity": 0.5,
              "isReferenceVisible": true,
              "useReferenceColors": false,
              "isRatchetEnabled": false,
              "activeTool": "PENCIL",
              "brushSize": 20.0,
              "brushOpacity": 1.0
            }
        """.trimIndent()
        File(dir, "metadata.json").writeText(legacyJson)

        val project = projectManager.getProject(id)
        assertNotNull(project)
        assertEquals(Color.BLACK, project?.manualBrushColor)
        assertEquals(800, project?.canvasWidth)
        assertEquals(600, project?.canvasHeight)
    }

    @Test
    fun testLayerMetadataPersistence() {
        val project = projectManager.createNewProject(512, 512, "Layers Project")
        val layers = listOf(
            LayerMetadata(id = "layer-1", name = "Ink", opacity = 1.0f, isVisible = true),
            LayerMetadata(id = "layer-2", name = "Color", opacity = 0.75f, isVisible = false)
        )
        project.layers = layers
        project.activeLayerId = "layer-2"

        val dummyBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        projectManager.saveProject(project, dummyBmp, null, isDirty = true)
        dummyBmp.recycle()

        val loaded = projectManager.loadProject(project.id)
        assertNotNull(loaded.project)
        assertEquals(2, loaded.project.layers.size)
        assertEquals("Ink", loaded.project.layers[0].name)
        assertEquals(1.0f, loaded.project.layers[0].opacity, 0.001f)
        assertTrue(loaded.project.layers[0].isVisible)

        assertEquals("Color", loaded.project.layers[1].name)
        assertEquals(0.75f, loaded.project.layers[1].opacity, 0.001f)
        assertFalse(loaded.project.layers[1].isVisible)
        assertEquals("layer-2", loaded.project.activeLayerId)
    }

    @Test
    fun testPerLayerBitmapPersistenceAndOrphanCleanup() {
        val project = projectManager.createNewProject(256, 256, "PerLayer Test")
        val layer1Id = "layer-1"
        val layer2Id = "layer-2"
        val layer1 = LayerMetadata(id = layer1Id, name = "Background")
        val layer2 = LayerMetadata(id = layer2Id, name = "Foreground")
        project.layers = listOf(layer1, layer2)
        project.activeLayerId = layer2Id

        val baseBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixel(10, 10, Color.WHITE)
        }
        val layer1Bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixel(50, 50, Color.RED)
        }
        val layer2Bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            setPixel(100, 100, Color.BLUE)
        }

        projectManager.saveProject(
            project = project,
            baseBitmap = baseBmp,
            layerBitmaps = mapOf(layer1Id to layer1Bmp, layer2Id to layer2Bmp),
            referenceBitmap = null,
            isDirty = true
        )

        val projectDir = projectManager.getProjectDir(project.id)
        val l1File = File(projectDir, "layer_${layer1Id}.png")
        val l2File = File(projectDir, "layer_${layer2Id}.png")
        assertTrue("layer-1 file should exist", l1File.exists())
        assertTrue("layer-2 file should exist", l2File.exists())

        // Load and verify pixels
        val loaded = projectManager.loadProject(project.id)
        assertEquals(2, loaded.layerBitmaps.size)
        val loadedL1 = loaded.layerBitmaps[layer1Id]
        val loadedL2 = loaded.layerBitmaps[layer2Id]
        assertNotNull(loadedL1)
        assertNotNull(loadedL2)
        assertEquals(Color.RED, loadedL1?.getPixel(50, 50))
        assertEquals(Color.BLUE, loadedL2?.getPixel(100, 100))

        // Now remove layer-2 and verify orphan cleanup
        project.layers = listOf(layer1)
        project.activeLayerId = layer1Id
        projectManager.saveProject(
            project = project,
            baseBitmap = baseBmp,
            layerBitmaps = mapOf(layer1Id to layer1Bmp),
            referenceBitmap = null,
            isDirty = true
        )

        assertTrue("layer-1 file should still exist", l1File.exists())
        assertFalse("layer-2 file should have been cleaned up as an orphan", l2File.exists())

        val reloaded = projectManager.loadProject(project.id)
        assertEquals(1, reloaded.layerBitmaps.size)
        assertTrue(reloaded.layerBitmaps.containsKey(layer1Id))
        assertFalse(reloaded.layerBitmaps.containsKey(layer2Id))

        baseBmp.recycle()
        layer1Bmp.recycle()
        layer2Bmp.recycle()
    }
}
