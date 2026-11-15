package com.example.roombalocal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity(), RoombaClient.Listener {

    private lateinit var prefs: SharedPreferences
    private lateinit var ipEdit: EditText
    private lateinit var blidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var stateText: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val robot = RoombaClient(this)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("roomba", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Roomba Local"
            textSize = 24f
        })

        ipEdit = field(root, "IP del robot (ej. 192.168.1.50)", prefs.getString("ip", "") ?: "")
        blidEdit = field(root, "BLID (usuario)", prefs.getString("blid", "") ?: "")
        passEdit = field(root, "Contraseña del robot", prefs.getString("pass", "") ?: "")
        passEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        val discoveryRow = row(root)
        discoveryRow.addView(Button(this).apply {
            text = "Buscar robot"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { searchRobot() }
        })
        discoveryRow.addView(Button(this).apply {
            text = "Obtener contraseña"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { askPairingMode() }
        })

        root.addView(button("Guardar y conectar") {
            saveFields()
            connect()
        })

        statusText = TextView(this).apply {
            text = "Sin conexión"
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(statusText)
        stateText = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(stateText)

        val row1 = row(root)
        row1.addView(cmdButton("Limpiar", "start"))
        row1.addView(cmdButton("Pausa", "pause"))
        val row2 = row(root)
        row2.addView(cmdButton("Reanudar", "resume"))
        row2.addView(cmdButton("Parar", "stop"))
        val row3 = row(root)
        row3.addView(cmdButton("A la base", "dock"))
        row3.addView(cmdButton("Localizar", "find"))
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    // Al salir de la app se libera la única conexión MQTT que admite el robot.
    override fun onStop() {
        super.onStop()
        robot.disconnect()
    }

    private fun connect() {
        val ip = prefs.getString("ip", "") ?: ""
        val blid = prefs.getString("blid", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        if (ip.isEmpty() || blid.isEmpty() || pass.isEmpty()) {
            statusText.text = "Rellena IP, BLID y contraseña"
            return
        }
        robot.connect(ip, blid, pass)
    }

    private fun saveFields() {
        prefs.edit()
            .putString("ip", ipEdit.text.toString().trim())
            .putString("blid", blidEdit.text.toString().trim())
            .putString("pass", passEdit.text.toString().trim())
            .apply()
    }

    private fun searchRobot() {
        statusText.text = "Buscando robots en la red…"
        Thread {
            val list = try {
                RoombaDiscovery.discover(this)
            } catch (e: Exception) {
                ui.post { statusText.text = "Error buscando: ${e.message}" }
                return@Thread
            }
            ui.post {
                when {
                    list.isEmpty() -> statusText.text =
                        "No se ha encontrado ningún robot. Comprueba que el móvil está en la misma wifi, o escribe la IP a mano."
                    list.size == 1 -> applyFound(list[0])
                    else -> {
                        val names = list.map { "${it.name.ifEmpty { "Roomba" }} · ${it.ip}" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Elige el robot")
                            .setItems(names) { _, i -> applyFound(list[i]) }
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun applyFound(r: FoundRobot) {
        ipEdit.setText(r.ip)
        blidEdit.setText(r.blid)
        saveFields()
        statusText.text = "Encontrado: ${r.name.ifEmpty { "Roomba" }} (${r.ip})"
    }

    private fun askPairingMode() {
        if (ipEdit.text.toString().trim().isEmpty()) {
            statusText.text = "Primero pulsa Buscar robot o escribe la IP"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Modo emparejamiento")
            .setMessage(
                "1. Cierra la app oficial de iRobot.\n" +
                    "2. Con el robot en la base y encendido, mantén pulsado HOME " +
                    "(en el 675, DOCK + SPOT) hasta que suene un tono y parpadee el símbolo wifi; suéltalo.\n" +
                    "3. Pulsa Continuar enseguida, antes de que pase un minuto."
            )
            .setPositiveButton("Continuar") { _, _ -> fetchPassword() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun fetchPassword() {
        val ip = ipEdit.text.toString().trim()
        statusText.text = "Pidiendo la contraseña al robot…"
        robot.disconnect() // el robot solo admite una conexión a la vez
        Thread {
            try {
                val pw = RoombaDiscovery.fetchPassword(ip)
                var blid = blidEdit.text.toString().trim()
                if (blid.isEmpty()) {
                    blid = RoombaDiscovery.discover(this, 3000).firstOrNull { it.ip == ip }?.blid ?: ""
                }
                ui.post {
                    passEdit.setText(pw)
                    if (blid.isNotEmpty()) blidEdit.setText(blid)
                    saveFields()
                    statusText.text = "Contraseña obtenida y guardada"
                    connect()
                }
            } catch (e: Exception) {
                ui.post { statusText.text = "No se pudo obtener la contraseña:\n${e.message}" }
            }
        }.start()
    }

    private fun field(parent: LinearLayout, hint: String, value: String): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
        }
        parent.addView(e)
        return e
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        parent.addView(r, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return r
    }

    private fun cmdButton(label: String, command: String) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { robot.send(command) }
    }

    override fun onStatus(message: String) {
        ui.post { statusText.text = message }
    }

    override fun onState(state: JSONObject) {
        val bat = if (state.has("batPct")) "${state.optInt("batPct")}%" else "?"
        val phase = state.optJSONObject("cleanMissionStatus")?.optString("phase") ?: ""
        val binFull = state.optJSONObject("bin")?.optBoolean("full", false) ?: false
        val txt = buildString {
            append("Batería: $bat\n")
            append("Estado: ${translatePhase(phase)}")
            if (binFull) append("\nDepósito lleno")
        }
        ui.post { stateText.text = txt }
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
