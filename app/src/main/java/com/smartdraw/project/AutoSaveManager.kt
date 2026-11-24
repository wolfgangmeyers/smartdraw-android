package com.smartdraw.project

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AutoSaveManager(
    private val projectManager: ProjectManager,
    private val coroutineScope: CoroutineScope,
    private val provider: ProjectDataProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    interface ProjectDataProvider {
        fun getCurrentProject(): PaintingProject?
        fun requestSaveData(onDataReady: (compositeBitmap: Bitmap, layerBitmaps: Map<String, Bitmap>) -> Unit) {
            requestBaseBitmap { bmp ->
                onDataReady(bmp, emptyMap())
            }
        }
        fun requestBaseBitmap(onBitmapReady: (Bitmap) -> Unit) {}
        fun getReferenceBitmap(): Bitmap?
    }

    companion object {
        private const val TAG = "AutoSaveManager"
        const val DEBOUNCE_DELAY_MS = 5000L
    }

    var isDirty: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    var activeSaveJob: Job? = null
        private set

    private val autoSaveRunnable = Runnable {
        if (isDirty) {
            triggerSaveAsync()
        }
    }

    fun markDirty() {
        isDirty = true
        resetDebounceTimer()
    }

    fun resetDebounceTimer() {
        handler.removeCallbacks(autoSaveRunnable)
        handler.postDelayed(autoSaveRunnable, DEBOUNCE_DELAY_MS)
    }

    fun cancelDebounceTimer() {
        handler.removeCallbacks(autoSaveRunnable)
    }

    fun cancelAutoSave() {
        cancelDebounceTimer()
        activeSaveJob?.cancel()
        activeSaveJob = null
        isDirty = false
    }

    fun triggerSaveAsync(onComplete: (() -> Unit)? = null) {
        val project = provider.getCurrentProject() ?: return
        if (!isDirty) {
            onComplete?.invoke()
            return
        }

        cancelDebounceTimer()

        provider.requestSaveData { compositeBitmap, layerBitmaps ->
            val refBitmap = provider.getReferenceBitmap()
            activeSaveJob = coroutineScope.launch(ioDispatcher) {
                try {
                    projectManager.saveProject(
                        project = project,
                        baseBitmap = compositeBitmap,
                        layerBitmaps = layerBitmaps,
                        referenceBitmap = refBitmap,
                        isDirty = true
                    )
                    withContext(Dispatchers.Main) {
                        isDirty = false
                        onComplete?.invoke()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during async auto-save", e)
                } finally {
                    if (!compositeBitmap.isRecycled) {
                        compositeBitmap.recycle()
                    }
                    layerBitmaps.values.forEach { bmp ->
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
            }
        }
    }

    fun flushSaveSync() {
        cancelDebounceTimer()
        if (!isDirty) return

        val project = provider.getCurrentProject() ?: return

        var compBmp: Bitmap? = null
        var layersMap: Map<String, Bitmap>? = null
        val lock = java.lang.Object()

        provider.requestSaveData { composite, layers ->
            synchronized(lock) {
                compBmp = composite
                layersMap = layers
                lock.notifyAll()
            }
        }

        synchronized(lock) {
            if (compBmp == null) {
                try {
                    lock.wait(2000)
                } catch (ignored: InterruptedException) {}
            }
        }

        val bitmapToSave = compBmp ?: return
        val layerBitmapsToSave = layersMap ?: emptyMap()
        val refBitmap = provider.getReferenceBitmap()

        try {
            runBlocking(ioDispatcher) {
                projectManager.saveProject(
                    project = project,
                    baseBitmap = bitmapToSave,
                    layerBitmaps = layerBitmapsToSave,
                    referenceBitmap = refBitmap,
                    isDirty = true
                )
            }
            isDirty = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in flushSaveSync", e)
        } finally {
            if (!bitmapToSave.isRecycled) {
                bitmapToSave.recycle()
            }
            layerBitmapsToSave.values.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }
}
