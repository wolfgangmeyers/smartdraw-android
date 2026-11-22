package com.smartdraw.harness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

object ReferenceImageGenerator {

    fun createRadialGradient(width: Int = 1024, height: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val colors = intArrayOf(
            Color.rgb(255, 230, 100), // Bright Gold Center
            Color.rgb(255, 60, 120),  // Vivid Pink/Magenta
            Color.rgb(130, 40, 220),  // Deep Violet
            Color.rgb(20, 180, 240),  // Cyan
            Color.rgb(10, 30, 80)     // Midnight Navy
        )
        val stops = floatArrayOf(0.0f, 0.3f, 0.55f, 0.8f, 1.0f)

        val shader = RadialGradient(
            width * 0.5f, height * 0.5f,
            width * 0.65f,
            colors, stops,
            Shader.TileMode.CLAMP
        )

        val paint = Paint().apply { this.shader = shader }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Overlay accent swirl circles
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeWidth = 24f
        accentPaint.color = Color.argb(140, 255, 255, 255)
        canvas.drawCircle(width * 0.5f, height * 0.5f, width * 0.25f, accentPaint)
        accentPaint.strokeWidth = 12f
        accentPaint.color = Color.argb(120, 0, 255, 200)
        canvas.drawCircle(width * 0.5f, height * 0.5f, width * 0.38f, accentPaint)

        return bitmap
    }

    fun createGeometricAbstract(width: Int = 1024, height: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.rgb(245, 242, 235)) // Warm ivory paper

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // Bold geometric blocks
        paint.color = Color.rgb(220, 40, 35) // Vermilion
        canvas.drawRect(width * 0.1f, height * 0.1f, width * 0.55f, height * 0.5f, paint)

        paint.color = Color.rgb(25, 75, 175) // Cobalt Blue
        canvas.drawRect(width * 0.6f, height * 0.15f, width * 0.9f, height * 0.75f, paint)

        paint.color = Color.rgb(250, 195, 25) // Saffron Yellow
        canvas.drawCircle(width * 0.35f, height * 0.72f, width * 0.18f, paint)

        paint.color = Color.rgb(30, 150, 110) // Emerald Teal
        canvas.drawRect(width * 0.05f, height * 0.65f, width * 0.28f, height * 0.92f, paint)

        paint.color = Color.rgb(240, 120, 30) // Orange wedge
        canvas.drawArc(
            width * 0.45f, height * 0.45f,
            width * 0.85f, height * 0.85f,
            180f, 90f, true, paint
        )

        // Geometric divider lines
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 35)
            strokeWidth = 14f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(width * 0.58f, 0f, width * 0.58f, height.toFloat(), linePaint)
        canvas.drawLine(0f, height * 0.55f, width.toFloat(), height * 0.55f, linePaint)

        return bitmap
    }

    fun createLandscapeSunset(width: Int = 1024, height: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Sky gradient
        val skyShader = LinearGradient(
            0f, 0f, 0f, height * 0.65f,
            intArrayOf(
                Color.rgb(40, 20, 90),   // Violet twilight
                Color.rgb(180, 45, 80),  // Crimson
                Color.rgb(245, 130, 40), // Orange
                Color.rgb(255, 220, 90)  // Golden horizon
            ),
            floatArrayOf(0.0f, 0.4f, 0.75f, 1.0f),
            Shader.TileMode.CLAMP
        )
        val paint = Paint().apply { shader = skyShader }
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.65f, paint)

        // Glowing Sun
        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 250, 200)
            style = Paint.Style.FILL
            setShadowLayer(50f, 0f, 0f, Color.rgb(255, 180, 50))
        }
        canvas.drawCircle(width * 0.5f, height * 0.55f, width * 0.12f, sunPaint)

        // Distant mountains
        val mountainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 25, 70)
            style = Paint.Style.FILL
        }
        val path = android.graphics.Path().apply {
            moveTo(0f, height * 0.65f)
            lineTo(width * 0.25f, height * 0.48f)
            lineTo(width * 0.5f, height * 0.60f)
            lineTo(width * 0.75f, height * 0.45f)
            lineTo(width.toFloat(), height * 0.65f)
            close()
        }
        canvas.drawPath(path, mountainPaint)

        // Water reflection
        val waterShader = LinearGradient(
            0f, height * 0.65f, 0f, height.toFloat(),
            intArrayOf(
                Color.rgb(220, 110, 50),
                Color.rgb(80, 30, 80),
                Color.rgb(20, 15, 50)
            ),
            floatArrayOf(0.0f, 0.4f, 1.0f),
            Shader.TileMode.CLAMP
        )
        paint.shader = waterShader
        canvas.drawRect(0f, height * 0.65f, width.toFloat(), height.toFloat(), paint)

        // Water reflection streaks
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 210, 100)
            strokeWidth = 6f
        }
        for (i in 0..20) {
            val y = height * 0.67f + i * (height * 0.015f)
            val spread = (i * 12f)
            val x1 = (width * 0.5f - spread).coerceAtLeast(0f)
            val x2 = (width * 0.5f + spread).coerceAtMost(width.toFloat())
            canvas.drawLine(x1, y, x2, y, streakPaint)
        }

        return bitmap
    }
}
