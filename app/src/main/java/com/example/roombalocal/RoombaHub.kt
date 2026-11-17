package com.example.roombalocal

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Una sola conexión con el robot para toda la app.
 * El robot solo admite una conexión MQTT y todas usan el mismo clientId (el BLID): si cada pantalla
 * abría la suya, al pasar de una a otra se pisaban y la nueva podía quedarse sin datos.
 * Las pantallas se apuntan en onStart y se borran en onStop; la conexión se cierra unos segundos
 * después de que se vaya la última, para dejar libre el robot.
 */
object RoombaHub : RoombaClient.Listener {

    private val listeners = CopyOnWriteArraySet<RoombaClient.Listener>()
    private val client = RoombaClient(this)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastStatus = "Sin conexión"
    @Volatile
    private var creds = ""
    @Volatile
    private var connecting = false

    private val disconnectTask = Runnable {
        if (listeners.isEmpty()) {
            client.disconnect()
            creds = ""
            connecting = false
            lastStatus = "Sin conexión"
        }
    }

    /** Apunta una pantalla. Devuelve false si faltan IP, BLID o contraseña. */
    fun attach(ctx: Context, l: RoombaClient.Listener): Boolean {
        main.removeCallbacks(disconnectTask)
        listeners.add(l)
        val prefs = ctx.getSharedPreferences("roomba", Context.MODE_PRIVATE)
        val ip = prefs.getString("ip", "") ?: ""
        val blid = prefs.getString("blid", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        if (ip.isEmpty() || blid.isEmpty() || pass.isEmpty()) return false
        val key = "$ip|$blid|$pass"
        if (key == creds && (client.isConnected() || connecting)) {
            // Ya hay conexión (o se está abriendo): se le pasa a la pantalla lo último que se sabe
            l.onStatus(lastStatus)
            if (client.isConnected()) l.onState(client.lastState())
            return true
        }
        creds = key
        connecting = true
        client.connect(ip, blid, pass)
        return true
    }

    fun detach(l: RoombaClient.Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) {
            main.removeCallbacks(disconnectTask)
            main.postDelayed(disconnectTask, 3000)
        }
    }

    fun send(command: String) = client.send(command)
    fun sendDelta(desired: JSONObject, label: String) = client.sendDelta(desired, label)
    fun isConnected() = client.isConnected()
    fun lastState(): JSONObject = client.lastState()

    override fun onStatus(message: String) {
        lastStatus = message
        when {
            message == "Conectado" -> connecting = false
            message.startsWith("Error") || message.startsWith("Conexión perdida") -> {
                connecting = false
                creds = "" // la próxima pantalla que se apunte vuelve a conectar
            }
        }
        for (l in listeners) l.onStatus(message)
    }

    override fun onState(state: JSONObject) {
        for (l in listeners) l.onState(state)
    }
}
