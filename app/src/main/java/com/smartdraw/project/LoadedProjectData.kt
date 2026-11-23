package com.smartdraw.project

import android.graphics.Bitmap

data class LoadedProjectData(
    val project: PaintingProject,
    val baseBitmap: Bitmap?,
    val referenceBitmap: Bitmap?,
    val layerBitmaps: Map<String, Bitmap> = emptyMap()
)
