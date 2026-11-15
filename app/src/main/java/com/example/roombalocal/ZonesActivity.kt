package com.example.roombalocal

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Mapa guardado: dibuja y nombra zonas, y mira cuánto cubrió de cada una la última limpieza. */
class ZonesActivity : Activity(), MapView.ZoneListener {

    private lateinit var mapView: MapView
    private lateinit var hint: TextView
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapStore.init(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        setContentView(root)

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
            text = "Mapa y zonas"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(14), 0, 0, 0)
        })
        root.addView(header)

        // Mapa editable
        mapView = MapView(this).apply {
            editable = true
            zoneListener = this@ZonesActivity
        }
        val mapCard = FrameLayout(this).apply {
            background = Ui.rounded(context, Ui.CARD, 22)
            clipToOutline = true
        }
        mapCard.addView(mapView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(mapCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        hint = TextView(this).apply {
            textSize = 13f
            setTextColor(Ui.MUTED)
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        root.addView(hint)

        // Lista de zonas
        val listCard = Ui.card(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(ScrollView(this).apply { addView(list) })
        root.addView(listCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })

        // Acciones sobre el mapa
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Usar la última limpieza como mapa"
            Ui.styleButton(this, Ui.CARD2, Ui.TEXT)
            textSize = 13f
            setOnClickListener {
                if (MapStore.useLastAsReference()) {
                    refresh()
                } else {
                    Toast.makeText(context, "Todavía no hay ninguna limpieza completa guardada", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Borrar mapa"
            Ui.styleButton(this, Ui.CARD2, Ui.DANGER)
            textSize = 13f
            setOnClickListener { confirmClear() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val zones = MapStore.zoneList()
        val cov = MapStore.coverage()
        mapView.setOverlay(
            MapStore.referenceCenters(),
            MapStore.cellSize(),
            MapStore.lastCenters(),
            zones,
            cov
        )
        hint.text = when {
            !MapStore.hasReference() ->
                "Aún no hay mapa. Se guarda solo al terminar la primera limpieza completa con la app abierta."
            zones.isEmpty() ->
                "Arrastra el dedo sobre el mapa para dibujar una zona y ponerle nombre."
            MapStore.hasLast() ->
                "Arrastra para añadir zonas. En turquesa, lo que cubrió la última limpieza."
            else -> "Arrastra para añadir más zonas."
        }

        list.removeAllViews()
        if (zones.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Sin zonas todavía"
                setTextColor(Ui.MUTED)
                textSize = 14f
            })
        }
        for ((i, z) in zones.withIndex()) {
            val c = cov[z.name]
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            r.addView(TextView(this).apply {
                text = z.name
                textSize = 16f
                setTextColor(Ui.TEXT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(TextView(this).apply {
                text = if (c != null && c >= 0) "$c %" else "—"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    when {
                        c == null || c < 0 -> Ui.MUTED
                        c >= 80 -> Ui.OK
                        c >= 40 -> Ui.WARN
                        else -> Ui.DANGER
                    }
                )
            })
            r.addView(TextView(this).apply {
                text = "✕"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Ui.MUTED)
                setOnClickListener { confirmDelete(i, z.name) }
            }, LinearLayout.LayoutParams(dp(44), dp(36)))
            list.addView(r)
        }
    }

    override fun onZoneDrawn(x1: Float, y1: Float, x2: Float, y2: Float) {
        val input = EditText(this).apply {
            hint = "Ej. Salón"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            Ui.styleField(this)
        }
        val holder = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Nombre de la zona")
            .setView(holder)
            .setPositiveButton("Guardar") { _, _ ->
                MapStore.addZone(input.text.toString(), x1, y1, x2, y2)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(index: Int, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Borrar \"$name\"")
            .setPositiveButton("Borrar") { _, _ ->
                MapStore.removeZone(index)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Borrar el mapa guardado")
            .setMessage("Las zonas se conservan. El siguiente mapa se guardará al terminar la próxima limpieza completa, o cuando pulses \"Usar la última limpieza como mapa\".")
            .setPositiveButton("Borrar") { _, _ ->
                MapStore.clearReference()
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
