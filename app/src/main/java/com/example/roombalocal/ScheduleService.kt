package com.example.roombalocal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Respaldo de la programación desde el móvil: a la hora indicada se conecta al robot y,
 * si no está ya limpiando (porque arrancó con su propio calendario), le manda limpiar.
 */
class ScheduleService : Service() {

    companion object {
        private const val CHANNEL = "schedule"
        private const val NOTIF_RUNNING = 4301
        private const val NOTIF_RESULT = 4302
        private val BUSY = setOf("run", "hmUsrDock", "hmMidMsn", "hmPostMsn", "evac")

        fun stamp(): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale("es", "ES")).format(Date())

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Limpieza programada", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    @Volatile
    private var running = false
    @Volatile
    private var cleaningSeen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val n = notification("Conectando con el robot…", ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_RUNNING, n)
        }
        if (!running) {
            running = true
            Thread { work() }.start()
        }
        return START_NOT_STICKY
    }

    private fun work() {
        cleaningSeen = false
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoombaLocal:schedule")
        wl.acquire(240_000L)
        var result: String
        var problem = false
        try {
            val schedule = ScheduleStore.load(this)
            if (!Scheduler.isDue(schedule)) {
                result = ""
            } else {
                val r = runBackup()
                result = r.first
                problem = r.second
            }
        } catch (e: Exception) {
            result = "Error: ${e.message}"
            problem = true
        }
        if (result.isNotEmpty()) {
            ScheduleStore.setLastRun(this, stamp() + " — " + result)
            if (problem) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_RESULT, notification(result, ongoing = false))
            }
        }
        Scheduler.reschedule(this)
        if (wl.isHeld) wl.release()
        // Con la limpieza en marcha, se graba el recorrido para el mapa (la conexión de este
        // servicio ya se ha cerrado, así que no se pisan)
        if (cleaningSeen) MapRecorderService.start(this)
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Devuelve (texto del resultado, ¿es un problema que conviene avisar?). */
    private fun runBackup(): Pair<String, Boolean> {
        val prefs = getSharedPreferences("roomba", Context.MODE_PRIVATE)
        val ip = prefs.getString("ip", "") ?: ""
        val blid = prefs.getString("blid", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        if (ip.isEmpty() || blid.isEmpty() || pass.isEmpty()) {
            return "Robot sin configurar en Ajustes" to true
        }

        // Si una pantalla de la app ya está conectada, se usa esa conexión.
        val shared = RoombaClient.active?.takeIf { it.isConnected() } // la conexión de RoombaHub
        if (shared != null) return decideAndStart(shared)

        var lastError = ""
        for (attempt in 1..3) {
            val status = StatusWaiter()
            val client = RoombaClient(status)
            client.connect(ip, blid, pass)
            val ok = status.awaitConnected(20_000)
            if (ok) {
                try {
                    return decideAndStart(client)
                } finally {
                    client.disconnect()
                    Thread.sleep(1_500)
                }
            }
            lastError = status.last
            client.disconnect()
            if (attempt < 3) Thread.sleep(20_000)
        }
        return "No se pudo conectar con el robot ($lastError). ¿Está el móvil en la wifi de casa?" to true
    }

    private fun decideAndStart(client: RoombaClient): Pair<String, Boolean> {
        // Espera a que el robot cuente en qué estado está
        val mission = waitFor(10_000) { client.lastState().optJSONObject("cleanMissionStatus") }
        val phase = mission?.optString("phase") ?: ""
        val cycle = mission?.optString("cycle") ?: ""

        if (phase in BUSY || (phase == "charge" && cycle != "none" && cycle.isNotEmpty())) {
            cleaningSeen = true
            return "El robot ya estaba limpiando (arrancó con su propia programación)" to false
        }
        if (phase == "stuck") {
            return "El robot está atascado; no se ha enviado la orden" to true
        }

        client.send("start")
        // El robot puede tardar en informar: se da por empezado si pasa a "run" o abre un ciclo
        val started = waitFor(60_000) {
            val m = client.lastState().optJSONObject("cleanMissionStatus")
            val p = m?.optString("phase") ?: ""
            val c = m?.optString("cycle") ?: ""
            if (p == "run" || (c.isNotEmpty() && c != "none")) p else null
        }
        if (started != null) cleaningSeen = true
        return if (started != null) {
            "Orden de limpiar enviada desde el móvil; el robot ha empezado" to false
        } else {
            "Orden de limpiar enviada, pero el robot no confirmó que empezara (estado: ${phase.ifEmpty { "desconocido" }})" to true
        }
    }

    private fun <T> waitFor(timeoutMs: Long, probe: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            probe()?.let { return it }
            Thread.sleep(500)
        }
        return probe()
    }

    private fun notification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ScheduleActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Limpieza programada")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    /** Escucha el estado de la conexión de un RoombaClient propio. */
    private class StatusWaiter : RoombaClient.Listener {
        private val lock = Object()
        @Volatile
        var last = ""
        private var done = false
        private var ok = false

        override fun onStatus(message: String) {
            synchronized(lock) {
                last = message
                if (message == "Conectado") {
                    ok = true; done = true; lock.notifyAll()
                } else if (message.startsWith("Error")) {
                    done = true; lock.notifyAll()
                }
            }
        }

        override fun onState(state: JSONObject) {}

        fun awaitConnected(timeoutMs: Long): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            synchronized(lock) {
                while (!done) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
                }
                return ok
            }
        }
    }
}
