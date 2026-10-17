package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
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

/**
 * 翻转手机:屏幕朝下保持约一秒,再翻回来。
 *
 * 用重力传感器(没有则退回加速度计),不需要任何权限。这是唯一一种离开屏幕的
 * 摩擦方式,单手可用,也对读屏用户友好(靠震动反馈)。
 * 设备没有传感器时退化成"点一下继续",避免变成一扇推不开的门。
 */
class FlipSensorView(context: Context) : View(context), SensorEventListener {
    var onCompleted: (() -> Unit)? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val faceDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        style = Paint.Style.FILL
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        style = Paint.Style.STROKE
        strokeWidth = dp(4).toFloat()
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        textSize = dp(17).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }

    private val hasSensor = sensor != null
    private var faceDownSince = 0L
    private var sawFaceDown = false
    private var completed = false

    init {
        contentDescription = if (hasSensor) {
            "把手机屏幕朝下保持一秒，再翻回来"
        } else {
            "此设备没有重力传感器，点一下即可继续"
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onDetachedFromWindow() {
        sensorManager.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (completed || event.values.size < 3) return
        val z = event.values[2]
        val now = SystemClock.uptimeMillis()
        if (z < -6f) {
            if (faceDownSince == 0L) faceDownSince = now
            if (!sawFaceDown && now - faceDownSince >= 900L) {
                sawFaceDown = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        } else if (z > 6f) {
            faceDownSince = 0L
            if (sawFaceDown && !completed) {
                completed = true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onCompleted?.invoke()
            }
        }
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 没有传感器的设备:用一次点击兜底,不让用户卡在这扇门上
        if (!hasSensor && !completed && event.actionMasked == MotionEvent.ACTION_UP) {
            completed = true
            onCompleted?.invoke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f - dp(6)
        val w = min(width, height) * 0.30f
        val h = w * 1.7f
        val body = android.graphics.RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        val radius = dp(12).toFloat()
        canvas.drawRoundRect(body, radius, radius, if (sawFaceDown) faceDownPaint else idlePaint)
        canvas.drawRoundRect(body, radius, radius, outlinePaint)

        val label = when {
            !hasSensor -> "点一下继续"
            completed -> "门开了"
            sawFaceDown -> "翻回来"
            else -> "先翻过去"
        }
        canvas.drawText(label, cx, height - dp(34).toFloat(), textPaint)
        val hint = if (hasSensor) "屏幕朝下保持一秒，再翻回正面" else "此设备没有重力传感器"
        canvas.drawText(hint, cx, height - dp(12).toFloat(), hintPaint)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/**
 * 心算:算出一道小题并选出答案。答错换一题,不累计惩罚。
 *
 * 这是唯一无法靠肌肉记忆或乱点绕过的类型,用来替代困难档里堆时长的记忆类。
 */
class MentalMathView(context: Context, private val level: Int) : LinearLayout(context) {
    var onCompleted: (() -> Unit)? = null

    private val questionView = TextView(context).apply {
        textSize = 26f
        setTextColor(UiTheme.ink)
        gravity = android.view.Gravity.CENTER
        includeFontPadding = false
    }
    private val hintView = TextView(context).apply {
        textSize = 13f
        setTextColor(UiTheme.muted)
        gravity = android.view.Gravity.CENTER
        includeFontPadding = false
        text = "答错会换一题，不会累积惩罚"
    }
    private val optionButtons = mutableListOf<Button>()
    private var answer = 0

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        // 不在容器上设 contentDescription:里面有真正的按钮,设了会把子控件从读屏树里屏蔽掉
        addView(questionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listOf(0, 1).forEach {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = android.view.Gravity.CENTER
            }
            repeat(2) {
                val button = Button(context).apply {
                    textSize = 20f
                    isAllCaps = false
                    setTextColor(UiTheme.ink)
                    background = UiTheme.shape(context, UiTheme.accentSoft, 14, UiTheme.line)
                    setOnClickListener { onOptionTapped(text.toString().toIntOrNull()) }
                }
                optionButtons += button
                row.addView(button, LayoutParams(0, dp(58), 1f).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                })
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        addView(hintView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        newQuestion()
    }

    private fun newQuestion() {
        val a: Int
        val b: Int
        val op: String
        when (level.coerceIn(1, 3)) {
            1 -> {
                op = if (Random.nextBoolean()) "+" else "-"
                a = Random.nextInt(11, 30)
                b = if (op == "-") Random.nextInt(2, a - 1) else Random.nextInt(2, 10)
                answer = if (op == "-") a - b else a + b
            }
            2 -> {
                op = "×"
                a = Random.nextInt(3, 10)
                b = Random.nextInt(3, 10)
                answer = a * b
            }
            else -> {
                op = "×"
                a = Random.nextInt(11, 20)
                b = Random.nextInt(3, 10)
                answer = a * b
            }
        }
        questionView.text = "$a $op $b = ?"
        hintView.text = "答错会换一题，不会累积惩罚"

        val values = linkedSetOf(answer)
        while (values.size < 4) {
            val candidate = answer + listOf(-3, -2, -1, 1, 2, 3).random()
            if (candidate > 0) values.add(candidate)
        }
        values.shuffled().forEachIndexed { index, value ->
            optionButtons.getOrNull(index)?.let { it.text = value.toString() }
        }
        announceForAccessibility(questionView.text)
    }

    private fun onOptionTapped(value: Int?) {
        if (value == null) return
        if (value == answer) {
            onCompleted?.invoke()
        } else {
            hintView.text = "不是这个数，换一题再试"
            newQuestion()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/**
 * 计数:数清屏幕上某一种图形的个数并选出数量。
 *
 * 每轮随机决定问哪一种——空心/实心 × 圆/三角形/正方形,共 6 种问法;
 * 答案也在 2..上限 之间随机。固定答案会让人记住"永远数 N 个",摩擦就失效了。
 *
 * 场上同时存在三类干扰项,必须同时看"形状"和"填充":
 * 同形状相反填充、不同形状相同填充、两者都不同。
 *
 * 比目标扫描多一层——必须真的注视并数完,没法靠挨个点试出来。
 * 答错换一轮,不累计惩罚。
 */
class CountShapesView(context: Context, private val targetCount: Int) : LinearLayout(context) {
    var onCompleted: (() -> Unit)? = null

    private val questionView = TextView(context).apply {
        textSize = 20f
        setTextColor(UiTheme.ink)
        gravity = android.view.Gravity.CENTER
        includeFontPadding = false
    }
    private val board = ShapesBoard(context)
    private val optionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
    }
    private val hintView = TextView(context).apply {
        textSize = 13f
        setTextColor(UiTheme.muted)
        gravity = android.view.Gravity.CENTER
        includeFontPadding = false
    }
    private var answer = 0
    private var answerLabel = ""

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        // 容器里已有按钮,不再额外设置 contentDescription
        addView(questionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(board, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        addView(optionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(hintView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        newRound()
    }

    private fun newRound() {
        val askedKind = Random.nextInt(3)
        val askedHollow = Random.nextBoolean()
        val maxAnswer = targetCount.coerceIn(3, 8)
        // 答案必须每轮随机:固定答案会让人记住"永远数 N 个",摩擦就失效了
        answer = Random.nextInt(2, maxAnswer + 1)
        answerLabel = shapeLabel(askedKind, askedHollow)
        questionView.text = "屏幕上有几个$answerLabel？"

        val pieces = mutableListOf<Piece>()
        repeat(answer) { pieces += Piece(askedKind, askedHollow) }
        val otherKinds = (0..2).filter { it != askedKind }
        // 干扰项三类各至少一个:同形状相反填充、不同形状相同填充、两者都不同。
        // 这样不能只数"空心的"或只数"三角形的",必须同时看形状和填充。
        pieces += Piece(askedKind, !askedHollow)
        pieces += Piece(otherKinds.random(), askedHollow)
        pieces += Piece(otherKinds.random(), !askedHollow)
        repeat((TOTAL_PIECES - pieces.size).coerceAtLeast(0)) {
            pieces += when (Random.nextInt(3)) {
                0 -> Piece(askedKind, !askedHollow)
                1 -> Piece(otherKinds.random(), askedHollow)
                else -> Piece(otherKinds.random(), !askedHollow)
            }
        }

        board.show(pieces)
        hintView.text = "数错了会换一轮，不会累积惩罚"
        renderOptions()
    }

    private fun shapeLabel(kind: Int, hollow: Boolean): String {
        val fill = if (hollow) "空心" else "实心"
        val shape = when (kind) {
            0 -> "圆"
            1 -> "三角形"
            else -> "正方形"
        }
        return fill + shape
    }

    private fun renderOptions() {
        optionRow.removeAllViews()
        val values = linkedSetOf(answer)
        while (values.size < 3) {
            val candidate = answer + listOf(-2, -1, 1, 2, 3).random()
            if (candidate >= 1) values.add(candidate)
        }
        values.shuffled().forEach { value ->
            optionRow.addView(Button(context).apply {
                text = value.toString()
                textSize = 20f
                isAllCaps = false
                setTextColor(UiTheme.ink)
                contentDescription = "$value 个$answerLabel"
                background = UiTheme.shape(context, UiTheme.accentSoft, 14, UiTheme.line)
                setOnClickListener {
                    if (value == answer) {
                        onCompleted?.invoke()
                    } else {
                        hintView.text = "不对，重新数一次"
                        newRound()
                    }
                }
            }, LayoutParams(0, dp(54), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** 一块待绘制的图形:kind 0 圆 / 1 三角形 / 2 正方形。 */
    private data class Piece(val kind: Int, val hollow: Boolean)

    /** 绘制图形阵。坐标用 0..1 归一化,布局完成后再映射到像素。 */
    private class ShapesBoard(context: Context) : View(context) {
        private class Placed(
            val xn: Float,
            val yn: Float,
            val kind: Int,
            val hollow: Boolean,
            val size: Float
        )

        private val placed = mutableListOf<Placed>()
        private var cols = 3
        private var rows = 3
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UiTheme.ink
            style = Paint.Style.STROKE
            strokeWidth = dp(4).toFloat()
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UiTheme.ink
            style = Paint.Style.FILL
        }

        fun show(pieces: List<Piece>) {
            placed.clear()
            val total = pieces.size.coerceAtLeast(1)
            cols = ceil(sqrt(total.toDouble())).toInt().coerceAtLeast(2)
            rows = ceil(total / cols.toDouble()).toInt().coerceAtLeast(1)
            val cells = (0 until cols * rows).shuffled().take(total)
            pieces.shuffled().forEachIndexed { index, piece ->
                val cell = cells[index]
                val row = cell / cols
                val col = cell % cols
                placed += Placed(
                    xn = (col + 0.5f) / cols + (Random.nextFloat() - 0.5f) * (0.5f / cols),
                    yn = (row + 0.5f) / rows + (Random.nextFloat() - 0.5f) * (0.5f / rows),
                    kind = piece.kind,
                    hollow = piece.hollow,
                    size = 0.78f + Random.nextFloat() * 0.34f
                )
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (placed.isEmpty()) return
            val cellW = width / cols.toFloat()
            val cellH = height / rows.toFloat()
            val base = min(cellW, cellH) * 0.30f
            placed.forEach { piece ->
                val cx = piece.xn * width
                val cy = piece.yn * height
                val r = base * piece.size
                val paint = if (piece.hollow) strokePaint else fillPaint
                when (piece.kind) {
                    0 -> canvas.drawCircle(cx, cy, r, paint)
                    1 -> {
                        val path = android.graphics.Path().apply {
                            moveTo(cx, cy - r)
                            lineTo(cx - r, cy + r)
                            lineTo(cx + r, cy + r)
                            close()
                        }
                        canvas.drawPath(path, paint)
                    }
                    else -> canvas.drawRoundRect(
                        android.graphics.RectF(cx - r, cy - r, cx + r, cy + r),
                        dp(6).toFloat(), dp(6).toFloat(), paint
                    )
                }
            }
        }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        /** 场上固定 10 个图形,答案在 2..上限 之间随机。 */
        const val TOTAL_PIECES = 10
    }
}
