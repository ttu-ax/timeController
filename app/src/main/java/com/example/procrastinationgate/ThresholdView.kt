package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class ThresholdView(
    context: Context,
    private val shown: Int,
    private val left: Int
) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        strokeWidth = dp(2).toFloat()
    }
    private val continueTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.surface
        strokeWidth = dp(2).toFloat()
    }
    private val leaveTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        strokeWidth = dp(4).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val centerY = height / 2f
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, line)
        if (shown == 0) return
        val count = shown.coerceAtMost(20)
        val spacing = width.toFloat() / (count + 1)
        repeat(count) { index ->
            val x = spacing * (index + 1)
            val isLeave = index >= count - left.coerceAtMost(count)
            val halfHeight = if (isLeave) dp(16).toFloat() else dp(8).toFloat()
            canvas.drawLine(
                x, centerY - halfHeight, x, centerY + halfHeight,
                if (isLeave) leaveTick else continueTick
            )
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
