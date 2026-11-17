package com.example.roombalocal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Piezas de mantenimiento. El 960 no informa del desgaste de cada pieza por la red local,
 * así que se calcula con las horas de funcionamiento del robot desde el último cambio.
 * Vidas por defecto ≈ recomendaciones de iRobot limpiando ~1 h al día; se pueden cambiar.
 */
object Maintenance {
    data class Part(val key: String, val name: String, val defaultLifeH: Int, val tip: String)

    val parts = listOf(
        Part("filter", "Filtro", 60, "iRobot recomienda cambiarlo cada ~2 meses (cada mes con mascotas)."),
        Part("sidebrush", "Cepillo lateral", 120, "Cámbialo cuando las cerdas estén dobladas o gastadas (~3-4 meses)."),
        Part("extractors", "Rodillos de goma", 250, "Cámbialos cuando las aletas estén gastadas (~6-12 meses)."),
        Part("wheel", "Rueda delantera", 250, "Límpiala a menudo; cámbiala si no gira bien.")
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("roomba", Context.MODE_PRIVATE)

    /** Horas de funcionamiento informadas por el robot (bbrun), o null si no las ha enviado. */
    fun runHours(state: JSONObject?): Double? {
        val run = state?.optJSONObject("bbrun") ?: return null
        if (!run.has("hr")) return null
        return run.optInt("hr") + run.optInt("min") / 60.0
    }

    /** Guarda las últimas horas vistas para poder registrar cambios sin conexión. */
    fun observe(ctx: Context, state: JSONObject?) {
        val h = runHours(state)?.toFloat() ?: return
        if (prefs(ctx).getFloat("lastRunHours", -1f) != h) prefs(ctx).edit().putFloat("lastRunHours", h).apply()
    }

    fun lastHours(ctx: Context): Double? =
        prefs(ctx).getFloat("lastRunHours", -1f).takeIf { it >= 0f }?.toDouble()

    fun life(ctx: Context, p: Part) = prefs(ctx).getInt("life_${p.key}", p.defaultLifeH)
    fun setLife(ctx: Context, p: Part, h: Int) = prefs(ctx).edit().putInt("life_${p.key}", h).apply()

    fun resetHours(ctx: Context, p: Part): Double? =
        prefs(ctx).getFloat("reset_${p.key}", -1f).takeIf { it >= 0f }?.toDouble()

    fun resetDate(ctx: Context, p: Part): Long = prefs(ctx).getLong("resetAt_${p.key}", 0L)

    fun markReplaced(ctx: Context, p: Part, hoursNow: Double) {
        prefs(ctx).edit()
            .putFloat("reset_${p.key}", hoursNow.toFloat())
            .putLong("resetAt_${p.key}", System.currentTimeMillis())
            .apply()
    }
}

/** Estadísticas del robot: batería, limpieza actual, totales, mantenimiento y datos en bruto. */
class StatsActivity : Activity(), RoombaClient.Listener {

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var connText: TextView
    private lateinit var batteryBig: TextView
    private lateinit var batteryBar: ProgressBar
    private lateinit var batteryRows: LinearLayout
    private lateinit var missionRows: LinearLayout
    private lateinit var totalsRows: LinearLayout
    private lateinit var maintRows: LinearLayout
    private lateinit var robotRows: LinearLayout
    private lateinit var rawText: TextView
    private lateinit var rawToggle: Button

