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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** 阶段任务类型。 */
enum class StageKind { MEMORY, SCAN, KNOB, KEY, SWIPE, HOLD, BREATHE, CHOICE, ROUTE, PLATE, SYMBOL_MEMORY, SYMBOL_KNOB }

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
    val direction: String = "右",
    val symbols: List<String> = emptyList(),
    /** 选中这些选项表示用户要走出口,而不是继续推门。 */
    val leaveOptions: Set<String> = emptySet()
)

/** 拖动类子视图接管手势,避免外层 ScrollView 抢走触摸。 */
internal fun View.claimGesture(claim: Boolean) {
    parent?.requestDisallowInterceptTouchEvent(claim)
}

/** 系统是否开着读屏(触摸浏览)。开着时所有靠手势的摩擦都要降级。 */
internal fun Context.isScreenReaderActive(): Boolean {
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE)
        as? android.view.accessibility.AccessibilityManager
    return manager?.isTouchExplorationEnabled == true
}

/** 给 canvas 绘制的自定义视图补一条读屏描述,返回自身便于链式调用。 */
internal fun <T : View> T.say(description: String): T {
    contentDescription = description
    return this
}

/** 阶段式摩擦的阶段组合定义。 */
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
    FrictionType.TARGET_SCAN -> listOf(
        StageSpec(StageKind.SCAN, ruleIndex = Random.nextInt(3), title = "目标扫描")
    )
    FrictionType.BOLT_SEQUENCE -> listOf(
        StageSpec(StageKind.KNOB, knobCount = 1, title = "旋转门栓"),
        StageSpec(StageKind.SWIPE, direction = "右", title = "向右滑动"),
        StageSpec(StageKind.HOLD, seconds = 2, title = "长按推门")
    ).shuffled()
    FrictionType.INTENT_CONFIRM -> listOf(
        StageSpec(StageKind.CHOICE, title = "现在打开它，是为了？",
            options = listOf("查找信息", "等待某件事", "只是继续刷")),
        StageSpec(StageKind.CHOICE, title = "打算用多久？",
            options = listOf("3 分钟", "5 分钟", "10 分钟")),
        StageSpec(StageKind.HOLD, seconds = 2, title = "最后，按住确认")
    )
    FrictionType.MULTI_STAGE -> {
        // 随机组合 2-3 个不同阶段:3 个以上会远超「2-5 秒内完成」的设计预算
        val candidates = listOf(
            StageSpec(StageKind.MEMORY, memoryCount = 4, title = "记住符号顺序"),
            StageSpec(StageKind.SCAN, ruleIndex = Random.nextInt(3), title = "按规则点出目标"),
            StageSpec(StageKind.KNOB, knobCount = 2, title = "对齐旋钮"),
            StageSpec(StageKind.KEY, turns = 2, title = "钥匙过门缝"),
            StageSpec(StageKind.SWIPE, direction = listOf("右", "下", "左", "上").random(), title = "按方向滑动"),
            StageSpec(StageKind.HOLD, seconds = 2, title = "长按确认"),
            StageSpec(StageKind.PLATE, title = "拼合拼片")
        )
        candidates.shuffled().take(Random.nextInt(2, 4))
    }
    FrictionType.REPEAT_SCAN -> listOf(
        StageSpec(StageKind.SCAN, ruleIndex = 0, title = "点出所有空心圆"),
        StageSpec(StageKind.SCAN, ruleIndex = 1, title = "点出所有三角形"),
        StageSpec(StageKind.SCAN, ruleIndex = 2, title = "点出最大的图形")
    )
    FrictionType.MEMORY_KNOB -> {
        val symbols = ('A'..'H').map { it.toString() }.shuffled().take(config.memoryCount.coerceIn(3, 8))
        listOf(
            StageSpec(StageKind.SYMBOL_MEMORY, symbols = symbols, title = "记住符号顺序"),
            StageSpec(StageKind.SYMBOL_KNOB, symbols = symbols, title = "旋转旋钮到对应符号")
        )
    }
    FrictionType.PLATE_ASSEMBLY -> listOf(
        StageSpec(StageKind.PLATE, title = "把拼片拖到对应的槽")
    )
    FrictionType.REFLECT_WAIT -> listOf(
        StageSpec(StageKind.CHOICE, title = "这次继续，是清醒的选择吗？",
            options = listOf("是，我想继续", "不，换个去处"),
            leaveOptions = setOf("不，换个去处")),
        StageSpec(StageKind.BREATHE, cycles = 3, title = "三次缓慢呼吸"),
        StageSpec(StageKind.CHOICE, title = "现在，决定继续还是离开",
            options = listOf("继续", "离开"),
            leaveOptions = setOf("离开"))
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

    /** 用户在某个阶段选了「离开」类选项时回调,而不是继续推门。 */
    var onLeave: (() -> Unit)? = null

    private val titleText = TextView(context).apply {
        gravity = android.view.Gravity.CENTER
        textSize = 16f
        setTextColor(UiTheme.ink)
        includeFontPadding = false
        setLineSpacing(0f, 1.1f)
    }
    private val container = FrameLayout(context)
    private var index = 0
    var lastChoice: String? = null
    private var finished = false

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
        if (finished) return
        if (i >= stages.size) {
            finished = true
            onCompleted?.invoke()
            return
        }
        index = i
        val spec = stages[i]
        // 多段摩擦必须自己把「第几段」说出来,否则用户会把换段误解成"又弹了一次摩擦"
        val stageLabel = if (stages.size > 1) " · 第 ${i + 1}/${stages.size} 段" else ""
        titleText.text = spec.title + stageLabel
        if (context.isScreenReaderActive()) announceForAccessibility(titleText.text)
        container.removeAllViews()
        val view = when (spec.kind) {
            StageKind.MEMORY -> MemoryPanel(context, spec.memoryCount, spec.reverse) { showStage(index + 1) }
                .say("记忆点阵:记住闪烁顺序,再按顺序点亮")
            StageKind.SCAN -> TargetScanView(context, spec.ruleIndex) { showStage(index + 1) }
                .say("目标扫描:按提示点出目标图形")
            StageKind.KNOB -> KnobSequenceView(context, spec.knobCount) { showStage(index + 1) }
                .say("旋钮锁:把每个旋钮转到与外侧短线标记对齐")
            StageKind.KEY -> KeyPathView(context, spec.turns) { showStage(index + 1) }
                .say("推钥匙:沿通道把钥匙拖到锁孔")
            StageKind.SWIPE -> SwipeView(context, spec.direction) { showStage(index + 1) }
                .say("按箭头方向滑动")
            StageKind.HOLD -> HoldStepView(context, spec.seconds) { showStage(index + 1) }
                .say("按住圆点 ${spec.seconds} 秒")
            StageKind.BREATHE -> BreatheStepView(context, spec.cycles) { showStage(index + 1) }
                .say("跟随屏幕完成 ${spec.cycles} 次缓慢呼吸")
            StageKind.CHOICE -> ChoiceStepView(context, spec.options) { value ->
                lastChoice = value
                if (value in spec.leaveOptions) {
                    finished = true
                    onLeave?.invoke()
                } else {
                    showStage(index + 1)
                }
            }
            StageKind.ROUTE -> RouteView(context, spec.routeSize) { showStage(index + 1) }
                .say("路线规划:从左上走到右下,避开深色格")
            StageKind.PLATE -> PieceBoardView(context) { showStage(index + 1) }
                .say("拼合门板:把拼片拖到对应形状的槽")
            StageKind.SYMBOL_MEMORY -> SymbolMemoryView(context, spec.symbols) { showStage(index + 1) }
            StageKind.SYMBOL_KNOB -> SymbolKnobView(context, spec.symbols) { showStage(index + 1) }
                .say("记忆旋钮:把每个旋钮转到记忆中的字母")
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
                claimGesture(true)
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
                claimGesture(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                active = null
                claimGesture(false)
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
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.line }
    private val checkDonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private val checkActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
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
    private val warnKeyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.warning }
    private val warnHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.warning
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val halfWidth = dp(26).toFloat()
    private var keyPos = 0f to 0f
    private var completed = false
    private var dragging = false
    private var checkpointIndex = 0
    private var outOfBounds = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pathPoints.clear()
        completed = false
        checkpointIndex = 0
        val left = dp(22).toFloat()
        val right = (w - dp(22)).toFloat()
        val segments = (turnCount + 1)
        // 起点与折返点高度等分 + 受限抖动,保证相邻线路最小间距不重叠
        val top = h * 0.2f
        val span = h * 0.6f
        val step = span / segments
        val heights = (0 until segments).map { i ->
            top + step * (i + 0.5f) + (Random.nextFloat() - 0.5f) * step * 0.5f
        }
        var x = left
        var y = heights[0]
        pathPoints += x to y
        for (i in 0 until segments) {
            x = if (i % 2 == 0) right else left
            if (i > 0) y = heights[i]
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
        // 必经检查点(中间转折点)
        for (i in 1 until pathPoints.size - 1) {
            val p = pathPoints[i]
            val paint = when {
                i <= checkpointIndex -> checkDonePaint
                i == checkpointIndex + 1 -> checkActivePaint
                else -> checkPaint
            }
            canvas.drawCircle(p.first, p.second, dp(10).toFloat(), paint)
        }
        canvas.drawCircle(
            keyPos.first, keyPos.second, dp(22).toFloat(),
            if (outOfBounds) warnKeyPaint else keyPaint
        )
        val hint = if (outOfBounds) {
            "手指回到通道上，进度不会重置"
        } else {
            "沿通道把钥匙拖到锁孔，经过圆点即可"
        }
        canvas.drawText(
            hint, width / 2f, height - dp(12).toFloat(),
            if (outOfBounds) warnHintPaint else hintPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = hypot(event.x - keyPos.first, event.y - keyPos.second) < dp(42)
                claimGesture(dragging)
                if (dragging) invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                // 出界只做提示,不重置进度:钥匙停在最后一个合法位置
                outOfBounds = minDistanceToPath(event.x to event.y) > halfWidth
                if (!outOfBounds) {
                    keyPos = event.x to event.y
                    // 依次经过必经检查点(中间转折点)
                    val nextCheck = checkpointIndex + 1
                    if (nextCheck < pathPoints.size - 1) {
                        val c = pathPoints[nextCheck]
                        if (hypot(keyPos.first - c.first, keyPos.second - c.second) < dp(34)) {
                            checkpointIndex = nextCheck
                        }
                    }
                    val end = pathPoints.last()
                    val allPassed = checkpointIndex >= pathPoints.size - 2
                    if (allPassed && hypot(keyPos.first - end.first, keyPos.second - end.second) < dp(30)) {
                        completed = true
                        onDone()
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                outOfBounds = false
                claimGesture(false)
                invalidate()
            }
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
    private val dotCount = count.coerceIn(3, 8)
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
    private var errors = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dots.clear()
        // 网格位置 + 随机抖动 + 顺序打乱,每次生成都不同且不重叠
        val cols = if (dotCount <= 4) dotCount else 4
        val rows = (dotCount + cols - 1) / cols
        val margin = dp(40)
        val cellW = (w - 2f * margin) / cols
        val cellH = (h - 2f * margin - dp(26)) / rows
        val generated = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until dotCount) {
            val row = i / cols
            val col = i % cols
            val cx = margin + cellW * (col + 0.5f)
            val cy = margin + cellH * (row + 0.5f)
            val jitterX = cellW * 0.38f
            val jitterY = cellH * 0.38f
            generated += (cx + (Random.nextFloat() - 0.5f) * jitterX) to
                (cy + (Random.nextFloat() - 0.5f) * jitterY)
        }
        dots.addAll(generated.shuffled())
        startShow()
    }

    private fun startShow() {
        handler.removeCallbacksAndMessages(null)
        showing = true
        inputIndex = 0
        flashIndex = -1
        errors = 0
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
        val suffix = if (!showing && errors > 0) "　点错 $errors/$MAX_ERRORS" else ""
        canvas.drawText(hint + suffix, width / 2f, height - dp(14).toFloat(), hintPaint)
    }

    private fun targetIndex(index: Int): Int =
        if (reverse) dots.size - 1 - index else index

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed || showing) return true
        if (event.actionMasked != MotionEvent.ACTION_DOWN || inputIndex >= dots.size) return true

        val expected = targetIndex(inputIndex)
        if (expected !in dots.indices) return true
        val (tx, ty) = dots[expected]
        if (hypot(event.x - tx, event.y - ty) < dp(56)) {
            inputIndex++
            invalidate()
            if (inputIndex >= dots.size) {
                completed = true
                onDone()
            }
            return true
        }

        // 点到别的圆点算一次点错;点空白不计数。错满 3 次重放序列。
        val tappedAnotherDot = dots.any { hypot(event.x - it.first, event.y - it.second) < dp(56) }
        if (tappedAnotherDot) {
            errors++
            if (errors >= MAX_ERRORS) startShow() else invalidate()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_ERRORS = 3
    }
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
        var missed = false
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
    /** 点错的中性提示:只描一圈安静橙,不冒充成功。 */
    private val missPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.warning
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
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
        val base = min(width, height) / 9.5f
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
            if (f.missed && !f.hit) {
                canvas.drawCircle(f.x, f.y, r + dp(7), missPaint)
            }
        }
        val total = figures.count { it.target }
        val progress = if (total > 0) "　$hitCount/$total" else ""
        canvas.drawText(hint + progress, width / 2f, height - dp(14).toFloat(), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val hit = figures.firstOrNull {
                !it.hit && hypot(event.x - it.x, event.y - it.y) < dp(36)
            }
            if (hit != null) {
                if (hit.target) {
                    hit.hit = true
                    hit.missed = false
                    hitCount++
                    val total = figures.count { it.target }
                    if (hitCount >= total) {
                        completed = true
                        onDone()
                    }
                } else {
                    // 点错只给中性提示,不算命中,也不会变成"已完成"的绿色
                    hit.missed = true
                }
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 组合滑动:朝指定方向滑动一次。 */
class SwipeView(context: Context, private val direction: String, private val onDone: () -> Unit) : View(context) {
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        style = Paint.Style.STROKE
        strokeWidth = dp(10).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(15).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val startX = FloatArray(1)
    private val startY = FloatArray(1)
    private var tracking = false
    private var completed = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val len = dp(54).toFloat()
        val head = dp(16).toFloat()
        val path = Path().apply {
            when (direction) {
                "左" -> {
                    moveTo(cx + len, cy); lineTo(cx - len, cy)
                    moveTo(cx - len, cy); lineTo(cx - len + head, cy - head)
                    moveTo(cx - len, cy); lineTo(cx - len + head, cy + head)
                }
                "上" -> {
                    moveTo(cx, cy + len); lineTo(cx, cy - len)
                    moveTo(cx, cy - len); lineTo(cx - head, cy - len + head)
                    moveTo(cx, cy - len); lineTo(cx + head, cy - len + head)
                }
                "下" -> {
                    moveTo(cx, cy - len); lineTo(cx, cy + len)
                    moveTo(cx, cy + len); lineTo(cx - head, cy + len - head)
                    moveTo(cx, cy + len); lineTo(cx + head, cy + len - head)
                }
                else -> {
                    moveTo(cx - len, cy); lineTo(cx + len, cy)
                    moveTo(cx + len, cy); lineTo(cx + len - head, cy - head)
                    moveTo(cx + len, cy); lineTo(cx + len - head, cy + head)
                }
            }
        }
        canvas.drawPath(path, arrowPaint)
        canvas.drawText("沿箭头方向滑动", cx, cy + dp(64), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX[0] = event.x
                startY[0] = event.y
                tracking = true
                claimGesture(true)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val dx = event.x - startX[0]
                val dy = event.y - startY[0]
                val matched = when (direction) {
                    "右" -> dx > dp(30) && kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    "左" -> dx < -dp(30) && kotlin.math.abs(dx) > kotlin.math.abs(dy)
                    "下" -> dy > dp(30) && kotlin.math.abs(dy) > kotlin.math.abs(dx)
                    else -> dy < -dp(30) && kotlin.math.abs(dy) > kotlin.math.abs(dx)
                }
                if (matched) {
                    completed = true
                    onDone()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                claimGesture(false)
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 长按阶段:按住圆形按钮,环形进度走满后完成。 */
class HoldStepView(context: Context, seconds: Int, private val onDone: () -> Unit) : View(context) {
    private val sec = seconds.coerceIn(1, 30)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.line }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        style = Paint.Style.STROKE
        strokeWidth = dp(10).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        textSize = dp(17).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val handler = Handler(Looper.getMainLooper())
    private var elapsedMs = 0L
    private var holding = false
    private var completed = false
    private val tick = object : Runnable {
        override fun run() {
            elapsedMs += 50L
            invalidate()
            if (elapsedMs >= sec * 1_000L) {
                // 先让最后一帧(进度满)绘制出来,再完成
                handler.postDelayed({
                    if (!completed) {
                        completed = true
                        onDone()
                    }
                }, 120L)
            } else {
                handler.postDelayed(this, 50L)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.34f
        canvas.drawCircle(cx, cy, r, basePaint)
        if (holding) {
            val progress = (elapsedMs / (sec * 1_000f)).coerceAtMost(1f)
            if (progress > 0f) {
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * progress, false, ringPaint)
            }
        }
        val remaining = if (holding) {
            ((sec * 1_000L - elapsedMs + 999L) / 1_000L).coerceAtLeast(0L)
        } else sec.toLong()
        canvas.drawText(if (holding) "继续按住… $remaining" else "按住 $sec 秒", cx, cy + dp(6), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = true
                elapsedMs = 0L
                claimGesture(true)
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 50L)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holding = false
                claimGesture(false)
                handler.removeCallbacks(tick)
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 意图选择阶段:显示问题与选项,点击任一完成并返回选项文本。 */
class ChoiceStepView(
    context: Context,
    options: List<String>,
    private val onDone: (String) -> Unit
) : FrameLayout(context) {
    init {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        // question 由外层 StageTaskView 的标题统一显示(还要带「第 n/m 段」),
        // 这里不再重复画一遍,否则同一句提问会出现两行。
        options.forEach { label ->
            box.addView(Button(context).apply {
                text = label
                textSize = 15f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = UiTheme.shape(context, UiTheme.ink, 12)
                setOnClickListener { onDone(label) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        }
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 拼合门板:把三种形状的拼片拖到对应形状的槽位。 */
class PieceBoardView(context: Context, private val onDone: () -> Unit) : View(context) {
    private class Piece(val kind: Int) {
        var x = 0f
        var y = 0f
        var homeX = 0f
        var homeY = 0f
        var locked = false
    }

    private val pieces = listOf(Piece(0), Piece(1), Piece(2))
    private val slotXs = FloatArray(3)
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.STROKE
        strokeWidth = dp(4).toFloat()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private var drag: Piece? = null
    private var completed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        completed = false
        val gap = w / 4f
        for (k in 0 until 3) slotXs[k] = gap * (k + 1)
        val xs = listOf(gap, gap * 2f, gap * 3f).shuffled()
        pieces.forEachIndexed { i, piece ->
            piece.x = xs[i]
            piece.y = h * 0.26f
            piece.homeX = piece.x
            piece.homeY = piece.y
            piece.locked = false
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = dp(34).toFloat()
        val slotY = height * 0.78f
        // 槽位(轮廓)
        for (k in 0 until 3) {
            drawShape(canvas, k, slotXs[k], slotY, r, slotPaint)
        }
        // 拼片
        pieces.forEach { piece ->
            val paint = if (piece.locked) donePaint else piecePaint
            drawShape(canvas, piece.kind, piece.x, piece.y, r, paint)
        }
        canvas.drawText("把拼片拖到对应的槽", width / 2f, height - dp(12).toFloat(), hintPaint)
    }

    private fun drawShape(canvas: Canvas, kind: Int, cx: Float, cy: Float, r: Float, paint: Paint) {
        when (kind) {
            0 -> canvas.drawCircle(cx, cy, r, paint)
            1 -> {
                val path = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx - r, cy + r)
                    lineTo(cx + r, cy + r)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            else -> canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), dp(8).toFloat(), dp(8).toFloat(), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = pieces.firstOrNull {
                    !it.locked && hypot(event.x - it.x, event.y - it.y) < dp(50)
                }
                claimGesture(drag != null)
            }
            MotionEvent.ACTION_MOVE -> drag?.let { piece ->
                piece.x = event.x
                piece.y = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> drag?.let { piece ->
                val slotY = height * 0.78f
                if (hypot(piece.x - slotXs[piece.kind], piece.y - slotY) < dp(52)) {
                    piece.x = slotXs[piece.kind]
                    piece.y = slotY
                    piece.locked = true
                    if (pieces.all { it.locked }) {
                        completed = true
                        onDone()
                    }
                } else {
                    piece.x = piece.homeX
                    piece.y = piece.homeY
                }
                drag = null
                claimGesture(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                drag?.let { piece ->
                    piece.x = piece.homeX
                    piece.y = piece.homeY
                }
                drag = null
                claimGesture(false)
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 呼吸阶段:跟随"吸气/呼气"完成 cycles 次。 */
class BreatheStepView(context: Context, cycles: Int, private val onDone: () -> Unit) : View(context) {
    private val cyc = cycles.coerceIn(1, 5)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        textSize = dp(30).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accentSoft }
    private val handler = Handler(Looper.getMainLooper())
    private var step = 0
    private var completed = false
    private val tick = object : Runnable {
        override fun run() {
            step++
            if (step >= cyc * 2) {
                completed = true
                onDone()
            } else {
                invalidate()
                handler.postDelayed(this, 2_000)
            }
        }
    }

    init { handler.postDelayed(tick, 2_000) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, dp(48).toFloat(), circlePaint)
        val label = when {
            step >= cyc * 2 -> "完成"
            step % 2 == 1 -> "呼气"
            else -> "吸气"
        }
        canvas.drawText(label, cx, cy + dp(10), textPaint)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 路线规划:从左上角按相邻格走到右下角,避开深色障碍格。 */
class RouteView(context: Context, size: Int, private val onDone: () -> Unit) : View(context) {
    private val gridSize = size.coerceIn(3, 5)
    private val obstacles = mutableSetOf<Pair<Int, Int>>()
    private val pathCells = mutableListOf<Pair<Int, Int>>()
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val obsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        style = Paint.Style.STROKE
        strokeWidth = dp(4).toFloat()
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private var cellW = 1f
    private var cellH = 1f
    private var completed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellW = w / gridSize.toFloat()
        cellH = (h - dp(26)) / gridSize.toFloat()
        pathCells.clear()
        completed = false
        generateObstacles()
        pathCells += 0 to 0
        invalidate()
    }

    /** 撒障碍后校验可解性:不可解就减少障碍数量重撒,保证一定有路。 */
    private fun generateObstacles() {
        obstacles.clear()
        val end = gridSize - 1 to gridSize - 1
        val candidates = (0 until gridSize).flatMap { r ->
            (0 until gridSize).map { c -> r to c }
        }.filter { it != (0 to 0) && it != end }
        for (count in min(gridSize, candidates.size) downTo 0) {
            repeat(24) {
                val trial = candidates.shuffled().take(count).toSet()
                if (hasPathTo(trial)) {
                    obstacles.addAll(trial)
                    return
                }
            }
        }
    }

    /** 广度优先搜索:确认起点到终点存在通路。 */
    private fun hasPathTo(blocked: Set<Pair<Int, Int>>): Boolean {
        val goal = gridSize - 1 to gridSize - 1
        val seen = mutableSetOf(0 to 0)
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.addLast(0 to 0)
        while (queue.isNotEmpty()) {
            val (r, c) = queue.removeFirst()
            if (r == goal.first && c == goal.second) return true
            for ((dr, dc) in STEPS) {
                val nr = r + dr
                val nc = c + dc
                if (nr !in 0 until gridSize || nc !in 0 until gridSize) continue
                val next = nr to nc
                if (next in blocked || !seen.add(next)) continue
                queue.addLast(next)
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 网格线
        for (i in 0..gridSize) {
            canvas.drawLine(0f, i * cellH, width.toFloat(), i * cellH, cellPaint)
            canvas.drawLine(i * cellW, 0f, i * cellW, cellH * gridSize, cellPaint)
        }
        // 障碍格
        obstacles.forEach { (r, c) ->
            canvas.drawRect(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH, obsPaint)
        }
        // 已走路径
        pathCells.forEach { (r, c) ->
            canvas.drawRect(c * cellW, r * cellH, (c + 1) * cellW, (r + 1) * cellH, pathPaint)
        }
        // 起点与终点
        canvas.drawCircle(cellW / 2f, cellH / 2f, dp(14).toFloat(), startPaint)
        val endC = (gridSize - 1) * cellW + cellW / 2f
        val endR = (gridSize - 1) * cellH + cellH / 2f
        canvas.drawCircle(endC, endR, dp(16).toFloat(), endPaint)
        canvas.drawText("从左上走到右下，避开深色格；点上一格可撤销", width / 2f, height - dp(10).toFloat(), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val col = (event.x / cellW).toInt().coerceIn(0, gridSize - 1)
            val row = (event.y / cellH).toInt().coerceIn(0, gridSize - 1)
            val cell = row to col
            if (cell in obstacles) return true
            // 点回上一格 = 撤销一步,避免走进死角后卡住
            if (pathCells.size >= 2 && cell == pathCells[pathCells.size - 2]) {
                pathCells.removeAt(pathCells.size - 1)
                invalidate()
                return true
            }
            val last = pathCells.last()
            val adjacent = (cell.first == last.first && kotlin.math.abs(cell.second - last.second) == 1) ||
                (cell.second == last.second && kotlin.math.abs(cell.first - last.first) == 1)
            if (adjacent && cell !in pathCells) {
                pathCells += cell
                if (cell == gridSize - 1 to gridSize - 1) {
                    completed = true
                    onDone()
                }
                invalidate()
            }
        }
        return true
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val STEPS = listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)
    }
}

/** 记忆符号阶段:显示符号序列,点「记住了」继续。 */
class SymbolMemoryView(
    context: Context,
    private val symbols: List<String>,
    private val onDone: () -> Unit
) : FrameLayout(context) {
    init {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        symbols.forEach { symbol ->
            row.addView(TextView(context).apply {
                text = symbol
                textSize = 34f
                setTextColor(UiTheme.ink)
                gravity = android.view.Gravity.CENTER
                background = UiTheme.shape(context, UiTheme.accentSoft, 16, UiTheme.line)
            }, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
        }
        box.addView(row)
        box.addView(Button(context).apply {
            text = "记住了"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = UiTheme.shape(context, UiTheme.ink, 12)
            setOnClickListener { onDone() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(26) })
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 记忆+旋钮阶段:8 个点位标随机字母,把每个旋钮转到目标符号的点位。 */
class SymbolKnobView(context: Context, symbols: List<String>, private val onDone: () -> Unit) : View(context) {
    private class Knob(var cx: Float, var cy: Float, var r: Float, val targetSymbol: String) {
        val pointSymbols = "ABCDEFGH".map { it.toString() }.shuffled()
        val targetIndex = pointSymbols.indexOf(targetSymbol)
        var current = 0f
        var done = false
    }

    private val knobs = symbols.map { Knob(0f, 0f, 0f, it) }.toMutableList()
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.line }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        strokeWidth = dp(5).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(11).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private var active: Knob? = null
    private var doneCount = 0
    private var completed = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val n = knobs.size
        val cols = if (n <= 3) n else 3
        val rows = (n + cols - 1) / cols
        val cellW = w / (cols + 1).toFloat()
        val cellH = h / (rows + 1).toFloat()
        val r = min(cellW, cellH) * 0.40f
        knobs.forEachIndexed { i, knob ->
            val row = i / cols
            val col = i % cols
            knob.cx = cellW * (col + 1)
            knob.cy = cellH * (row + 1)
            knob.r = r
            knob.current = 0f
            knob.done = false
        }
        doneCount = 0
        completed = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        knobs.forEach { knob ->
            canvas.drawCircle(knob.cx, knob.cy, knob.r, if (knob.done) donePaint else basePaint)
            val rad = Math.toRadians(knob.current.toDouble())
            canvas.drawLine(
                knob.cx, knob.cy,
                knob.cx + knob.r * 0.50f * kotlin.math.cos(rad).toFloat(),
                knob.cy + knob.r * 0.50f * kotlin.math.sin(rad).toFloat(),
                pointerPaint
            )
            // 8 个点位字母(旋钮圆内靠外圈),不设颜色提示
            knob.pointSymbols.forEachIndexed { index, symbol ->
                val angle = Math.toRadians((index * 45).toDouble())
                val px = knob.cx + knob.r * 0.66f * kotlin.math.cos(angle).toFloat()
                val py = knob.cy + knob.r * 0.66f * kotlin.math.sin(angle).toFloat()
                canvas.drawText(symbol, px, py + dp(4), symbolPaint)
            }
        }
        canvas.drawText("把每个旋钮转到记忆中的字母", width / 2f, height - dp(12).toFloat(), hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (completed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val target = knobs.firstOrNull {
                    !it.done && hypot(event.x - it.cx, event.y - it.cy) < it.r * 1.8f
                } ?: return true
                active = target
                claimGesture(true)
            }
            MotionEvent.ACTION_MOVE -> active?.let { knob ->
                val angle = Math.toDegrees(
                    kotlin.math.atan2((event.y - knob.cy).toDouble(), (event.x - knob.cx).toDouble())
                ).toFloat()
                knob.current = ((angle % 360f) + 360f) % 360f
                invalidate()
            }
            MotionEvent.ACTION_UP -> active?.let { knob ->
                val snapped = (knob.current / 45f).roundToInt() * 45f
                knob.current = ((snapped % 360f) + 360f) % 360f
                val targetAngle = (knob.targetIndex * 45).toFloat()
                if (kotlin.math.abs(angleDiff(knob.current, targetAngle)) < 22f) {
                    knob.done = true
                    doneCount++
                    if (doneCount >= knobs.size) {
                        completed = true
                        onDone()
                    }
                }
                active = null
                claimGesture(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                active = null
                claimGesture(false)
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
