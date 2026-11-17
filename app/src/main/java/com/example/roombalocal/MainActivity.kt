package com.example.roombalocal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.max

class MainActivity : Activity(), RoombaClient.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var batteryText: TextView
    private lateinit var phaseText: TextView
    private lateinit var batteryBar: ProgressBar
    private lateinit var binChip: TextView
    private lateinit var map: MapView
    private var sawRun = false
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("roomba", Context.MODE_PRIVATE)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        setContentView(root)

        // Cabecera: título, estado de la conexión y botón de ajustes
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Roomba"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
        })
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Ui.WARN)
            }
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            rightMargin = dp(8)
        })
        statusText = TextView(this).apply {
            text = "Sin conexión"
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Ui.MUTED)
        }
        statusRow.addView(statusText)
        titles.addView(statusRow)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val zonesBtn = TextView(this).apply {
            text = "▦"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(context, Ui.CARD, 14)
            setOnClickListener { startActivity(Intent(this@MainActivity, ZonesActivity::class.java)) }
        }
        val clock = TextView(this).apply {
            text = "◷"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(context, Ui.CARD, 14)
            setOnClickListener { startActivity(Intent(this@MainActivity, ScheduleActivity::class.java)) }
        }
        val statsBtn = TextView(this).apply {
            text = "ⓘ"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(context, Ui.CARD, 14)
            setOnClickListener { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
        }
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(context, Ui.CARD, 14)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        for (b in listOf(statsBtn, clock, zonesBtn)) {
            header.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(8) })
        }
        header.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Mapa (ocupa todo el espacio libre)
        MapStore.init(this)
        map = MapView(this)
        map.setTrail(MapStore.currentPoints())
        val mapCard = FrameLayout(this).apply {
            background = Ui.rounded(context, Ui.CARD, 22)
            clipToOutline = true
        }
        mapCard.addView(map, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(mapCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        // Tarjeta de estado: batería y fase
        val info = Ui.card(this)
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        batteryText = TextView(this).apply {
            text = "— %"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
        }
        phaseText = TextView(this).apply {
            text = "—"
            textSize = 16f
            gravity = Gravity.END
            setTextColor(Ui.ACCENT)
        }
        infoRow.addView(batteryText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        infoRow.addView(phaseText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        info.addView(infoRow)
        batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Ui.OK)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.CARD2)
        }
        info.addView(batteryBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(8) })
        binChip = TextView(this).apply {
            text = "Depósito lleno: vacíalo"
            textSize = 12f
            setTextColor(Ui.ON_ACCENT)
            background = Ui.rounded(context, Ui.WARN, 10)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        info.addView(binChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        // Tocar la tarjeta de batería abre las estadísticas
        info.setOnClickListener { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
        root.addView(info, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        // Controles debajo del mapa
        root.addView(buttonRow(
            control("▶  Limpiar", Ui.ACCENT, Ui.ON_ACCENT, "start"),
            control("‖  Pausa", Ui.CARD2, Ui.TEXT, "pause"),
            control("■  Parar", Ui.CARD2, Ui.DANGER, "stop")
        ), rowParams())
        root.addView(buttonRow(
            control("Reanudar", Ui.CARD2, Ui.TEXT, "resume"),
            control("⌂  A la base", Ui.CARD2, Ui.TEXT, "dock"),
            control("◎  Localizar", Ui.CARD2, Ui.TEXT, "find")
        ), rowParams())
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(10)
    }

    private fun buttonRow(vararg buttons: Button): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, b) in buttons.withIndex()) {
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(10)
            })
        }
        return row
    }

    private fun control(label: String, bg: Int, fg: Int, command: String): Button =
        Button(this).apply {
            text = label
            Ui.styleButton(this, bg, fg)
            textSize = 14f
            setOnClickListener { RoombaHub.send(command) }
        }

    private fun refreshOverlay() {
        map.setOverlay(
            MapStore.referenceCenters(),
            MapStore.cellSize(),
            emptyList(),
            MapStore.zoneList(),
            emptyMap()
        )
    }

    override fun onStart() {
        super.onStart()
        refreshOverlay()
        Scheduler.reschedule(this) // mantiene armada la próxima limpieza programada
        val ip = prefs.getString("ip", "") ?: ""
        val blid = prefs.getString("blid", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        if (ip.isEmpty() || blid.isEmpty() || pass.isEmpty()) {
            setStatus("Configura el robot en ajustes (⚙)")
            if (!prefs.getBoolean("askedSettings", false)) {
                prefs.edit().putBoolean("askedSettings", true).apply()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            return
        }
        RoombaHub.attach(this, this)
    }

    // Al salir de la pantalla se libera la única conexión MQTT que admite el robot.
    override fun onStop() {
        super.onStop()
        RoombaHub.detach(this)
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
        val c = when {
            msg.startsWith("Conectado") || msg.startsWith("Enviado") -> Ui.OK
            msg.startsWith("Error") || msg.startsWith("No ") || msg.startsWith("Conexión perdida") -> Ui.DANGER
            else -> Ui.WARN
        }
        (statusDot.background as GradientDrawable).setColor(c)
    }

    override fun onStatus(message: String) {
        ui.post { setStatus(message) }
    }

    override fun onState(state: JSONObject) {
        ui.post { render(state) }
    }

    private fun render(state: JSONObject) {
        Maintenance.observe(this, state)
        val bat = if (state.has("batPct")) state.optInt("batPct") else -1
        batteryText.text = if (bat >= 0) "$bat %" else "— %"
        batteryBar.progress = max(bat, 0)
        val batColor = when {
            bat in 0..14 -> Ui.DANGER
            bat in 15..29 -> Ui.WARN
            else -> Ui.OK
        }
        batteryBar.progressTintList = ColorStateList.valueOf(batColor)

        val mission = state.optJSONObject("cleanMissionStatus")
        phaseText.text = translatePhase(mission?.optString("phase") ?: "")
        binChip.visibility =
            if (state.optJSONObject("bin")?.optBoolean("full", false) == true) View.VISIBLE else View.GONE

        val phase = mission?.optString("phase") ?: ""
        val cycle = mission?.optString("cycle") ?: ""
        val nMssn = mission?.optInt("nMssn", -1) ?: -1

        // Posición: se dibuja y se guarda en el móvil
        val pose = state.optJSONObject("pose")
        val pt = pose?.optJSONObject("point")
        if (pose != null && pt != null) {
            val x = pt.optDouble("x").toFloat()
            val y = pt.optDouble("y").toFloat()
            if (MapStore.addCurrent(x, y, nMssn)) map.reset()
            map.update(x, y, pose.optDouble("theta", 0.0).toFloat())
        }

        // Fin de una limpieza completa: se guarda; la primera pasa a ser el mapa
        if (phase == "run") {
            sawRun = true
        } else if (phase == "charge" && cycle == "none" && sawRun) {
            sawRun = false
            val savedAsMap = MapStore.finishMission()
            refreshOverlay()
            if (savedAsMap) {
                Toast.makeText(this, "Mapa guardado tras la primera limpieza completa", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun translatePhase(p: String) = when (p) {
        "charge" -> "Cargando"
        "run" -> "Limpiando"
        "stop" -> "Parado"
        "hmUsrDock", "hmMidMsn", "hmPostMsn" -> "Volviendo a la base"
        "stuck" -> "Atascado"
        "evac" -> "Vaciando"
        "" -> "—"
        else -> p
    }
}
