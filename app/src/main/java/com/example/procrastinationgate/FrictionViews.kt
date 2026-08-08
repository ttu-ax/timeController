package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** 点按序列:依次点亮所有圆点,点错不惩罚。 */
class TapSequenceView(context: Context) : View(context) {
    var onCompleted: (() -> Unit)? = null
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.line }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private val dots = mutableListOf<Pair<Float, Float>>()
    private var current = 0
    private var completed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dots.clear()
        current = 0
        completed = false
        val count = 4
        val margin = dp(48)
        val minGap = dp(34) * 2.6f
        var attempts = 0
        while (dots.size < count && attempts < 300) {
            attempts++
            val x = margin + Random.nextFloat() * (w - 2 * margin)
            val y = margin + Random.nextFloat() * (h - 2 * margin)
            val tooClose = dots.any { hypot(it.first - x, it.second - y) < minGap }
            if (!tooClose) dots += x to y
        }
        if (dots.size < count) {
            // 随机失败时兜底:固定四角布局
            dots.clear()
            val cx = w / 2f
            val cy = h / 2f
            val spread = min(w, h) * 0.3f
            dots += cx - spread to cy - spread
            dots += cx + spread to cy - spread
            dots += cx - spread to cy + spread
            dots += cx + spread to cy + spread
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dotR = dp(30).toFloat()
        dots.forEachIndexed { index, (x, y) ->
            val paint = when {
                index < current -> donePaint
                index == current -> activePaint
                else -> dotPaint
            }
            canvas.drawCircle(x, y, dotR, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && current < dots.size) {
            val (tx, ty) = dots[current]
            if (hypot(event.x - tx, event.y - ty) < dp(60)) {
                current++
                invalidate()
                if (current >= dots.size) {
                    completed = true
                    onCompleted?.invoke()
                }
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 拖滑块:把圆钮拖到轨道尽头。 */
class DragSliderView(context: Context) : View(context) {
    var onCompleted: (() -> Unit)? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.line }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private var progress = 0f
    private var completed = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(8).toFloat()
        val right = (width - dp(8)).toFloat()
        val trackTop = height / 2f - dp(10)
        val trackBottom = height / 2f + dp(10)
        val radius = dp(10).toFloat()
        val knobX = left + progress * (right - left)
        canvas.drawRoundRect(left, trackTop, right, trackBottom, radius, radius, trackPaint)
        if (progress > 0f) {
            canvas.drawRoundRect(left, trackTop, knobX, trackBottom, radius, radius, fillPaint)
        }
        canvas.drawCircle(knobX, height / 2f, dp(26).toFloat(), knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val left = dp(8).toFloat()
                val right = (width - dp(8)).toFloat()
                progress = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                invalidate()
                if (progress >= 0.96f) {
                    completed = true
                    onCompleted?.invoke()
                }
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 捏合推开:双指按住并向内合拢。 */
class PinchView(context: Context) : View(context) {
    var onCompleted: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(15).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private var startDistance = 0f
    private var pinching = false
    private var completed = false
    private var first = PointF()
    private var second = PointF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText("双指按住，向内合拢", width / 2f, height / 2f - dp(56), hintPaint)
        if (pinching) {
            canvas.drawCircle(first.x, first.y, dp(30).toFloat(), paint)
            canvas.drawCircle(second.x, second.y, dp(30).toFloat(), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    first = PointF(event.getX(0), event.getY(0))
                    second = PointF(event.getX(1), event.getY(1))
                    startDistance = hypot(first.x - second.x, first.y - second.y)
                    pinching = true
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && pinching) {
                    first = PointF(event.getX(0), event.getY(0))
                    second = PointF(event.getX(1), event.getY(1))
                    val distance = hypot(first.x - second.x, first.y - second.y)
                    invalidate()
                    if (startDistance > 0f && distance <= startDistance * 0.55f) {
                        completed = true
                        onCompleted?.invoke()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                pinching = false
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
