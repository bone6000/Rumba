package com.example.roombalocal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
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

/** Ajustes: datos de conexión con el robot y su detección automática. */
class SettingsActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var ipEdit: EditText
    private lateinit var blidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var statusText: TextView
    private val ui = Handler(Looper.getMainLooper())

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
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)

        // Cabecera con botón de volver
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "←"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.rounded(context, Ui.CARD, 14)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "Ajustes"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(14), 0, 0, 0)
        })
        root.addView(header)

        // Detección automática
        val auto = Ui.card(this)
        auto.addView(sectionTitle("Detección automática"))
        auto.addView(hint(
            "Con el móvil en la misma wifi que el robot, busca su IP y su identificador (BLID), " +
                "y después pídele la contraseña."
        ))
        val searchBtn = Button(this).apply {
            text = "Buscar robot en la red"
            Ui.styleButton(this, Ui.ACCENT, Ui.ON_ACCENT)
            setOnClickListener { searchRobot() }
        }
        auto.addView(searchBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        val pwBtn = Button(this).apply {
            text = "Obtener contraseña del robot"
            Ui.styleButton(this, Ui.CARD2, Ui.TEXT)
            setOnClickListener { askPairingMode() }
        }
        auto.addView(pwBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        root.addView(auto, cardParams())

        // Datos manuales
        val manual = Ui.card(this)
        manual.addView(sectionTitle("Datos del robot"))
        ipEdit = field("192.168.1.50", prefs.getString("ip", "") ?: "")
        blidEdit = field("Identificador (BLID)", prefs.getString("blid", "") ?: "")
        passEdit = field("Contraseña", prefs.getString("pass", "") ?: "")
        passEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        manual.addView(label("IP del robot"))
        manual.addView(ipEdit, fieldParams())
        manual.addView(label("BLID (usuario)"))
        manual.addView(blidEdit, fieldParams())
        manual.addView(label("Contraseña"))
        manual.addView(passEdit, fieldParams())
        root.addView(manual, cardParams())

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.MUTED)
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        val save = Button(this).apply {
            text = "Guardar y volver"
            Ui.styleButton(this, Ui.ACCENT, Ui.ON_ACCENT)
            setOnClickListener {
                saveFields()
                finish()
            }
        }
        root.addView(save, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
    }

    override fun onPause() {
        super.onPause()
        saveFields()
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(16)
    }

    private fun fieldParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(4)
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

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Ui.MUTED)
        setPadding(0, dp(12), 0, 0)
    }

    private fun field(hintText: String, value: String): EditText = EditText(this).apply {
        hint = hintText
        setText(value)
        Ui.styleField(this)
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
        statusText.text = "Encontrado: ${r.name.ifEmpty { "Roomba" }} (${r.ip}). Ahora puedes obtener la contraseña."
    }

    private fun askPairingMode() {
        if (ipEdit.text.toString().trim().isEmpty()) {
            statusText.text = "Primero busca el robot o escribe la IP."
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
                    statusText.text = "Contraseña obtenida y guardada. Ya puedes volver."
                }
            } catch (e: Exception) {
                ui.post { statusText.text = "No se pudo obtener la contraseña:\n${e.message}" }
            }
        }.start()
    }
}
