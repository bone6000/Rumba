package com.example.roombalocal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/** Colores y ayudas de estilo compartidos por las pantallas. */
object Ui {
    val BG = 0xFF0B1220.toInt()
    val CARD = 0xFF152033.toInt()
    val CARD2 = 0xFF1E2B42.toInt()
    val STROKE = 0xFF2B3A55.toInt()
    val ACCENT = 0xFF22D3EE.toInt()
    val ON_ACCENT = 0xFF06222B.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val MUTED = 0xFF94A3B8.toInt()
    val OK = 0xFF4ADE80.toInt()
    val WARN = 0xFFFBBF24.toInt()
    val DANGER = 0xFFF87171.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            setColor(color)
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    fun styleButton(b: Button, bg: Int, fg: Int, radiusDp: Int = 14) {
        val ctx = b.context
        b.isAllCaps = false
        b.setTextColor(fg)
        b.textSize = 15f
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(ctx, 10), dp(ctx, 14), dp(ctx, 10), dp(ctx, 14))
        b.background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            rounded(ctx, bg, radiusDp),
            null
        )
    }

    fun styleField(e: EditText) {
        val ctx = e.context
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        e.setSingleLine(true)
        e.background = rounded(ctx, CARD2, 12, STROKE)
        e.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 20)
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
    }
}
