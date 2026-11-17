package com.example.roombalocal

import android.content.Context
import org.json.JSONObject

/**
 * Registra el recorrido de la limpieza a partir del estado que publica el robot.
 * Se puede llamar desde la pantalla principal o desde MapRecorderService (segundo plano):
 * procesar el mismo estado dos veces no duplica nada.
 */
object MapRecorder : RoombaClient.Listener {

    private val ACTIVE_PHASES = setOf("run", "hmUsrDock", "hmMidMsn", "hmPostMsn", "evac")

    /** Hay una limpieza en curso (también si está en pausa o recargando a mitad). */
    @Volatile
    var missionActive = false
        private set

    /** Se avisa cuando se termina una limpieza; el parámetro indica si pasó a ser el mapa guardado. */
    @Volatile
    var onMissionFinished: ((savedAsMap: Boolean) -> Unit)? = null

    override fun onStatus(message: String) {}

    override fun onState(state: JSONObject) = process(state)

    fun process(state: JSONObject) {
        val mission = state.optJSONObject("cleanMissionStatus") ?: return
        val phase = mission.optString("phase")
        val cycle = mission.optString("cycle")
        val nMssn = mission.optInt("nMssn", -1)

        val active = phase in ACTIVE_PHASES || (cycle.isNotEmpty() && cycle != "none")
        missionActive = active

        if (active) {
            val pt = state.optJSONObject("pose")?.optJSONObject("point")
            if (pt != null) {
                MapStore.addCurrent(pt.optDouble("x").toFloat(), pt.optDouble("y").toFloat(), nMssn)
            }
        } else if (phase == "charge" && MapStore.currentCount() >= 10) {
            // El robot ha vuelto a la base y no tiene ciclo abierto: la limpieza ha terminado.
            // No depende de haber estado mirando la pantalla durante toda la limpieza.
            val savedAsMap = MapStore.finishMission()
            onMissionFinished?.invoke(savedAsMap)
        }
    }

    fun init(ctx: Context) = MapStore.init(ctx)
}
