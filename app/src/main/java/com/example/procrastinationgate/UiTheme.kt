package com.example.procrastinationgate

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView

object UiTheme {
    // ---- 尺寸刻度 ----
    const val RADIUS_CARD = 20
    const val RADIUS_BUTTON = 14
    const val RADIUS_PILL = 999
    const val HEIGHT_PRIMARY = 52
    const val HEIGHT_ACTION = 44
    const val PAD_CARD = 18
    const val GAP_SECTION = 22
    const val GAP_ITEM = 10

    // ---- 字号刻度 ----
    const val TEXT_PAGE_TITLE = 28f
    const val TEXT_CARD_TITLE = 17f
    const val TEXT_BODY = 15f
    const val TEXT_CAPTION = 12f

    // ---- 雅致翡墨与云雾清白主题色体系 ----
    val canvas = Color.rgb(247, 249, 246)         // 温润云雾白背景
    val surface = Color.WHITE                     // 纯净瓷白卡片
    val surfaceMuted = Color.rgb(241, 245, 242)    // 浅灰松玉背景
    val ink = Color.rgb(17, 26, 20)               // 深翠墨黑
    val inkSoft = Color.rgb(60, 75, 65)           // 冷杉深绿灰
    val muted = Color.rgb(122, 137, 126)          // 静杉中灰
    val line = Color.rgb(226, 234, 227)           // 微米极细柔边
    val accent = Color.rgb(46, 196, 118)          // 晨光竹翡绿（主高亮）
    val accentSoft = Color.rgb(235, 247, 240)      // 露珠浅翠（胶囊/微光背景）
    val accentGlow = Color.rgb(74, 222, 128)      // 呼吸翡光（门缝指示）
    val warning = Color.rgb(235, 146, 60)         // 落日暖杏
    val warningSoft = Color.rgb(254, 244, 233)
    val destructive = Color.rgb(220, 76, 76)      // 陶土赤红
    val destructiveSoft = Color.rgb(254, 237, 237)

    // 核心深色卡底色
    val heroSurface = Color.rgb(18, 27, 22)
    val heroSurfaceAlt = Color.rgb(26, 38, 30)
    val heroLine = Color.rgb(42, 58, 48)

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

    fun gradient(
        context: Context,
        colors: IntArray,
        radiusDp: Int,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR,
        strokeColor: Int? = null,
        strokeDp: Int = 1
    ) = GradientDrawable(orientation, colors).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(context.dp(strokeDp), it) }
    }

    fun ripple(
        context: Context,
        contentColor: Int,
        pressedColor: Int = accentSoft,
        radiusDp: Int = RADIUS_BUTTON,
        strokeColor: Int? = null,
        strokeDp: Int = 1
    ): RippleDrawable {
        val content = shape(context, contentColor, radiusDp, strokeColor, strokeDp)
        val mask = shape(context, Color.WHITE, radiusDp)
        val colorStateList = ColorStateList.valueOf(pressedColor)
        return RippleDrawable(colorStateList, content, mask)
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
        setLineSpacing(0f, 1.15f)
    }

    fun statusPill(context: Context, value: String, active: Boolean) =
        text(context, value, 12f, if (active) Color.rgb(28, 122, 72) else inkSoft, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
            background = shape(
                context,
                if (active) accentSoft else surfaceMuted,
                RADIUS_PILL,
                if (active) Color.argb(40, 46, 196, 118) else null
            )
        }

    fun chip(context: Context, label: String, active: Boolean = false) =
        text(context, label, 12f, if (active) Color.rgb(28, 122, 72) else inkSoft).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
            background = shape(
                context,
                if (active) accentSoft else surfaceMuted,
                10,
                if (active) Color.argb(35, 46, 196, 118) else null
            )
        }

    fun doorGap(context: Context, active: Boolean = true): View =
        View(context).apply {
            background = shape(context, if (active) accent else line, RADIUS_PILL)
        }

    fun createAppIconView(
        context: Context,
        packageName: String?,
        label: String,
        sizeDp: Int = 42,
        radiusDp: Int = 11
    ): View {
        val sizePx = context.dp(sizeDp)
        val radiusPx = context.dp(radiusDp).toFloat()

        if (!packageName.isNullOrBlank()) {
            val iconDrawable = runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
            if (iconDrawable != null) {
                return ImageView(context).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    background = shape(context, Color.WHITE, radiusDp, line, 1)
                }
            }
        }

        // 兜底单字头像
        val firstChar = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "门"
        return TextView(context).apply {
            text = firstChar
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = shape(context, accentSoft, radiusDp, Color.argb(40, 46, 196, 118), 1)
        }
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.dp(value: Float): Float = value * resources.displayMetrics.density
