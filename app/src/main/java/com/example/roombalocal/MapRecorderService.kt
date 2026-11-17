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

/**
 * Mantiene la conexión con el robot mientras limpia, aunque la pantalla se apague o salgas de la app,
 * para registrar el recorrido completo. Se para solo cuando el robot termina y vuelve a la base.
 * El robot no guarda el recorrido: si la app no está conectada, esa parte se pierde.
 */
class MapRecorderService : Service() {

    companion object {
        private const val CHANNEL = "mapa"
        private const val NOTIF = 4303
        private const val MAX_MS = 4 * 60 * 60 * 1000L

        @Volatile
        var running = false
            private set
        @Volatile
        private var lastStart = 0L

        fun start(ctx: Context) {
            val now = System.currentTimeMillis()
            if (running || now - lastStart < 10_000) return
            lastStart = now
            try {
                ctx.startForegroundService(Intent(ctx, MapRecorderService::class.java))
            } catch (_: Exception) {
                // Android no deja arrancarlo desde segundo plano: se grabará mientras la app esté abierta
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Registro del mapa", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF, n)
        }
        if (!running) {
            running = true
            Thread { loop() }.start()
        }
        return START_NOT_STICKY
    }

    private fun loop() {
        MapStore.init(this)
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoombaLocal:mapa")
        wl.acquire(MAX_MS)
        val begin = System.currentTimeMillis()
        var seenActive = false
        var idleSince = 0L
        try {
            if (!RoombaHub.attach(this, MapRecorder)) return
            while (System.currentTimeMillis() - begin < MAX_MS) {
                Thread.sleep(5_000)
                val now = System.currentTimeMillis()
                if (!RoombaHub.isConnected()) {
                    // Se perdió la conexión (wifi, robot reiniciando…): se vuelve a intentar
                    RoombaHub.attach(this, MapRecorder)
                    continue
                }
                if (MapRecorder.missionActive) {
                    seenActive = true
                    idleSince = 0L
                } else {
                    if (idleSince == 0L) idleSince = now
                    // Margen inicial para que llegue el primer estado; después, si lleva
                    // 20 s sin limpieza activa, es que ha terminado.
                    if ((seenActive || now - begin > 60_000) && now - idleSince > 20_000) break
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            RoombaHub.detach(MapRecorder)
            if (wl.isHeld) wl.release()
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("Roomba limpiando")
            .setContentText("Registrando el recorrido para el mapa")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
