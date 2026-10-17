package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.util.Calendar

/**
 * 门缝刻度: 精密 24 小时微光轴。
 * 把今天每次「经过门」按实际时间落点，让清醒抉择与时间分布具象化。
 */
class ThresholdView(
    context: Context,
    private val events: List<GateEvent>
) : View(context) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(45, 255, 255, 255)
        strokeWidth = dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 255, 255)
        strokeWidth = dp(1).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = dp(1.5f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        textSize = dp(9).toFloat()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val continueTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = dp(2.2f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val leaveTickGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 74, 222, 128)
        strokeWidth = dp(6).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val leaveTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accentGlow
        strokeWidth = dp(3.2f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val bypassTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.warning
        strokeWidth = dp(2.5f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private val nowHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 74, 222, 128)
        style = Paint.Style.FILL
    }

    private val nowDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accentGlow
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return

        val cy = height * 0.42f
        val inset = dp(12).toFloat()
        val left = inset
        val right = width - inset
        val span = right - left

        // 1. 基底刻度线
        canvas.drawLine(left, cy, right, cy, trackPaint)

        // 2. 小时刻度与关键时间标签 (00, 06, 12, 18, 24)
        val majorHours = setOf(0, 6, 12, 18, 24)
        for (hour in 0..24) {
            val x = left + span * (hour / 24f)
            if (hour in majorHours) {
                canvas.drawLine(x, cy - dp(4), x, cy + dp(4), majorTickPaint)
                val label = String.format("%02d", hour)
                canvas.drawText(label, x, cy + dp(15), textPaint)
            } else if (hour % 2 == 0) {
                canvas.drawLine(x, cy - dp(2.5f), x, cy + dp(2.5f), minorTickPaint)
            }
        }

        // 3. 今日事件落点
        events.forEach { event ->
            if (event.result == GateResult.SHOWN) return@forEach
            val x = left + span * dayFraction(event.occurredAt)
            when (event.result) {
                GateResult.LEAVE -> {
                    val halfHeight = dp(12).toFloat()
                    // 双层发光刻度
                    canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight, leaveTickGlow)
                    canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight, leaveTick)
                }
                GateResult.BYPASS -> {
                    val halfHeight = dp(9).toFloat()
                    canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight, bypassTick)
                }
                else -> {
                    val halfHeight = dp(8).toFloat()
                    canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight, continueTick)
                }
            }
        }

        // 4. 当前时刻点光
        val nowX = left + span * dayFraction(System.currentTimeMillis())
        canvas.drawCircle(nowX, cy, dp(7).toFloat(), nowHalo)
        canvas.drawCircle(nowX, cy, dp(3.2f).toFloat(), nowDot)
    }

    private fun dayFraction(at: Long): Float {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return (minutes / (24f * 60f)).coerceIn(0f, 1f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
