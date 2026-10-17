package com.example.procrastinationgate

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

object UiTheme {
    // ---- 尺寸刻度(改动集中在这里,不要再往界面代码里写魔法数字) ----
    const val RADIUS_CARD = 18
    const val RADIUS_BUTTON = 14
    const val RADIUS_PILL = 999
    const val HEIGHT_PRIMARY = 54
    const val HEIGHT_ACTION = 46
    const val PAD_CARD = 16
    const val GAP_SECTION = 24
    const val GAP_ITEM = 10

    // ---- 字号刻度 ----
    const val TEXT_PAGE_TITLE = 30f
    const val TEXT_CARD_TITLE = 17f
    const val TEXT_BODY = 15f
    const val TEXT_CAPTION = 12f

    val canvas = Color.rgb(244, 246, 243)
    val surface = Color.WHITE
    val surfaceMuted = Color.rgb(236, 239, 235)
    val ink = Color.rgb(23, 27, 24)
    val inkSoft = Color.rgb(72, 79, 74)
    val muted = Color.rgb(119, 128, 121)
    val line = Color.rgb(218, 224, 218)
    val accent = Color.rgb(193, 239, 112)
    val accentSoft = Color.rgb(232, 248, 203)
    val warning = Color.rgb(244, 178, 108)
    val destructive = Color.rgb(186, 72, 72)

    fun shape(
        context: Context,
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeDp: Int = 1
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = context.dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(context.dp(strokeDp), it) }
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int = ink,
        weight: Int = Typeface.NORMAL
    ) = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.create("sans-serif", weight)
        includeFontPadding = false
        setLineSpacing(0f, 1.12f)
    }

    fun statusPill(context: Context, value: String, active: Boolean) =
        text(context, value, 12f, if (active) ink else inkSoft, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
            background = shape(
                context,
                if (active) accentSoft else surfaceMuted,
                999
            )
        }

    fun doorGap(context: Context, active: Boolean = true): View =
        View(context).apply {
            background = shape(context, if (active) accent else line, 999)
        }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
