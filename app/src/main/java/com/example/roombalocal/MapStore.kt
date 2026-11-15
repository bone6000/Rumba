package com.example.roombalocal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class P(val x: Float, val y: Float)

data class Zone(val name: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    fun contains(x: Float, y: Float) =
        x >= min(x1, x2) && x <= max(x1, x2) && y >= min(y1, y2) && y <= max(y1, y2)
}

/**
 * Guarda en el móvil:
 *  - el recorrido de la limpieza en curso (por si se cierra la app),
 *  - el de la última limpieza completa,
 *  - el mapa de referencia (la primera limpieza completa, o la que elijas),
 *  - las zonas con nombre que dibujes sobre ese mapa.
 *
 * El 960 no guarda mapas propios: se trabaja con el recorrido que el robot comunica
 * (posición x, y desde el punto de partida de cada misión). El mapa de referencia
 * sirve mientras el robot arranque siempre desde la base.
 */
object MapStore {
    private var file: File? = null
    private var loaded = false

    private var cell = 0f
    private var reference: List<P> = emptyList()
    private var refCells: Set<Long> = emptySet()
    private var last: List<P> = emptyList()
    private val current = ArrayList<P>()
    private var currentMission = -1
    private var sinceSave = 0
    val zones = ArrayList<Zone>()

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        file = File(ctx.applicationContext.filesDir, "roomba_map.json")
        try {
            load()
        } catch (_: Exception) {
        }
    }

    // ---------- Recorrido en curso ----------

    /** Devuelve true si empieza una misión nueva (y por tanto se descartó el recorrido anterior). */
    @Synchronized
    fun addCurrent(x: Float, y: Float, mission: Int): Boolean {
        var reset = false
        if (mission >= 0 && mission != currentMission) {
            current.clear()
            currentMission = mission
            sinceSave = 0
            reset = true
        }
        val n = current.size
        if (n == 0 || hypot(x - current[n - 1].x, y - current[n - 1].y) > 3f) {
            current.add(P(x, y))
            if (++sinceSave >= 25) {
                sinceSave = 0
                save()
            }
        }
        return reset
    }

    @Synchronized
    fun currentPoints(): List<P> = ArrayList(current)

    /**
     * Se llama cuando la limpieza termina. Guarda el recorrido como "última limpieza" y,
     * si todavía no había mapa, lo guarda como mapa de referencia. Devuelve true en ese caso.
     */
    @Synchronized
    fun finishMission(): Boolean {
        if (current.size < 10) return false
        last = ArrayList(current)
        current.clear()
        sinceSave = 0
        var savedAsMap = false
        if (reference.isEmpty() && last.size >= 40) {
            setReference(last)
            savedAsMap = true
        }
        save()
        return savedAsMap
    }

    // ---------- Mapa de referencia ----------

    @Synchronized
    fun hasReference(): Boolean = reference.isNotEmpty()

    @Synchronized
    fun hasLast(): Boolean = last.size >= 10

    @Synchronized
    fun useLastAsReference(): Boolean {
        if (last.size < 10) return false
        setReference(last)
        save()
        return true
    }

    @Synchronized
    fun clearReference() {
        reference = emptyList()
        refCells = emptySet()
        cell = 0f
        save()
    }

    private fun setReference(points: List<P>) {
        reference = ArrayList(points)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }
        val extent = max(maxX - minX, maxY - minY)
        // Celdas del tamaño de ~1/50 del recorrido: el robot cubre unas 3 celdas de ancho.
        cell = max(extent / 50f, 1f)
        refCells = cells(reference, cell)
    }

    @Synchronized
    fun cellSize(): Float = cell

    @Synchronized
    fun referenceCenters(): List<P> = if (cell > 0f) centers(refCells) else emptyList()

    @Synchronized
    fun lastCenters(): List<P> =
        if (cell > 0f && last.isNotEmpty()) centers(cells(last, cell)) else emptyList()

    // ---------- Zonas ----------

    @Synchronized
    fun addZone(name: String, x1: Float, y1: Float, x2: Float, y2: Float) {
        var finalName = name.trim().ifEmpty { "Zona ${zones.size + 1}" }
        var n = 2
        while (zones.any { it.name == finalName }) {
            finalName = "${name.trim()} $n"
            n++
        }
        zones.add(Zone(finalName, x1, y1, x2, y2))
        save()
    }

    @Synchronized
    fun removeZone(index: Int) {
        if (index in zones.indices) {
            zones.removeAt(index)
            save()
        }
    }

    @Synchronized
    fun zoneList(): List<Zone> = ArrayList(zones)

    /** Porcentaje (0-100) del mapa de cada zona que cubrió la última limpieza; -1 si la zona no tiene mapa. */
    @Synchronized
    fun coverage(): Map<String, Int> {
        if (cell <= 0f || last.isEmpty() || refCells.isEmpty()) return emptyMap()
        val cur = cells(last, cell)
        val res = LinkedHashMap<String, Int>()
        for (z in zones) {
            var total = 0
            var hit = 0
            for (k in refCells) {
                val cx = (cellX(k) + 0.5f) * cell
                val cy = (cellY(k) + 0.5f) * cell
                if (z.contains(cx, cy)) {
                    total++
                    if (k in cur) hit++
                }
            }
            res[z.name] = if (total == 0) -1 else hit * 100 / total
        }
        return res
    }

    // ---------- Celdas ----------

    private fun key(ix: Int, iy: Int): Long = (ix.toLong() shl 32) or (iy.toLong() and 0xffffffffL)
    private fun cellX(k: Long): Int = (k shr 32).toInt()
    private fun cellY(k: Long): Int = (k and 0xffffffffL).toInt()

    private fun cells(points: List<P>, c: Float): HashSet<Long> {
        val out = HashSet<Long>()
        fun mark(x: Float, y: Float) {
            val ix = floor(x / c).toInt()
            val iy = floor(y / c).toInt()
            for (dx in -1..1) for (dy in -1..1) out.add(key(ix + dx, iy + dy))
        }
        for (i in points.indices) {
            val p = points[i]
            mark(p.x, p.y)
            if (i > 0) {
                val q = points[i - 1]
                val d = hypot(p.x - q.x, p.y - q.y)
                val steps = min((d / (c / 2f)).toInt(), 400)
                for (s in 1 until steps) {
                    val t = s / steps.toFloat()
                    mark(q.x + (p.x - q.x) * t, q.y + (p.y - q.y) * t)
                }
            }
        }
        return out
    }

    private fun centers(set: Set<Long>): List<P> =
        set.map { P((cellX(it) + 0.5f) * cell, (cellY(it) + 0.5f) * cell) }

    // ---------- Persistencia ----------

    private fun pointsToJson(l: List<P>): JSONArray {
        val a = JSONArray()
        for (p in l) a.put(JSONArray().put(Math.round(p.x)).put(Math.round(p.y)))
        return a
    }

    private fun jsonToPoints(a: JSONArray?): List<P> {
        val out = ArrayList<P>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            out.add(P(p.optDouble(0).toFloat(), p.optDouble(1).toFloat()))
        }
        return out
    }

    private fun save() {
        val f = file ?: return
        try {
            val j = JSONObject()
            j.put("ref", pointsToJson(reference))
            j.put("last", pointsToJson(last))
            j.put("current", pointsToJson(current))
            j.put("currentMission", currentMission)
            val za = JSONArray()
            for (z in zones) {
                za.put(
                    JSONObject()
                        .put("n", z.name)
                        .put("x1", z.x1.toDouble()).put("y1", z.y1.toDouble())
                        .put("x2", z.x2.toDouble()).put("y2", z.y2.toDouble())
                )
            }
            j.put("zones", za)
            f.writeText(j.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        val j = JSONObject(f.readText())
        val ref = jsonToPoints(j.optJSONArray("ref"))
        if (ref.isNotEmpty()) setReference(ref)
        last = jsonToPoints(j.optJSONArray("last"))
        current.clear()
        current.addAll(jsonToPoints(j.optJSONArray("current")))
        currentMission = j.optInt("currentMission", -1)
        zones.clear()
        val za = j.optJSONArray("zones")
        if (za != null) {
            for (i in 0 until za.length()) {
                val o = za.optJSONObject(i) ?: continue
                zones.add(
                    Zone(
                        o.optString("n"),
                        o.optDouble("x1").toFloat(), o.optDouble("y1").toFloat(),
                        o.optDouble("x2").toFloat(), o.optDouble("y2").toFloat()
                    )
                )
            }
        }
    }
}