    private var state: JSONObject? = null
    private var lastRender = 0L
    private var renderPending = false
    private var rawVisible = false

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("roomba", Context.MODE_PRIVATE)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(context, Ui.CARD, 14)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "Estadísticas"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
        })
        connText = TextView(this).apply {
            text = "Sin conexión"
            textSize = 12f
            setTextColor(Ui.MUTED)
        }
        titles.addView(connText)
        header.addView(titles)
        root.addView(header)

        // Batería
        val bat = Ui.card(this)
        bat.addView(sectionTitle("Batería"))
        batteryBig = TextView(this).apply {
            text = "— %"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(0, dp(4), 0, 0)
        }
        bat.addView(batteryBig)
        batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Ui.OK)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.CARD2)
        }
        bat.addView(batteryBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
        batteryRows = rows()
        bat.addView(batteryRows)
        root.addView(bat, cardParams())

        // Limpieza actual
        val mission = Ui.card(this)
        mission.addView(sectionTitle("Limpieza actual"))
        missionRows = rows()
        mission.addView(missionRows)
        root.addView(mission, cardParams())

        // Mantenimiento
        val maint = Ui.card(this)
        maint.addView(sectionTitle("Mantenimiento"))
        maint.addView(hint(
            "El robot no informa del desgaste de las piezas, así que se calcula con sus horas de " +
                "funcionamiento. Pulsa \"Lo he cambiado\" al cambiar una pieza y toca su nombre para ajustar su duración."
        ))
        maintRows = rows()
        maint.addView(maintRows)
        root.addView(maint, cardParams())

        // Totales
        val totals = Ui.card(this)
        totals.addView(sectionTitle("Historial"))
        totalsRows = rows()
        totals.addView(totalsRows)
        root.addView(totals, cardParams())

        // Robot
        val info = Ui.card(this)
        info.addView(sectionTitle("Robot"))
        robotRows = rows()
        info.addView(robotRows)
        root.addView(info, cardParams())

        // Datos en bruto
        val raw = Ui.card(this)
        raw.addView(sectionTitle("Todos los datos"))
        raw.addView(hint("Todo lo que envía el robot, tal cual. Útil para buscar un dato que no aparezca arriba."))
        val rawButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rawToggle = Button(this).apply {
            text = "Mostrar"
            Ui.styleButton(this, Ui.CARD2, Ui.TEXT)
            textSize = 13f
            setOnClickListener {
                rawVisible = !rawVisible
                text = if (rawVisible) "Ocultar" else "Mostrar"
                rawText.visibility = if (rawVisible) View.VISIBLE else View.GONE
                render()
            }
        }
        val rawCopy = Button(this).apply {
            text = "Copiar"
            Ui.styleButton(this, Ui.CARD2, Ui.TEXT)
            textSize = 13f
            setOnClickListener { copyRaw() }
        }
        rawButtons.addView(rawToggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rawButtons.addView(rawCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(8)
        })
        raw.addView(rawButtons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        rawText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.MUTED)
            setTextIsSelectable(true)
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        raw.addView(rawText)
        root.addView(raw, cardParams())

        render()
    }

    override fun onStart() {
        super.onStart()
        val ip = prefs.getString("ip", "") ?: ""
        val blid = prefs.getString("blid", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        if (ip.isEmpty() || blid.isEmpty() || pass.isEmpty()) {
            connText.text = "Configura el robot en Ajustes (⚙)"
            return
        }
        RoombaHub.attach(this, this)
    }

    override fun onStop() {
        super.onStop()
        RoombaHub.detach(this)
    }

    override fun onStatus(message: String) {
        ui.post {
            connText.text = message
            connText.setTextColor(
                when {
                    message.startsWith("Conectado") -> Ui.OK
                    message.startsWith("Error") || message.startsWith("No ") ||
                        message.startsWith("Conexión perdida") -> Ui.DANGER
                    else -> Ui.MUTED
                }
            )
        }
    }

    override fun onState(state: JSONObject) {
        ui.post {
            this.state = state
            Maintenance.observe(this, state)
            // Mientras limpia llegan muchas posiciones por segundo: se redibuja como mucho una vez por segundo
            val wait = 1000 - (System.currentTimeMillis() - lastRender)
            if (wait <= 0) render()
            else if (!renderPending) {
                renderPending = true
                ui.postDelayed({ renderPending = false; render() }, wait)
            }
        }
    }

    // ---------- Pintado ----------

    private fun render() {
        lastRender = System.currentTimeMillis()
        val s = state
        renderBattery(s)
        renderMission(s)
        renderMaintenance(s)
        renderTotals(s)
        renderRobot(s)
        if (rawVisible) rawText.text = s?.let { sorted(it).toString(2) } ?: "Sin datos todavía."
    }

    private fun renderBattery(s: JSONObject?) {
        batteryRows.removeAllViews()
        val pct = if (s?.has("batPct") == true) s.optInt("batPct") else -1
        batteryBig.text = if (pct >= 0) "$pct %" else "— %"
        batteryBar.progress = max(pct, 0)
        batteryBar.progressTintList = ColorStateList.valueOf(
            when {
                pct in 0..14 -> Ui.DANGER
                pct in 15..29 -> Ui.WARN
                else -> Ui.OK
            }
        )
        if (s == null) return
        val mission = s.optJSONObject("cleanMissionStatus")
        val phase = mission?.optString("phase") ?: ""
        row(batteryRows, "Estado", when (phase) {
            "charge" -> "Cargando / en la base"
            "run" -> "En uso (limpiando)"
            "" -> null
            else -> "Fuera de la base"
        })
        mission?.optInt("rechrgM", 0)?.takeIf { it > 0 }?.let {
            row(batteryRows, "Recarga para seguir limpiando", minutes(it))
        }
        val chg = s.optJSONObject("bbchg3")
        chg?.let { c ->
            if (c.has("estCap")) row(batteryRows, "Capacidad estimada", "${c.optInt("estCap")} mAh")
            val charges = c.optInt("nLithChrg") + c.optInt("nNimhChrg")
            if (c.has("nLithChrg") || c.has("nNimhChrg")) row(batteryRows, "Ciclos de carga", "$charges")
            if (c.has("hOnDock")) row(batteryRows, "Horas en la base", "${c.optInt("hOnDock")} h")
            if (c.has("nDocks")) row(batteryRows, "Veces que ha vuelto a la base", "${c.optInt("nDocks")}")
        }
        s.optJSONObject("bbchg")?.let { c ->
            if (c.has("aborts")) {
                val a = c.opt("aborts")
                row(batteryRows, "Cargas interrumpidas", if (a is org.json.JSONArray) a.join(" · ") else a.toString())
            }
        }
        val info = s.optJSONObject("batInfo")
        info?.let { b ->
            val maker = b.optString("mName", "")
            if (maker.isNotEmpty()) row(batteryRows, "Fabricante", maker)
            val date = b.optString("mDate", "")
            if (date.isNotEmpty()) row(batteryRows, "Fecha de fabricación", date)
        }
        s.optString("batteryType", "").takeIf { it.isNotEmpty() }?.let { row(batteryRows, "Tipo", it) }
    }

    private fun renderMission(s: JSONObject?) {
        missionRows.removeAllViews()
        val m = s?.optJSONObject("cleanMissionStatus")
        if (m == null) {
            row(missionRows, "Sin datos", "—")
            return
        }
        val phase = m.optString("phase")
        val cycle = m.optString("cycle")
        row(missionRows, "Fase", translatePhase(phase))
        if (cycle.isNotEmpty() && cycle != "none") row(missionRows, "Ciclo", cycle)
        val active = cycle.isNotEmpty() && cycle != "none"
        if (active) {
            if (m.has("mssnM")) row(missionRows, "Tiempo limpiando", minutes(m.optInt("mssnM")))
            if (m.has("sqft")) row(missionRows, "Superficie", "${sqftToM2(m.optInt("sqft"))} m²")
            m.optLong("mssnStrtTm", 0).takeIf { it > 0 }?.let {
                row(missionRows, "Empezó", fmtDate(it * 1000))
            }
            m.optInt("expireM", 0).takeIf { it > 0 }?.let {
                row(missionRows, "La misión caduca en", minutes(it))
            }
            m.optString("initiator", "").takeIf { it.isNotEmpty() }?.let {
                row(missionRows, "Iniciada por", when (it) {
                    "localApp" -> "Esta app"
                    "schedule" -> "Programación del robot"
                    "manual" -> "Botón del robot"
                    "rmtApp" -> "App de iRobot"
                    else -> it
                })
            }
        } else {
            row(missionRows, "Ahora mismo", "No hay ninguna limpieza en curso")
        }
        val err = m.optInt("error", 0)
        if (err != 0) row(missionRows, "Error", "$err — ${errorText(err)}", Ui.DANGER)
        val nr = m.optInt("notReady", 0)
        if (nr != 0) row(missionRows, "No preparado", "$nr — ${notReadyText(nr)}", Ui.WARN)
        s?.optJSONObject("bin")?.let { b ->
            row(missionRows, "Depósito", when {
                b.has("present") && !b.optBoolean("present") -> "No está puesto"
                b.optBoolean("full") -> "Lleno: vacíalo"
                else -> "Correcto"
            }, if (b.optBoolean("full") || (b.has("present") && !b.optBoolean("present"))) Ui.WARN else null)
        }
    }

    private fun renderMaintenance(s: JSONObject?) {
        maintRows.removeAllViews()
        val hoursNow = Maintenance.runHours(s) ?: Maintenance.lastHours(this)
        for (p in Maintenance.parts) {
            val life = Maintenance.life(this, p)
            val base = Maintenance.resetHours(this, p)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = p.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.TEXT)
                setOnClickListener { editLife(p) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val status = TextView(this).apply {
                textSize = 14f
                gravity = Gravity.END
            }
            top.addView(status)
            box.addView(top)

            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressBackgroundTintList = ColorStateList.valueOf(Ui.CARD2)
            }
            val detail = TextView(this).apply {
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, dp(4), 0, 0)
            }

            if (base == null || hoursNow == null) {
                status.text = "Sin registrar"
                status.setTextColor(Ui.MUTED)
                bar.progress = 0
                detail.text = (if (hoursNow == null) "Conéctate al robot para leer sus horas de uso. " else "") +
                    "Pulsa \"Lo he cambiado\" cuando la cambies (o ahora si está nueva). Duración: $life h de uso. ${p.tip}"
            } else {
                val used = max(0.0, hoursNow - base)
                val left = life - used
                val pctLeft = ((left / life) * 100).roundToInt().coerceIn(0, 100)
                bar.progress = pctLeft
                val color = when {
                    pctLeft <= 10 -> Ui.DANGER
                    pctLeft <= 25 -> Ui.WARN
                    else -> Ui.OK
                }
                bar.progressTintList = ColorStateList.valueOf(color)
                status.setTextColor(color)
                status.text = if (left <= 0) "Cambiar ya" else "Quedan ${left.roundToInt()} h ($pctLeft %)"
                val since = Maintenance.resetDate(this, p).takeIf { it > 0 }?.let { " · cambiado el ${fmtDay(it)}" } ?: ""
                detail.text = "Usado ${used.roundToInt()} de $life h$since. ${p.tip}"
            }
            box.addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
            box.addView(detail)
            val btn = Button(this).apply {
                text = "Lo he cambiado"
                Ui.styleButton(this, Ui.CARD2, Ui.TEXT)
                textSize = 12f
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isEnabled = hoursNow != null
                alpha = if (hoursNow != null) 1f else 0.5f
                setOnClickListener { confirmReplaced(p, hoursNow ?: return@setOnClickListener) }
            }
            box.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            maintRows.addView(box)
        }
    }

    private fun renderTotals(s: JSONObject?) {
        totalsRows.removeAllViews()
        val run = s?.optJSONObject("bbrun")
        val mssn = s?.optJSONObject("bbmssn")
        if (run == null && mssn == null) {
            val h = Maintenance.lastHours(this)
            row(totalsRows, "Horas de limpieza", h?.let { "${it.roundToInt()} h (último dato guardado)" } ?: "Sin datos")
            return
        }
        run?.let { r ->
            if (r.has("hr")) row(totalsRows, "Horas de limpieza", "${r.optInt("hr")} h ${r.optInt("min")} min")
            if (r.has("sqft")) row(totalsRows, "Superficie total", "${sqftToM2(r.optInt("sqft") * 100)} m²")
        }
        mssn?.let { m ->
            if (m.has("nMssn")) row(totalsRows, "Limpiezas", "${m.optInt("nMssn")}")
            if (m.has("nMssnOk")) row(totalsRows, "Terminadas bien", "${m.optInt("nMssnOk")}")
            if (m.has("nMssnC")) row(totalsRows, "Canceladas", "${m.optInt("nMssnC")}")
            if (m.has("nMssnF")) row(totalsRows, "Fallidas", "${m.optInt("nMssnF")}")
            if (m.has("aMssnM")) row(totalsRows, "Duración media", minutes(m.optInt("aMssnM")))
            if (m.has("aCycleM")) row(totalsRows, "Duración media del ciclo", minutes(m.optInt("aCycleM")))
        }
        run?.let { r ->
            val events = listOf(
                "nScrubs" to "Zonas sucias detectadas (Dirt Detect)",
                "nStuck" to "Veces atascado",
                "nPicks" to "Veces levantado",
                "nPanics" to "Maniobras de escape",
                "nCliffsF" to "Escalones detectados (avanzando)",
                "nCliffsR" to "Escalones detectados (marcha atrás)",
                "nMBStll" to "Bloqueos del cepillo principal",
                "nWStll" to "Bloqueos de las ruedas",
                "nCBump" to "Choques"
            )
            for ((k, label) in events) if (r.has(k)) row(totalsRows, label, "${r.optInt(k)}")
        }
        s?.optJSONObject("bbsys")?.let { b ->
            if (b.has("hr")) row(totalsRows, "Horas encendido", "${b.optInt("hr")} h")
        }
    }

    private fun renderRobot(s: JSONObject?) {
        robotRows.removeAllViews()
        if (s == null) {
            row(robotRows, "Sin datos", "—")
            return
        }
        s.optString("name", "").takeIf { it.isNotEmpty() }?.let { row(robotRows, "Nombre", it) }
        s.optString("sku", "").takeIf { it.isNotEmpty() }?.let { row(robotRows, "Modelo", it) }
        s.optString("softwareVer", "").takeIf { it.isNotEmpty() }?.let { row(robotRows, "Firmware", it) }
        row(robotRows, "IP", prefs.getString("ip", "") ?: "")
        s.optJSONObject("signal")?.let { sig ->
            if (sig.has("rssi")) {
                val rssi = sig.optInt("rssi")
                val quality = when {
                    rssi >= -55 -> "excelente"
                    rssi >= -67 -> "buena"
                    rssi >= -75 -> "regular"
                    else -> "débil"
                }
                row(robotRows, "Señal wifi", "$rssi dBm ($quality)")
            }
        }
        s.optJSONObject("dock")?.let { d ->
            if (d.has("known")) row(robotRows, "Conoce su base", if (d.optBoolean("known")) "Sí" else "No")
        }
        val prefsLine = mutableListOf<String>()
        if (s.has("vacHigh")) prefsLine += if (s.optBoolean("vacHigh")) "Potencia alta" else "Potencia normal"
        if (s.has("carpetBoost")) prefsLine += if (s.optBoolean("carpetBoost")) "Carpet Boost automático" else "Sin Carpet Boost automático"
        if (s.has("twoPass")) prefsLine += if (s.optBoolean("twoPass")) "Dos pasadas" else "Una pasada"
        if (s.has("binPause")) prefsLine += if (s.optBoolean("binPause")) "Para si el depósito se llena" else "Sigue con el depósito lleno"
        if (prefsLine.isNotEmpty()) row(robotRows, "Ajustes de limpieza", prefsLine.joinToString(" · "))
        if (s.has("utctime")) row(robotRows, "Reloj del robot", fmtDate(s.optLong("utctime") * 1000))
    }

    // ---------- Acciones ----------

    private fun confirmReplaced(p: Maintenance.Part, hoursNow: Double) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setMessage("¿Has puesto una pieza nueva? El contador empieza de cero con las ${hoursNow.roundToInt()} h de uso actuales del robot.")
            .setPositiveButton("Sí, lo he cambiado") { _, _ ->
                Maintenance.markReplaced(this, p, hoursNow)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun editLife(p: Maintenance.Part) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Maintenance.life(this@StatsActivity, p).toString())
            Ui.styleField(this)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Duración de ${p.name.lowercase()} (horas de uso)")
            .setMessage("Por defecto: ${p.defaultLifeH} h. ${p.tip}")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                input.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let {
                    Maintenance.setLife(this, p, it)
                    render()
                }
            }
            .setNeutralButton("Por defecto") { _, _ ->
                Maintenance.setLife(this, p, p.defaultLifeH)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun copyRaw() {
        val s = state ?: return
        val txt = sorted(s).toString(2)
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Estado del Roomba", txt))
        connText.text = "Datos copiados al portapapeles"
    }

    // ---------- Ayudas ----------

    private fun rows() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row(parent: LinearLayout, label: String, value: String?, color: Int? = null) {
        if (value.isNullOrEmpty()) return
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        r.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Ui.MUTED)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(this).apply {
            text = value
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(color ?: Ui.TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
            leftMargin = dp(10)
        })
        parent.addView(r)
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.TEXT)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(Ui.MUTED)
        setPadding(0, dp(6), 0, 0)
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
    }

    private fun minutes(m: Int): String = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"

    private fun sqftToM2(sqft: Int): Int = (sqft / 10.764).roundToInt()

    private fun fmtDate(ms: Long) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES")).format(Date(ms))
    private fun fmtDay(ms: Long) = SimpleDateFormat("d MMM yyyy", Locale("es", "ES")).format(Date(ms))

    /** Copia con las claves ordenadas, para que los datos en bruto sean fáciles de leer. */
    private fun sorted(o: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = o.keys().asSequence().toList().sorted()
        for (k in keys) {
            val v = o.opt(k)
            out.put(k, if (v is JSONObject) sorted(v) else v)
        }
        return out
    }

    private fun translatePhase(p: String) = when (p) {
        "charge" -> "Cargando"
        "run" -> "Limpiando"
        "stop" -> "Parado"
        "hmUsrDock" -> "Volviendo a la base (orden)"
        "hmMidMsn" -> "Volviendo a la base a cargar"
        "hmPostMsn" -> "Volviendo a la base (terminado)"
        "stuck" -> "Atascado"
        "evac" -> "Vaciando"
        "" -> "—"
        else -> p
    }

    // Códigos de error y de "no preparado" según roombapy (los mismos que muestra Home Assistant)
    private val errors = mapOf(
        1 to "Rueda izquierda fuera del suelo", 2 to "Cepillos principales atascados",
        3 to "Rueda derecha fuera del suelo", 4 to "Rueda izquierda atascada",
        5 to "Rueda derecha atascada", 6 to "Atascado cerca de un escalón",
        7 to "Error en la rueda izquierda", 8 to "Error del depósito", 9 to "Paragolpes atascado",
        10 to "Error en la rueda derecha", 11 to "Error del depósito", 12 to "Problema en el sensor de escalones",
        13 to "Las dos ruedas fuera del suelo", 14 to "Falta el depósito", 15 to "Hay que reiniciarlo",
        16 to "Golpe inesperado", 17 to "Camino bloqueado", 18 to "Problema al volver a la base",
        19 to "Problema al salir de la base", 20 to "Problema al volver a la base",
        21 to "Problema de navegación", 22 to "Problema de navegación", 23 to "Problema de batería",
        24 to "Problema de navegación", 25 to "Hay que reiniciarlo", 26 to "Problema de aspiración",
        27 to "Problema de aspiración", 29 to "Necesita actualizar el software", 30 to "Problema de aspiración",
        31 to "Hay que reiniciarlo", 33 to "Camino bloqueado", 34 to "Hay que reiniciarlo",
        36 to "Depósito lleno", 38 to "Problema de aspiración", 39 to "Hay que reiniciarlo",
        40 to "Problema de navegación", 41 to "Tiempo agotado", 42 to "Problema de localización",
        43 to "Problema de navegación", 46 to "Batería baja", 47 to "Hay que reiniciarlo",
        48 to "Camino bloqueado", 65 to "Problema de hardware", 66 to "Poca memoria",
        68 to "Problema de hardware", 76 to "Problema de hardware", 89 to "Misión demasiado larga",
        101 to "Batería no conectada", 102 to "Error de carga", 103 to "Error de carga",
        104 to "Sin corriente de carga", 105 to "Corriente de carga demasiado baja",
        106 to "Batería demasiado caliente", 107 to "Temperatura de batería incorrecta",
        108 to "Fallo de comunicación con la batería", 109 to "Error de batería",
        110 to "Celdas de la batería desequilibradas", 111 to "Fallo de comunicación con la batería",
        112 to "Carga no válida", 114 to "Fallo interno de la batería", 115 to "Fallo de celda al cargar",
        116 to "Error de carga de la base", 118 to "Fallo de comunicación con la batería",
        119 to "Tiempo de carga agotado", 120 to "Batería no inicializada", 122 to "Error del sistema de carga",
        123 to "Batería no inicializada"
    )

    private fun errorText(e: Int) = errors[e] ?: "código desconocido"

    private fun notReadyText(n: Int) = when (n) {
        2 -> "Suelo irregular"
        15 -> "Batería baja"
        39 -> "Pendiente"
        48 -> "Camino bloqueado"
        68 -> "Actualizando"
        else -> "código desconocido"
    }
}
