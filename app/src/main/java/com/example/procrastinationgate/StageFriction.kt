package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** 阶段任务类型。 */
enum class StageKind { MEMORY, SCAN, KNOB, KEY, SWIPE, HOLD, BREATHE, CHOICE, ROUTE }

/** 单个阶段的规格。 */
data class StageSpec(
    val kind: StageKind,
    val memoryCount: Int = 4,
    val reverse: Boolean = false,
    val knobCount: Int = 2,
    val turns: Int = 2,
    val ruleIndex: Int = 0,
    val seconds: Int = 3,
    val cycles: Int = 1,
    val routeSize: Int = 3,
    val title: String = "",
    val options: List<String> = emptyList(),
    val direction: String = "右"
)

/** 15 种高难度摩擦的阶段组合定义。 */
fun frictionStages(type: FrictionType, config: GateFrictionConfig): List<StageSpec> = when (type) {
    FrictionType.MEMORY_GRID -> listOf(
        StageSpec(StageKind.MEMORY, memoryCount = config.memoryCount, title = "记住点的顺序")
    )
    FrictionType.REVERSE_MEMORY -> listOf(
        StageSpec(StageKind.MEMORY, memoryCount = config.memoryCount, reverse = true, title = "记住后按倒序点亮")
    )
    FrictionType.KNOB_LOCK -> listOf(
        StageSpec(StageKind.KNOB, knobCount = config.knobCount, title = "对齐全部旋钮")
    )
    FrictionType.KEY_PATH -> listOf(
        StageSpec(StageKind.KEY, turns = config.turnCount, title = "把钥匙拖到锁孔")
    )
    FrictionType.TILT_BALL -> listOf(
        StageSpec(StageKind.KEY, turns = 1, title = "把小球滚到锁孔")
    )
    FrictionType.TARGET_SCAN -> listOf(
        StageSpec(StageKind.SCAN, ruleIndex = Random.nextInt(3), title = "目标扫描")
    )
    FrictionType.BOLT_SEQUENCE -> listOf(
        StageSpec(StageKind.KNOB, knobCount = 1, title = "旋转门栓"),
        StageSpec(StageKind.SWIPE, direction = "右", title = "向右滑动"),
        StageSpec(StageKind.HOLD, seconds = 2, title = "长按推门")
    ).shuffled()
    FrictionType.GUIDED_SWIPE -> listOf(
        StageSpec(StageKind.SWIPE, direction = "右", title = "向右滑"),
        StageSpec(StageKind.SWIPE, direction = "下", title = "向下滑"),
        StageSpec(StageKind.HOLD, seconds = 2, title = "长按圆点")
    ).shuffled()
    FrictionType.INTENT_CONFIRM -> listOf(
        StageSpec(StageKind.CHOICE, title = "现在打开它，是为了？",
            options = listOf("查找信息", "等待某件事", "只是继续刷")),
        StageSpec(StageKind.CHOICE, title = "打算用多久？",
            options = listOf("3 分钟", "5 分钟", "10 分钟")),
        StageSpec(StageKind.HOLD, seconds = 2, title = "最后，按住确认")
    )
    FrictionType.MULTI_STAGE -> listOf(
        StageSpec(StageKind.MEMORY, memoryCount = 4, title = "记住符号顺序"),
        StageSpec(StageKind.KNOB, knobCount = 2, title = "按顺序转旋钮"),
        StageSpec(StageKind.KEY, turns = 2, title = "钥匙过门缝"),
        StageSpec(StageKind.HOLD, seconds = 2, title = "长按推门")
    )
    FrictionType.RULE_SWITCH -> listOf(
        StageSpec(StageKind.SCAN, ruleIndex = 0, title = "点出所有空心圆"),
        StageSpec(StageKind.SCAN, ruleIndex = 1, title = "点出所有三角形"),
        StageSpec(StageKind.SCAN, ruleIndex = 2, title = "点出最大的图形")
    )
    FrictionType.MEMORY_KNOB -> listOf(
        StageSpec(StageKind.MEMORY, memoryCount = 4, title = "记住符号顺序"),
        StageSpec(StageKind.KNOB, knobCount = config.knobCount, title = "按序对齐旋钮")
    )
    FrictionType.PLATE_ASSEMBLY -> listOf(
        StageSpec(StageKind.KNOB, knobCount = 3, title = "对齐门板拼片")
    )
    FrictionType.REFLECT_WAIT -> listOf(
        StageSpec(StageKind.CHOICE, title = "这次继续，是清醒的选择吗？",
            options = listOf("是，我想继续", "不，换个去处")),
        StageSpec(StageKind.BREATHE, cycles = 3, title = "三次缓慢呼吸"),
        StageSpec(StageKind.CHOICE, title = "现在，决定继续还是离开",
            options = listOf("继续", "离开"))
    )
    FrictionType.ROUTE_PLAN -> listOf(
        StageSpec(StageKind.ROUTE, routeSize = config.routeSize, title = "规划路线到出口")
    )
    else -> emptyList()
}

