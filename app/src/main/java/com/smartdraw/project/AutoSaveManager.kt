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
        fun requestBaseBitmap(onBitmapReady: (Bitmap) -> Unit)
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

        provider.requestBaseBitmap { baseBitmap ->
            val refBitmap = provider.getReferenceBitmap()
            activeSaveJob = coroutineScope.launch(ioDispatcher) {
                try {
                    projectManager.saveProject(
                        project = project,
                        baseBitmap = baseBitmap,
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
                    baseBitmap.recycle()
                }
            }
        }
    }

    fun flushSaveSync() {
        cancelDebounceTimer()
        if (!isDirty) return

        val project = provider.getCurrentProject() ?: return

        var baseBmp: Bitmap? = null
        val lock = java.lang.Object()

        provider.requestBaseBitmap { bitmap ->
            synchronized(lock) {
                baseBmp = bitmap
                lock.notifyAll()
            }
        }

        synchronized(lock) {
            if (baseBmp == null) {
                try {
                    lock.wait(1500)
                } catch (ignored: InterruptedException) {}
            }
        }

        val bitmapToSave = baseBmp ?: return
        val refBitmap = provider.getReferenceBitmap()

        try {
            runBlocking(ioDispatcher) {
                projectManager.saveProject(
                    project = project,
                    baseBitmap = bitmapToSave,
                    referenceBitmap = refBitmap,
                    isDirty = true
                )
            }
            isDirty = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in flushSaveSync", e)
        } finally {
            bitmapToSave.recycle()
        }
    }
}
