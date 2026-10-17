package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Calendar

/**
 * 门缝刻度:一条 24 小时轴,把今天每次「经过门」按实际时间落点。
 *
 * 之前是把刻度均匀撒在一条线上,既读不出时间,没有事件时也只剩一条空线。
 * 现在固定的小时刻度保证任何情况下都有东西可看,事件落点让「今天什么时候
 * 最容易失去控制」变得可读;实心圆点是当前时刻。
 */
class ThresholdView(
    context: Context,
    private val events: List<GateEvent>
) : View(context) {
    private val baseline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 218, 224, 218)
        strokeWidth = dp(2).toFloat()
    }
    private val hourTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 218, 224, 218)
        strokeWidth = dp(1).toFloat()
    }
    private val continueTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        strokeWidth = dp(2).toFloat()
    }
    private val leaveTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        strokeWidth = dp(4).toFloat()
    }
    private val nowDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return
        val cy = height / 2f
        // 两端留出内缩,避免 24:00 附近的刻度和「现在」圆点被卡片边缘切掉
        val inset = dp(5).toFloat()
        val left = inset
        val right = width - inset
        val span = right - left
        canvas.drawLine(left, cy, right, cy, baseline)

        for (hour in 1 until 24) {
            val x = left + span * hour / 24f
            canvas.drawLine(x, cy - dp(3), x, cy + dp(3), hourTick)
        }

        events.forEach { event ->
            if (event.result == GateResult.SHOWN) return@forEach
            val x = left + span * dayFraction(event.occurredAt)
            val isLeave = event.result == GateResult.LEAVE
            val halfHeight = if (isLeave) dp(14) else dp(8)
            canvas.drawLine(x, cy - halfHeight, x, cy + halfHeight,
                if (isLeave) leaveTick else continueTick)
        }

        canvas.drawCircle(
            left + span * dayFraction(System.currentTimeMillis()),
            cy,
            dp(3).toFloat(),
            nowDot
        )
    }

    /** 0..1 的一天内位置。 */
    private fun dayFraction(at: Long): Float {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return (minutes / (24f * 60f)).coerceIn(0f, 1f)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
