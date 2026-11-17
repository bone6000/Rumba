package com.example.roombalocal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja el recorrido del robot en directo, el mapa guardado (celdas tenues),
 * lo cubierto en la última limpieza y las zonas con nombre.
 * Con editable = true se puede arrastrar el dedo para dibujar una zona nueva.
 */
class MapView(context: Context) : View(context) {

    interface ZoneListener {
        fun onZoneDrawn(x1: Float, y1: Float, x2: Float, y2: Float)
    }

    var editable = false
    var zoneListener: ZoneListener? = null

    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private var lastX = 0f
    private var lastY = 0f
    private var theta = 0f
    private var hasPos = false

    private var refCenters: List<P> = emptyList()
    private var coveredCenters: List<P> = emptyList()
    private var cellSize = 0f
    private var zones: List<Zone> = emptyList()
    private var coverage: Map<String, Int> = emptyMap()

    private var dragStart: P? = null
    private var dragNow: P? = null

    private var tScale = 1f
    private var tCx = 0f
    private var tCy = 0f

    private val density = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFFFFF
        style = Paint.Style.FILL
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3394A3B8
        style = Paint.Style.FILL
    }
    private val coveredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x6622D3EE
        style = Paint.Style.FILL
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x28FBBF24
        style = Paint.Style.FILL
    }
    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.WARN
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textSize = 12f * sp
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    // Franja del ancho del robot (~34 cm) por donde ha pasado
    private val swathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ACCENT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 70
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
    private val rect = RectF()

    // ---------- Datos ----------

    fun reset() {
        xs.clear()
        ys.clear()
        hasPos = false
        invalidate()
    }

    fun setTrail(points: List<P>) {
        xs.clear()
        ys.clear()
        for (p in points) {
            xs.add(p.x)
            ys.add(p.y)
        }
        if (points.isNotEmpty()) {
            lastX = points.last().x
            lastY = points.last().y
        }
        invalidate()
    }

    /** Coloca el robot sin añadir puntos al recorrido (el recorrido llega de MapStore). */
    fun setRobot(x: Float, y: Float, thetaDeg: Float) {
        theta = thetaDeg
        lastX = x
        lastY = y
        hasPos = true
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

    fun setOverlay(
        ref: List<P>,
        cell: Float,
        covered: List<P>,
        zoneList: List<Zone>,
        cov: Map<String, Int>
    ) {
        refCenters = ref
        cellSize = cell
        coveredCenters = covered
        zones = zoneList
        coverage = cov
        invalidate()
    }

    // ---------- Transformación ----------

    private fun bounds(): FloatArray? {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var any = false
        fun add(x: Float, y: Float) {
            any = true
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        for (i in xs.indices) add(xs[i], ys[i])
        if (hasPos) add(lastX, lastY)
        for (c in refCenters) add(c.x, c.y)
        for (c in coveredCenters) add(c.x, c.y)
        for (z in zones) {
            add(z.x1, z.y1)
            add(z.x2, z.y2)
        }
        return if (any) floatArrayOf(minX, maxX, minY, maxY) else null
    }

    private fun updateTransform(): Boolean {
        val b = bounds() ?: return false
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        val minSpan = 300f
        val spanX = max(b[1] - b[0], minSpan)
        val spanY = max(b[3] - b[2], minSpan)
        val pad = 32f * density
        tScale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
        tCx = (b[0] + b[1]) / 2
        tCy = (b[2] + b[3]) / 2
        return true
    }

    private fun sx(x: Float) = width / 2f + (x - tCx) * tScale
    private fun sy(y: Float) = height / 2f - (y - tCy) * tScale

    // ---------- Dibujo ----------

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

        if (!updateTransform()) {
            val lines = if (editable) {
                listOf("Aún no hay mapa guardado", "Haz una limpieza completa con la app abierta")
            } else {
                listOf("El mapa aparece cuando", "el robot empieza a limpiar")
            }
            var ty = h / 2 - 8f * density
            for (l in lines) {
                canvas.drawText(l, w / 2, ty, textPaint)
                ty += 20f * density
            }
            return
        }

        // mapa guardado y lo cubierto en la última limpieza
        val cellPx = cellSize * tScale
        val r = cellPx * 0.25f
        if (cellPx > 0f) {
            for (c in refCenters) {
                rect.set(sx(c.x) - cellPx / 2, sy(c.y) - cellPx / 2, sx(c.x) + cellPx / 2, sy(c.y) + cellPx / 2)
                canvas.drawRoundRect(rect, r, r, refPaint)
            }
            for (c in coveredCenters) {
                rect.set(sx(c.x) - cellPx / 2, sy(c.y) - cellPx / 2, sx(c.x) + cellPx / 2, sy(c.y) + cellPx / 2)
                canvas.drawRoundRect(rect, r, r, coveredPaint)
            }
        }

        // zonas
        for (z in zones) {
            val left = sx(min(z.x1, z.x2))
            val right = sx(max(z.x1, z.x2))
            val top = sy(max(z.y1, z.y2))
            val bottom = sy(min(z.y1, z.y2))
            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, 8f * density, 8f * density, zoneFill)
            canvas.drawRoundRect(rect, 8f * density, 8f * density, zoneStroke)
            val cov = coverage[z.name]
            val label = if (cov != null && cov >= 0) "${z.name} · $cov %" else z.name
            canvas.drawText(label, left + 8f * density, top + 18f * density, zoneText)
        }

        // recorrido en directo
        if (xs.isNotEmpty()) {
            path.reset()
            path.moveTo(sx(xs[0]), sy(ys[0]))
            for (i in 1 until xs.size) path.lineTo(sx(xs[i]), sy(ys[i]))
            if (hasPos) path.lineTo(sx(lastX), sy(lastY))
            swathPaint.strokeWidth = max(34f * tScale, 8f * density)
            canvas.drawPath(path, swathPaint)
            canvas.drawPath(path, trailPaint)
            canvas.drawCircle(sx(xs[0]), sy(ys[0]), 8f * density, dockPaint)
        }

        // robot
        if (hasPos) {
            val rx = sx(lastX)
            val ry = sy(lastY)
            val rr = 11f * density
            canvas.drawCircle(rx, ry, rr, robotFill)
            canvas.drawCircle(rx, ry, rr, robotRing)
            val a = Math.toRadians(theta.toDouble())
            canvas.drawLine(
                rx, ry,
                rx + (cos(a) * rr * 0.8).toFloat(),
                ry - (sin(a) * rr * 0.8).toFloat(),
                arrowPaint
            )
        }

        // zona que se está dibujando
        val s = dragStart
        val d = dragNow
        if (s != null && d != null) {
            rect.set(
                sx(min(s.x, d.x)), sy(max(s.y, d.y)),
                sx(max(s.x, d.x)), sy(min(s.y, d.y))
            )
            canvas.drawRoundRect(rect, 8f * density, 8f * density, dragPaint)
        }
    }

    // ---------- Dibujar zonas con el dedo ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editable) return false
        if (!updateTransform()) return false
        val wx = tCx + (e.x - width / 2f) / tScale
        val wy = tCy - (e.y - height / 2f) / tScale
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStart = P(wx, wy)
                dragNow = dragStart
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                dragNow = P(wx, wy)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val s = dragStart
                dragStart = null
                dragNow = null
                if (s != null) {
                    val minPx = 24f * density
                    if (abs(wx - s.x) * tScale > minPx && abs(wy - s.y) * tScale > minPx) {
                        zoneListener?.onZoneDrawn(s.x, s.y, wx, wy)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragStart = null
                dragNow = null
                invalidate()
            }
        }
        return true
    }
}
