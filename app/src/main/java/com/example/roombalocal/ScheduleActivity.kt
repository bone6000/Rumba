package com.example.roombalocal

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Programación semanal: se guarda en el robot y el móvil hace de respaldo. */
class ScheduleActivity : Activity(), RoombaClient.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var sched: Schedule
    private val ui = Handler(Looper.getMainLooper())

    private val dayToggles = HashMap<Int, TextView>()
    private val dayTimes = HashMap<Int, TextView>()
    private lateinit var robotSwitch: Switch
    private lateinit var phoneSwitch: Switch
    private lateinit var connText: TextView
    private lateinit var robotInfo: TextView
    private lateinit var tzButton: Button
    private lateinit var statusText: TextView
    private lateinit var nextText: TextView
    private lateinit var batteryButton: Button

    private var lastRobotState: JSONObject? = null
    private var prefilledFromRobot = false
    private var waitingRobotConfirm = false

    // Orden en pantalla: lunes primero. Índices del robot: 0 = domingo.
    private val order = listOf(1, 2, 3, 4, 5, 6, 0)
    private val names = mapOf(
        0 to "Domingo", 1 to "Lunes", 2 to "Martes", 3 to "Miércoles",
        4 to "Jueves", 5 to "Viernes", 6 to "Sábado"
    )
    private val shortNames = mapOf(0 to "D", 1 to "L", 2 to "M", 3 to "X", 4 to "J", 5 to "V", 6 to "S")

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("roomba", Context.MODE_PRIVATE)
        sched = ScheduleStore.load(this)
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

        // Cabecera
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
        header.addView(TextView(this).apply {
            text = "Programación"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(14), 0, 0, 0)
        })
        root.addView(header)

        // Días y horas
        val days = Ui.card(this)
        days.addView(sectionTitle("Días y hora de limpieza"))
        days.addView(hint("Toca un día para activarlo y la hora para cambiarla. El 960 admite una limpieza por día."))
        for (i in order) days.addView(dayRow(i))
        val allSame = Button(this).apply {
            text = "Misma hora para todos los días activos"
            Ui.styleButton(this, Ui.CARD2, Ui.TEXT)
            textSize = 13f
            setOnClickListener { pickTimeForAll() }
        }
        days.addView(allSame, matchWrap(top = 12))
        root.addView(days, matchWrap(top = 14))

        // Dónde se ejecuta
        val where = Ui.card(this)
        where.addView(sectionTitle("Cómo se lanza"))
        robotSwitch = toggleSwitch("Guardar en el robot", sched.onRobot) { sched.onRobot = it; refreshNext() }
        where.addView(robotSwitch, matchWrap(top = 6))
        where.addView(hint("El robot limpia solo con su calendario interno, aunque el móvil esté apagado. Necesita tener bien la hora."))
        phoneSwitch = toggleSwitch("Respaldo desde el móvil", sched.onPhone) { sched.onPhone = it; refreshNext() }
        where.addView(phoneSwitch, matchWrap(top = 12))
        where.addView(hint(
            "${Schedule.BACKUP_DELAY_MIN} minutos después de la hora, el móvil comprueba el robot y, si no ha empezado, " +
                "le manda limpiar. El móvil tiene que estar encendido y en la wifi de casa."
        ))
        nextText = hint("")
        nextText.setTextColor(Ui.ACCENT)
        where.addView(nextText)
        batteryButton = Button(this).apply {
            text = "Permitir que funcione en segundo plano"
            Ui.styleButton(this, Ui.CARD2, Ui.WARN)
            textSize = 13f
            setOnClickListener { askIgnoreBattery() }
        }
        where.addView(batteryButton, matchWrap(top = 10))
        root.addView(where, matchWrap(top = 12))

        // Estado del robot
        val info = Ui.card(this)
        info.addView(sectionTitle("Robot"))
        connText = hint("Sin conexión")
        info.addView(connText)
        robotInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.TEXT)
            setPadding(0, dp(6), 0, 0)
        }
        info.addView(robotInfo)
        tzButton = Button(this).apply {
            Ui.styleButton(this, Ui.CARD2, Ui.TEXT)
            textSize = 13f
            visibility = View.GONE
            setOnClickListener { sendTimezone() }
        }
        info.addView(tzButton, matchWrap(top = 10))
        root.addView(info, matchWrap(top = 12))

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.MUTED)
        }
        root.addView(statusText, matchWrap(top = 12))

        val save = Button(this).apply {
            text = "Guardar programación"
            Ui.styleButton(this, Ui.ACCENT, Ui.ON_ACCENT)
            setOnClickListener { save() }
        }
        root.addView(save, matchWrap(top = 12))

        refreshRows()
        refreshNext()
    }

    override fun onStart() {
        super.onStart()
        refreshNext()
        val ip = prefs.getString("ip", "") ?: ""
        val blid = prefs.getString("blid", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        if (ip.isEmpty() || blid.isEmpty() || pass.isEmpty()) {
            connText.text = "Configura el robot en Ajustes para poder guardar la programación en él."
            return
        }
        RoombaHub.attach(this, this)
    }

    override fun onStop() {
        super.onStop()
        RoombaHub.detach(this)
    }

    // ---------- Construcción de la pantalla ----------

    private fun dayRow(i: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggle = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                sched.days[i].on = !sched.days[i].on
                refreshRows()
                refreshNext()
            }
        }
        val time = TextView(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { pickTime(i) }
        }
        dayToggles[i] = toggle
        dayTimes[i] = time
        row.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(time, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })
        return row.also {
            it.layoutParams = matchWrap(top = 8)
        }
    }

    private fun refreshRows() {
        for (i in 0..6) {
            val d = sched.days[i]
            dayToggles[i]?.apply {
                text = (if (d.on) "●  " else "○  ") + names[i]
                setTextColor(if (d.on) Ui.ON_ACCENT else Ui.MUTED)
                background = Ui.rounded(context, if (d.on) Ui.ACCENT else Ui.CARD2, 12)
            }
            dayTimes[i]?.apply {
                text = hhmm(d.h, d.m)
                setTextColor(if (d.on) Ui.TEXT else Ui.MUTED)
                background = Ui.rounded(context, Ui.CARD2, 12, if (d.on) Ui.ACCENT else Ui.STROKE)
            }
        }
    }

    private fun refreshNext() {
        val next = Scheduler.nextTrigger(sched)
        val lines = mutableListOf<String>()
        when {
            !sched.onPhone -> lines += "Respaldo del móvil desactivado."
            next == null -> lines += "No hay ningún día activo."
            else -> lines += "Próximo respaldo del móvil: " +
                SimpleDateFormat("EEEE d 'a las' HH:mm", Locale("es", "ES")).format(Date(next))
        }
        if (sched.onPhone && !Scheduler.canExact(this)) {
            lines += "Android no permite alarmas exactas a esta app: el respaldo puede retrasarse unos minutos."
        }
        val last = ScheduleStore.lastRun(this)
        if (last.isNotEmpty()) lines += "Último respaldo: $last"
        nextText.text = lines.joinToString("\n")

        val pm = getSystemService(PowerManager::class.java)
        batteryButton.visibility =
            if (sched.onPhone && !pm.isIgnoringBatteryOptimizations(packageName)) View.VISIBLE else View.GONE
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

    private fun toggleSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.TEXT)
        isChecked = checked
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun matchWrap(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun hhmm(h: Int, m: Int) = String.format(Locale.ROOT, "%02d:%02d", h, m)

    // ---------- Acciones ----------

    private fun pickTime(i: Int) {
        val d = sched.days[i]
        TimePickerDialog(this, { _, h, m ->
            d.h = h
            d.m = m
            d.on = true
            refreshRows()
            refreshNext()
        }, d.h, d.m, true).show()
    }

    private fun pickTimeForAll() {
        val first = order.map { sched.days[it] }.firstOrNull { it.on } ?: sched.days[1]
        TimePickerDialog(this, { _, h, m ->
            val anyOn = sched.anyOn()
            for (d in sched.days) {
                if (d.on || !anyOn) {
                    d.h = h
                    d.m = m
                }
            }
            refreshRows()
            refreshNext()
        }, first.h, first.m, true).show()
    }

    private fun save() {
        ScheduleStore.save(this, sched)
        val next = Scheduler.reschedule(this)
        val msgs = mutableListOf("Guardada en el móvil.")

        if (sched.onRobot) {
            if (RoombaHub.isConnected()) {
                waitingRobotConfirm = true
                RoombaHub.sendDelta(JSONObject().put("cleanSchedule", sched.toRobot()), "programación")
                msgs += "Enviando al robot…"
            } else {
                msgs += "No hay conexión con el robot: no se ha guardado en él. Vuelve a guardar cuando esté conectado."
            }
        } else if (RoombaHub.isConnected() && Schedule.fromRobot(
                lastRobotState?.optJSONObject("cleanSchedule"))?.anyOn() == true
        ) {
            // Se desactivó "Guardar en el robot": se vacía su calendario para que no limpie por su cuenta
            waitingRobotConfirm = true
            val empty = Schedule(sched.days.map { DaySlot(false, it.h, it.m) }, false, sched.onPhone)
            RoombaHub.sendDelta(JSONObject().put("cleanSchedule", empty.toRobot()), "borrar programación del robot")
            msgs += "Borrando la programación del robot…"
        }
        if (sched.onPhone && next != null) askNotifications()
        statusText.text = msgs.joinToString("\n")
        statusText.setTextColor(Ui.MUTED)
        refreshNext()
    }

    private fun sendTimezone() {
        val tz = TimeZone.getDefault().id
        RoombaHub.sendDelta(JSONObject().put("timezone", tz), "zona horaria $tz")
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun askIgnoreBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------- Robot ----------

    override fun onStatus(message: String) {
        ui.post {
            connText.text = message
            if (message == "Conectado" && lastRobotState == null) robotInfo.text = "Esperando datos del robot…"
            connText.setTextColor(
                when {
                    message.startsWith("Conectado") || message.startsWith("Enviado") -> Ui.OK
                    message.startsWith("Error") || message.startsWith("No ") ||
                        message.startsWith("Conexión perdida") -> Ui.DANGER
                    else -> Ui.MUTED
                }
            )
        }
    }

    override fun onState(state: JSONObject) {
        val receivedAt = System.currentTimeMillis()
        ui.post { renderRobot(state, receivedAt) }
    }

    private fun renderRobot(state: JSONObject, receivedAt: Long) {
        lastRobotState = state
        val lines = mutableListOf<String>()
        val robotSched = Schedule.fromRobot(state.optJSONObject("cleanSchedule"))

        // Si aún no hay nada guardado en el móvil, se parte de lo que tenga el robot
        if (robotSched != null && !ScheduleStore.hasSaved(this) && !prefilledFromRobot) {
            prefilledFromRobot = true
            for (i in 0..6) {
                sched.days[i].on = robotSched.days[i].on
                sched.days[i].h = robotSched.days[i].h
                sched.days[i].m = robotSched.days[i].m
            }
            refreshRows()
            refreshNext()
        }

        lines += "Programación en el robot: " + (robotSched?.let { summary(it) } ?: "sin datos")
        if (robotSched != null && sched.onRobot) {
            lines += if (sched.matchesRobot(state.optJSONObject("cleanSchedule")))
                "✓ Coincide con la de la app" else "≠ Distinta de la de la app (pulsa Guardar)"
        }

        // Reloj del robot: hora UTC (utctime) y desfase local (localtimeoffset, en minutos).
        // El calendario se dispara con la hora LOCAL del robot, así que lo importante es esta.
        if (state.has("utctime")) {
            val robotSec = state.optLong("utctime")
            val diffMin = (robotSec - receivedAt / 1000) / 60
            if (abs(diffMin) > 2) lines += "⚠ Reloj UTC del robot desfasado ${abs(diffMin)} min"
            if (state.has("localtimeoffset")) {
                val offMin = state.optInt("localtimeoffset")
                val robotLocal = SimpleDateFormat("HH:mm", Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date((robotSec + offMin * 60L) * 1000))
                val phoneOff = TimeZone.getDefault().getOffset(receivedAt) / 60000
                val phoneLocal = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(receivedAt))
                lines += "Hora local del robot: $robotLocal (móvil: $phoneLocal)"
                if (offMin != phoneOff) {
                    val diffH = (phoneOff - offMin) / 60.0
                    lines += "⚠ El robot usa UTC${fmtOffset(offMin)} y el móvil UTC${fmtOffset(phoneOff)}: " +
                        "su calendario se dispara ${fmtHours(diffH)} de la hora programada."
                }
            } else {
                lines += "Reloj del robot (UTC): " + SimpleDateFormat("HH:mm", Locale.ROOT).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(robotSec * 1000)) + " — el robot no envía su desfase horario"
            }
        } else {
            lines += "Reloj del robot: sin datos"
        }

        if (state.has("schedHold")) {
            lines += if (state.optBoolean("schedHold")) "⚠ Calendario del robot EN PAUSA (schedHold)"
            else "Calendario del robot activo"
        }

        val robotTz = state.optString("timezone", "")
        val phoneTz = TimeZone.getDefault().id
        if (robotTz.isNotEmpty()) {
            lines += "Zona horaria del robot: $robotTz"
            if (robotTz != phoneTz) {
                tzButton.text = "Poner la zona horaria del móvil ($phoneTz) en el robot"
                tzButton.visibility = View.VISIBLE
            } else {
                tzButton.visibility = View.GONE
            }
        }
        robotInfo.text = lines.joinToString("\n")

        if (waitingRobotConfirm && robotSched != null) {
            val target = if (sched.onRobot) sched
            else Schedule(sched.days.map { DaySlot(false, it.h, it.m) }, false, sched.onPhone)
            if (target.matchesRobot(state.optJSONObject("cleanSchedule"))) {
                waitingRobotConfirm = false
                statusText.text = if (sched.onRobot) "✓ Programación guardada en el robot y en el móvil."
                else "✓ Programación del robot borrada; solo limpia el respaldo del móvil."
                statusText.setTextColor(Ui.OK)
            }
        }
    }

    private fun fmtOffset(min: Int): String {
        val sign = if (min < 0) "-" else "+"
        val a = abs(min)
        return if (a % 60 == 0) "$sign${a / 60}" else "$sign${a / 60}:${String.format(Locale.ROOT, "%02d", a % 60)}"
    }

    private fun fmtHours(h: Double): String {
        val txt = if (h == Math.floor(h)) "${abs(h).toInt()} h" else "${abs(h)} h"
        return if (h > 0) "$txt después" else "$txt antes"
    }

    private fun summary(s: Schedule): String {
        val on = order.filter { s.days[it].on }
        if (on.isEmpty()) return "ninguna"
        return on.joinToString(", ") { "${shortNames[it]} ${hhmm(s.days[it].h, s.days[it].m)}" }
    }
}
