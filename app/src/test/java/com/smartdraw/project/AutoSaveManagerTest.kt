package com.smartdraw.project

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoSaveManagerTest {

    private lateinit var context: Context
    private lateinit var projectManager: ProjectManager
    private lateinit var autoSaveManager: AutoSaveManager
    private var testProject: PaintingProject? = null
    private var baseBitmap: Bitmap? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val projectsDir = File(context.filesDir, "projects")
        if (projectsDir.exists()) {
            projectsDir.deleteRecursively()
        }
        projectManager = ProjectManager(context)
        testProject = projectManager.createNewProject(256, 256, "AutoSave Test")

        baseBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)

        autoSaveManager = AutoSaveManager(
            projectManager = projectManager,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            provider = object : AutoSaveManager.ProjectDataProvider {
                override fun getCurrentProject(): PaintingProject? = testProject
                override fun requestBaseBitmap(onBitmapReady: (Bitmap) -> Unit) {
                    onBitmapReady(baseBitmap!!.copy(baseBitmap!!.config!!, true))
                }
                override fun getReferenceBitmap(): Bitmap? = null
            },
            ioDispatcher = Dispatchers.Default
        )
    }

    @Test
    fun testMarkDirtyAndDebounceTriggersSave() {
        assertFalse(autoSaveManager.isDirty)

        autoSaveManager.markDirty()
        assertTrue(autoSaveManager.isDirty)

        // Advance looper past 5 seconds (5000ms)
        ShadowLooper.idleMainLooper(5100, TimeUnit.MILLISECONDS)

        // Wait for background IO save to complete
        var attempts = 0
        while (autoSaveManager.isDirty && attempts < 50) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            attempts++
        }

        // After execution, isDirty is cleared
        assertFalse(autoSaveManager.isDirty)
    }

    @Test
    fun testSubsequentEventsResetDebounceCountdown() {
        autoSaveManager.markDirty()
        assertTrue(autoSaveManager.isDirty)

        // Advance 3 seconds
        ShadowLooper.idleMainLooper(3000, TimeUnit.MILLISECONDS)
        assertTrue(autoSaveManager.isDirty) // Not saved yet

        // New event pushes debounce back by 5s
        autoSaveManager.markDirty()

        // Advance another 3 seconds (total 6s from start, but only 3s from 2nd event)
        ShadowLooper.idleMainLooper(3000, TimeUnit.MILLISECONDS)
        assertTrue(autoSaveManager.isDirty) // Still not saved

        // Advance another 2.5 seconds
        ShadowLooper.idleMainLooper(2500, TimeUnit.MILLISECONDS)

        var attempts = 0
        while (autoSaveManager.isDirty && attempts < 50) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            attempts++
        }

        assertFalse(autoSaveManager.isDirty) // Now saved!
    }

    @Test
    fun testFlushSaveSyncImmediatelySaves() {
        testProject?.brushSize = 55f
        autoSaveManager.markDirty()
        assertTrue(autoSaveManager.isDirty)

        autoSaveManager.flushSaveSync()
        assertFalse(autoSaveManager.isDirty)

        // Verify project on disk was updated
        val loaded = projectManager.loadProject(testProject!!.id)
        assertEquals(55f, loaded.project.brushSize, 0.001f)
    }
}
