package com.example.roombalocal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Programación semanal de limpiezas: una hora por día, igual que el calendario interno del Roomba 960.
 * Los días se guardan con el índice del robot: 0 = domingo … 6 = sábado.
 */
data class DaySlot(var on: Boolean, var h: Int, var m: Int)

data class Schedule(
    val days: List<DaySlot>,
    var onRobot: Boolean,
    var onPhone: Boolean
) {
    fun anyOn() = days.any { it.on }

    fun toJson(): JSONObject = JSONObject()
        .put("days", JSONArray().apply {
            days.forEach { put(JSONObject().put("on", it.on).put("h", it.h).put("m", it.m)) }
        })
        .put("robot", onRobot)
        .put("phone", onPhone)

    /** Formato del robot (dorita980 / roombapy): {"cycle": [...], "h": [...], "m": [...]}. */
    fun toRobot(): JSONObject = JSONObject()
        .put("cycle", JSONArray().apply { days.forEach { put(if (it.on) "start" else "none") } })
        .put("h", JSONArray().apply { days.forEach { put(it.h) } })
        .put("m", JSONArray().apply { days.forEach { put(it.m) } })

    /** ¿Coincide con lo que informa el robot? (solo días activos y sus horas) */
    fun matchesRobot(robot: JSONObject?): Boolean {
        val other = fromRobot(robot) ?: return false
        for (i in 0..6) {
            val a = days[i]
            val b = other.days[i]
            if (a.on != b.on) return false
            if (a.on && (a.h != b.h || a.m != b.m)) return false
        }
        return true
    }

    companion object {
        /** Minutos de margen del respaldo del móvil cuando el robot también tiene la programación. */
        const val BACKUP_DELAY_MIN = 3

        fun default() = Schedule(List(7) { DaySlot(false, 10, 0) }, onRobot = true, onPhone = true)

        fun fromJson(j: JSONObject?): Schedule? {
            val arr = j?.optJSONArray("days") ?: return null
            if (arr.length() != 7) return null
            val days = (0..6).map {
                val d = arr.getJSONObject(it)
                DaySlot(d.optBoolean("on"), d.optInt("h", 10), d.optInt("m", 0))
            }
            return Schedule(days, j.optBoolean("robot", true), j.optBoolean("phone", true))
        }

        fun fromRobot(j: JSONObject?): Schedule? {
            val cycle = j?.optJSONArray("cycle") ?: return null
            val h = j.optJSONArray("h") ?: return null
            val m = j.optJSONArray("m") ?: return null
            if (cycle.length() < 7 || h.length() < 7 || m.length() < 7) return null
            val days = (0..6).map {
                DaySlot(cycle.optString(it) == "start", h.optInt(it, 10), m.optInt(it, 0))
            }
            return Schedule(days, onRobot = true, onPhone = true)
        }
    }
}

object ScheduleStore {
    private const val KEY = "schedule"
    private const val KEY_LAST = "scheduleLastRun"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("roomba", Context.MODE_PRIVATE)

    fun hasSaved(ctx: Context) = prefs(ctx).contains(KEY)

    fun load(ctx: Context): Schedule =
        try {
            Schedule.fromJson(JSONObject(prefs(ctx).getString(KEY, "") ?: "")) ?: Schedule.default()
        } catch (_: Exception) {
            Schedule.default()
        }

    fun save(ctx: Context, s: Schedule) {
        prefs(ctx).edit().putString(KEY, s.toJson().toString()).apply()
    }

    fun setLastRun(ctx: Context, text: String) {
        prefs(ctx).edit().putString(KEY_LAST, text).apply()
    }

    fun lastRun(ctx: Context): String = prefs(ctx).getString(KEY_LAST, "") ?: ""
}

/** Alarmas del móvil para el respaldo: siempre hay como mucho una armada, la próxima. */
object Scheduler {

    private const val REQ = 4201

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_RUN)
        return PendingIntent.getBroadcast(
            ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun delayMin(s: Schedule) = if (s.onRobot) Schedule.BACKUP_DELAY_MIN else 0

    /** Próximo disparo del respaldo (ms), o null si no hay nada que programar. */
    fun nextTrigger(s: Schedule, now: Long = System.currentTimeMillis()): Long? {
        if (!s.onPhone || !s.anyOn()) return null
        var best: Long? = null
        for (offset in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val slot = s.days[c.get(Calendar.DAY_OF_WEEK) - 1]
            if (!slot.on) continue
            c.set(Calendar.HOUR_OF_DAY, slot.h)
            c.set(Calendar.MINUTE, slot.m)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.MINUTE, delayMin(s))
            val t = c.timeInMillis
            if (t > now + 5_000 && (best == null || t < best)) best = t
        }
        return best
    }

    /** ¿Estamos cerca (±15 min) de una limpieza programada? Evita lanzar alarmas viejas. */
    fun isDue(s: Schedule, now: Long = System.currentTimeMillis()): Boolean {
        if (!s.onPhone) return false
        for (offset in -1..0) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val slot = s.days[c.get(Calendar.DAY_OF_WEEK) - 1]
            if (!slot.on) continue
            c.set(Calendar.HOUR_OF_DAY, slot.h)
            c.set(Calendar.MINUTE, slot.m)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            c.add(Calendar.MINUTE, delayMin(s))
            if (kotlin.math.abs(now - c.timeInMillis) <= 15 * 60_000L) return true
        }
        return false
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Arma (o anula) la alarma según la programación guardada. Devuelve la hora armada. */
    fun reschedule(ctx: Context): Long? {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx)
        am.cancel(pi)
        val next = nextTrigger(ScheduleStore.load(ctx)) ?: return null
        if (canExact(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
        return next
    }
}
