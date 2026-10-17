package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** 点按序列:依次点亮所有圆点,点错不惩罚。 */
class TapSequenceView(context: Context, private val dotCount: Int = 4) : View(context) {
    var onCompleted: (() -> Unit)? = null
    init { contentDescription = "点按序列:依次点亮全部圆点" }
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
        val count = dotCount.coerceIn(3, 6)
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
class DragSliderView(context: Context, private val thresholdPercent: Int = 96) : View(context) {
    var onCompleted: (() -> Unit)? = null
    init { contentDescription = "拖滑块:把圆钮拖到轨道尽头" }
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
                if (event.actionMasked == MotionEvent.ACTION_DOWN) claimGesture(true)
                val left = dp(8).toFloat()
                val right = (width - dp(8)).toFloat()
                progress = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                invalidate()
                if (progress >= thresholdPercent.coerceIn(50, 100) / 100f) {
                    completed = true
                    onCompleted?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> claimGesture(false)
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 向内推合:单指按住外侧,向屏幕中线推,把两扇门板合上(替代原双指捏合)。 */
class SqueezeView(context: Context, private val targetPercent: Int = 55) : View(context) {
    var onCompleted: (() -> Unit)? = null
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.line }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(15).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private var startDistance = 0f
    private var currentDistance = 0f
    private var squeezing = false
    private var completed = false

    init {
        contentDescription = "向内推合门板:按住任意一侧,向屏幕中线推"
    }

    /** 门缝半宽(px):手指越靠近中线,两扇门板合得越紧。 */
    private fun gapHalf(): Float {
        val half = width / 2f
        return when {
            completed -> half * 0.04f
            // 上限与静止态一致,避免刚按下时门板突然跳一下
            squeezing -> currentDistance.coerceIn(half * 0.04f, half * 0.62f)
            else -> half * 0.62f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val panelH = dp(58).toFloat()
        val gap = gapHalf()
        val radius = dp(16).toFloat()
        // 门缝底色
        canvas.drawRoundRect(0f, cy - panelH, width.toFloat(), cy + panelH, radius, radius, trackPaint)
        // 两扇门板:由两侧向中线合拢
        canvas.drawRect(0f, cy - panelH, cx - gap, cy + panelH, panelPaint)
        canvas.drawRect(cx + gap, cy - panelH, width.toFloat(), cy + panelH, panelPaint)
        // 门缝中线
        canvas.drawRect(
            cx - dp(2), cy - panelH * 0.8f, cx + dp(2), cy + panelH * 0.8f,
            if (completed) donePaint else trackPaint
        )
        canvas.drawText(
            if (completed) "门已合上" else "按住任意一侧，向屏幕中线推",
            width / 2f, height - dp(12).toFloat(), hintPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        val cx = width / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val distance = abs(event.x - cx)
                // 必须从外侧起手,否则在中线附近点一下就能过
                if (distance >= width * 0.30f) {
                    startDistance = distance
                    currentDistance = distance
                    squeezing = true
                    claimGesture(true)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> if (squeezing) {
                currentDistance = abs(event.x - cx)
                invalidate()
                if (startDistance > 0f &&
                    currentDistance <= startDistance * targetPercent.coerceIn(30, 90) / 100f
                ) {
                    completed = true
                    claimGesture(false)
                    invalidate()
                    onCompleted?.invoke()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                squeezing = false
                claimGesture(false)
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
