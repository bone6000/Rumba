package com.example.roombalocal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja el recorrido del robot durante la limpieza a partir de la posición (x, y)
 * que publica el propio robot. Se ajusta solo al tamaño del recorrido.
 */
class MapView(context: Context) : View(context) {

    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private var lastX = 0f
    private var lastY = 0f
    private var theta = 0f
    private var hasPos = false

    private val density = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 200
    }
    private val dockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.OK
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val robotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E8F0.toInt()
        style = Paint.Style.FILL
    }
    private val robotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ON_ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textSize = 14f * sp
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    fun reset() {
        xs.clear()
        ys.clear()
        hasPos = false
        invalidate()
    }

    fun update(x: Float, y: Float, thetaDeg: Float) {
        theta = thetaDeg
        lastX = x
        lastY = y
        hasPos = true
        val n = xs.size
        if (n == 0 || hypot(x - xs[n - 1], y - ys[n - 1]) > 3f) {
            xs.add(x)
            ys.add(y)
            if (xs.size > 6000) {
                xs.removeAt(0)
                ys.removeAt(0)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // cuadrícula de puntos
        val step = 24f * density
        var gx = step / 2
        while (gx < w) {
            var gy = step / 2
            while (gy < h) {
                canvas.drawCircle(gx, gy, 1.5f * density, gridPaint)
                gy += step
            }
            gx += step
        }

        if (!hasPos || xs.isEmpty()) {
            val lines = listOf("El mapa aparece cuando", "el robot empieza a limpiar")
            var ty = h / 2 - 8f * density
            for (l in lines) {
                canvas.drawText(l, w / 2, ty, textPaint)
                ty += 20f * density
            }
            return
        }

        val minX = min(xs.min(), lastX)
        val maxX = max(xs.max(), lastX)
        val minY = min(ys.min(), lastY)
        val maxY = max(ys.max(), lastY)
        val minSpan = 300f
        val spanX = max(maxX - minX, minSpan)
        val spanY = max(maxY - minY, minSpan)
        val pad = 32f * density
        val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2

        fun sx(x: Float) = w / 2 + (x - cx) * scale
        fun sy(y: Float) = h / 2 - (y - cy) * scale

        path.reset()
        path.moveTo(sx(xs[0]), sy(ys[0]))
        for (i in 1 until xs.size) path.lineTo(sx(xs[i]), sy(ys[i]))
        path.lineTo(sx(lastX), sy(lastY))
        canvas.drawPath(path, trailPaint)

        // punto de partida (base)
        canvas.drawCircle(sx(xs[0]), sy(ys[0]), 8f * density, dockPaint)

        // robot
        val rx = sx(lastX)
        val ry = sy(lastY)
        val r = 11f * density
        canvas.drawCircle(rx, ry, r, robotFill)
        canvas.drawCircle(rx, ry, r, robotRing)
        val a = Math.toRadians(theta.toDouble())
        canvas.drawLine(
            rx, ry,
            rx + (cos(a) * r * 0.8).toFloat(),
            ry - (sin(a) * r * 0.8).toFloat(),
            arrowPaint
        )
    }
}