/** 阶段任务执行器:按顺序运行阶段,全部完成后回调。 */
class StageTaskView(
    context: Context,
    private val stages: List<StageSpec>
) : FrameLayout(context) {
    var onCompleted: (() -> Unit)? = null
    private val titleText = TextView(context).apply {
        gravity = android.view.Gravity.CENTER
        textSize = 16f
        setTextColor(UiTheme.ink)
        includeFontPadding = false
        setLineSpacing(0f, 1.1f)
    }
    private val container = FrameLayout(context)
    private var index = 0

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(titleText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (stages.isNotEmpty()) showStage(0)
    }

    private fun showStage(i: Int) {
        if (i >= stages.size) {
            onCompleted?.invoke()
            return
        }
        index = i
        val spec = stages[i]
        titleText.text = spec.title
        container.removeAllViews()
        val view = when (spec.kind) {
            StageKind.MEMORY -> MemoryPanel(context, spec.memoryCount, spec.reverse) { showStage(index + 1) }
            StageKind.SCAN -> TargetScanView(context, spec.ruleIndex) { showStage(index + 1) }
            StageKind.KNOB -> KnobSequenceView(context, spec.knobCount) { showStage(index + 1) }
            StageKind.KEY -> KeyPathView(context, spec.turns) { showStage(index + 1) }
            StageKind.SWIPE -> PlaceholderStepView(context, "滑动(即将上线)")
            StageKind.HOLD -> PlaceholderStepView(context, "长按(即将上线)")
            StageKind.BREATHE -> PlaceholderStepView(context, "呼吸(即将上线)")
            StageKind.CHOICE -> PlaceholderStepView(context, "选择(即将上线)")
            StageKind.ROUTE -> PlaceholderStepView(context, "路线规划(即将上线)")
        }
        container.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

/** 未实现阶段的占位提示。 */
class PlaceholderStepView(context: Context, text: String) : TextView(context) {
    init {
        gravity = android.view.Gravity.CENTER
        textSize = 15f
        setTextColor(UiTheme.muted)
        this.text = text
    }
}

/** 旋钮序列:依次旋转对齐全部旋钮。 */
class KnobSequenceView(context: Context, count: Int, private val onDone: () -> Unit) : View(context) {
    private class Knob(val cx: Float, val cy: Float, val r: Float, val target: Float) {
        var current = 0f
        var done = false
    }

    private val knobs = mutableListOf<Knob>()
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.FILL
    }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        strokeWidth = dp(6).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        strokeWidth = dp(3).toFloat()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val snapAngles = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
    private var active: Knob? = null
    private var doneCount = 0
    private var completed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        knobs.clear()
        doneCount = 0
        completed = false
        val total = knobsCapacity
        val cols = 3
        val rows = (total + cols - 1) / cols
        val cellW = w / (cols + 1).toFloat()
        val cellH = h / (rows + 1).toFloat()
        val r = min(cellW, cellH) * 0.30f
        for (i in 0 until total) {
            val row = i / cols
            val col = i % cols
            knobs += Knob(
                cellW * (col + 1), cellH * (row + 1), r,
                snapAngles.filter { it != 0f }.random()
            )
        }
        invalidate()
    }

    private var knobsCapacity: Int = 0

    init { knobsCapacity = count.coerceIn(1, 9) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        knobs.forEach { knob ->
            canvas.drawCircle(knob.cx, knob.cy, knob.r, if (knob.done) donePaint else basePaint)
            val rad = Math.toRadians(knob.current.toDouble())
            canvas.drawLine(
                knob.cx, knob.cy,
                knob.cx + knob.r * 0.7f * kotlin.math.cos(rad).toFloat(),
                knob.cy + knob.r * 0.7f * kotlin.math.sin(rad).toFloat(),
                pointerPaint
            )
            // 目标缺口标记
            val tRad = Math.toRadians(knob.target.toDouble())
            canvas.drawLine(
                knob.cx + knob.r * 1.15f * kotlin.math.cos(tRad).toFloat(),
                knob.cy + knob.r * 1.15f * kotlin.math.sin(tRad).toFloat(),
                knob.cx + knob.r * 1.35f * kotlin.math.cos(tRad).toFloat(),
                knob.cy + knob.r * 1.35f * kotlin.math.sin(tRad).toFloat(),
                markPaint
            )
        }
        canvas.drawText("旋转对齐所有旋钮（对准短线标记）", width / 2f, height - dp(12).toFloat(), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = knobs.firstOrNull {
                    !it.done && hypot(event.x - it.cx, event.y - it.cy) < it.r * 1.8f
                } ?: return true
                active = target
            }
            MotionEvent.ACTION_MOVE -> active?.let { knob ->
                val angle = Math.toDegrees(
                    kotlin.math.atan2((event.y - knob.cy).toDouble(), (event.x - knob.cx).toDouble())
                ).toFloat()
                knob.current = ((angle % 360f) + 360f) % 360f
                invalidate()
            }
            MotionEvent.ACTION_UP -> active?.let { knob ->
                val snapped = snapAngles.minByOrNull { kotlin.math.abs(angleDiff(knob.current, it)) }!!
                knob.current = snapped
                if (kotlin.math.abs(angleDiff(snapped, knob.target)) < 20f) {
                    knob.done = true
                    doneCount++
                    if (doneCount >= knobs.size) {
                        completed = true
                        onDone()
                    }
                }
                active = null
                invalidate()
            }
        }
        return true
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 钥匙通道:把钥匙沿通道拖到锁孔,出界回到起点。 */
class KeyPathView(context: Context, turns: Int, private val onDone: () -> Unit) : View(context) {
    private val turnCount = turns.coerceIn(0, 3)
    private val pathPoints = mutableListOf<Pair<Float, Float>>()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.STROKE
        strokeWidth = dp(44).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.surface
        style = Paint.Style.STROKE
        strokeWidth = dp(30).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        style = Paint.Style.STROKE
        strokeWidth = dp(5).toFloat()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val halfWidth = dp(26).toFloat()
    private var keyPos = 0f to 0f
    private var completed = false
    private var dragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pathPoints.clear()
        completed = false
        val left = dp(22).toFloat()
        val right = (w - dp(22)).toFloat()
        val top = h * 0.22f
        val bottom = h * 0.78f
        val segments = (turnCount + 1)
        var x = left
        var y = h / 2f
        pathPoints += x to y
        var goingDown = true
        for (i in 0 until segments) {
            x = if (i % 2 == 0) right else left
            if (i % 2 == 1) {
                y = if (goingDown) bottom else top
                goingDown = !goingDown
            }
            pathPoints += x to y
        }
        keyPos = pathPoints.first()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pathPoints.size < 2) return
        val path = Path().apply {
            moveTo(pathPoints[0].first, pathPoints[0].second)
            for (i in 1 until pathPoints.size) lineTo(pathPoints[i].first, pathPoints[i].second)
        }
        canvas.drawPath(path, trackPaint)
        canvas.drawPath(path, innerPaint)
        val end = pathPoints.last()
        canvas.drawCircle(end.first, end.second, dp(26).toFloat(), lockPaint)
        canvas.drawCircle(keyPos.first, keyPos.second, dp(22).toFloat(), keyPaint)
        canvas.drawText("沿通道把钥匙拖到锁孔，出界会回到起点", width / 2f, height - dp(12).toFloat(), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = hypot(event.x - keyPos.first, event.y - keyPos.second) < dp(42)
                if (dragging) invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                keyPos = event.x to event.y
                val minDist = minDistanceToPath(keyPos)
                if (minDist > halfWidth) {
                    keyPos = pathPoints.first()
                    dragging = false
                } else {
                    val end = pathPoints.last()
                    if (hypot(keyPos.first - end.first, keyPos.second - end.second) < dp(30)) {
                        completed = true
                        onDone()
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun minDistanceToPath(p: Pair<Float, Float>): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until pathPoints.size - 1) {
            best = min(best, distToSegment(p, pathPoints[i], pathPoints[i + 1]))
        }
        return best
    }

    private fun distToSegment(p: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else {
            (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0f, 1f)
        }
        val x = a.first + t * dx
        val y = a.second + t * dy
        return hypot(p.first - x, p.second - y)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 记忆面板:先按序闪烁,再按(正/倒)序点亮。 */
class MemoryPanel(
    context: Context,
    count: Int,
    private val reverse: Boolean,
    private val onDone: () -> Unit
) : View(context) {
    private val dotCount = count.coerceIn(3, 7)
    private val dots = mutableListOf<Pair<Float, Float>>()
    private val handler = Handler(Looper.getMainLooper())
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.line }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(15).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private var flashIndex = -1
    private var showing = true
    private var inputIndex = 0
    private var completed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dots.clear()
        val margin = dp(46)
        val minGap = dp(34) * 2.4f
        var attempts = 0
        while (dots.size < dotCount && attempts < 300) {
            attempts++
            val x = margin + Random.nextFloat() * (w - 2 * margin)
            val y = margin + Random.nextFloat() * (h - 2 * margin)
            val tooClose = dots.any { hypot(it.first - x, it.second - y) < minGap }
            if (!tooClose) dots += x to y
        }
        if (dots.size < dotCount) {
            dots.clear()
            val cx = w / 2f
            val cy = h / 2f
            val spread = min(w, h) * 0.28f
            for (i in 0 until dotCount) {
                val angle = 2f * Math.PI.toFloat() * i / dotCount
                dots += cx + spread * kotlin.math.cos(angle) to cy + spread * kotlin.math.sin(angle)
            }
        }
        startShow()
    }

    private fun startShow() {
        showing = true
        inputIndex = 0
        flashIndex = -1
        val tick = object : Runnable {
            var i = 0
            override fun run() {
                if (i >= dots.size) {
                    showing = false
                    invalidate()
                    return
                }
                flashIndex = i
                invalidate()
                i++
                handler.postDelayed(this, 480)
            }
        }
        handler.postDelayed(tick, 480)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = dp(26).toFloat()
        dots.forEachIndexed { index, (x, y) ->
            val paint = when {
                !showing && targetIndex(index) < inputIndex -> donePaint
                showing && index == flashIndex -> activePaint
                else -> guidePaint
            }
            canvas.drawCircle(x, y, r, paint)
        }
        val hint = when {
            showing -> "记住点的顺序"
            reverse -> "按倒序点亮"
            else -> "按顺序点亮"
        }
        canvas.drawText(hint, width / 2f, height - dp(14).toFloat(), hintPaint)
    }

    private fun targetIndex(index: Int): Int =
        if (reverse) dots.size - 1 - index else index

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed || showing) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN && inputIndex < dots.size) {
            val target = dots.firstOrNull { targetIndex(dots.indexOf(it)) == inputIndex }
            val idx = dots.indexOfFirst { targetIndex(dots.indexOf(it)) == inputIndex }
            if (idx >= 0) {
                val (tx, ty) = dots[idx]
                if (hypot(event.x - tx, event.y - ty) < dp(56)) {
                    inputIndex++
                    invalidate()
                    if (inputIndex >= dots.size) {
                        completed = true
                        onDone()
                    }
                }
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 目标扫描:按规则点击图形(空心圆/三角形/最大图形)。 */
class TargetScanView(
    context: Context,
    ruleIndex: Int,
    private val onDone: () -> Unit
) : View(context) {
    private val rule = ruleIndex.coerceIn(0, 2)
    private class Figure(
        val x: Float, val y: Float, val kind: Int, val size: Float
    ) {
        var hollow = false
        var target = false
        var hit = false
    }

    private val figures = mutableListOf<Figure>()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        style = Paint.Style.STROKE
        strokeWidth = dp(4).toFloat()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        style = Paint.Style.FILL
    }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        style = Paint.Style.FILL
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(15).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private var hint = ""
    private var hitCount = 0
    private var completed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        figures.clear()
        hitCount = 0
        completed = false
        hint = when (rule) {
            0 -> "点出所有空心圆"
            1 -> "点出所有三角形"
            else -> "点出最大的图形"
        }
        val positions = (0 until 12).map { i ->
            val row = i / 4
            val col = i % 4
            (w * (0.16f + col * 0.23f)) to (h * (0.26f + row * 0.24f))
        }
        positions.forEachIndexed { i, (px, py) ->
            val jitter = w * 0.06f
            val x = px + (Random.nextFloat() - 0.5f) * jitter
            val y = py + (Random.nextFloat() - 0.5f) * jitter
            val kind = i % 3
            val size = 0.75f + Random.nextFloat() * 0.55f
            figures += Figure(x, y, kind, size).apply { hollow = Random.nextBoolean() }
        }
        when (rule) {
            0 -> {
                if (figures.count { it.kind == 0 && it.hollow } < 2) {
                    figures.filter { it.kind == 0 }.take(2).forEach { it.hollow = true }
                }
                figures.forEach { it.target = it.kind == 0 && it.hollow }
            }
            1 -> figures.forEach { it.target = it.kind == 1 }
            else -> {
                val maxSize = figures.maxOf { it.size }
                figures.forEach { it.target = it.size >= maxSize - 0.001f }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val base = min(width, height) / 8f
        figures.forEach { f ->
            val r = base * f.size
            val paint = if (f.hit) donePaint else if (f.hollow) strokePaint else fillPaint
            when (f.kind) {
                0 -> canvas.drawCircle(f.x, f.y, r, paint)
                1 -> {
                    val path = Path().apply {
                        moveTo(f.x, f.y - r)
                        lineTo(f.x - r, f.y + r)
                        lineTo(f.x + r, f.y + r)
                        close()
                    }
                    canvas.drawPath(path, paint)
                }
                else -> canvas.drawRoundRect(
                    RectF(f.x - r, f.y - r, f.x + r, f.y + r), dp(6).toFloat(), dp(6).toFloat(), paint)
            }
        }
        canvas.drawText(hint, width / 2f, height - dp(14).toFloat(), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val hit = figures.firstOrNull {
                !it.hit && hypot(event.x - it.x, event.y - it.y) < dp(36)
            }
            if (hit != null) {
                hit.hit = true
                if (hit.target) {
                    hitCount++
                    val total = figures.count { it.target }
                    if (hitCount >= total) {
                        completed = true
                        onDone()
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
