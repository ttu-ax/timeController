package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

class GateView(
    context: Context,
    targetLabel: String,
    targetPackage: String? = null,
    continueMinutes: Int?,
    private val frictionType: FrictionType,
    frictionConfig: GateFrictionConfig = GateFrictionConfig(),
    alternativeLabel: String,
    emergencyPauseMinutes: Int,
    previewMode: Boolean,
    private val onContinue: () -> Unit,
    private val onAlternative: () -> Unit,
    private val onPause: () -> Unit
) : FrameLayout(context) {
    private lateinit var traceView: TraceView
    private val continueButton: Button
    private lateinit var statusStrip: TextView

    /** 意图确认选出的继续时长(分钟),供放行期使用。 */
    var decidedMinutes: Int? = null

    /**
     * 横屏时视口只有 450dp 上下,而摩擦区是固定 130~300dp。
     * 不压缩的话摩擦区和按钮会被整个挤出屏幕,用户只能盲滚,门就没法推了。
     */
    private val compactLayout = resources.displayMetrics.heightPixels < dp(620)

    /** 矮视口下把摩擦区压到视口高度的 28%,保证动作区和按钮同屏。 */
    private fun frictionHeight(baseDp: Int): Int {
        if (!compactLayout) return dp(baseDp)
        val budget = (resources.displayMetrics.heightPixels * 0.28f).toInt()
        return min(dp(baseDp), budget)
    }

    private fun frictionGap(): Int = dp(if (compactLayout) 8 else 20)

    /** 竖屏下每种摩擦动作的基准高度;横屏两栏布局里不固定高度,由权重撑满。 */
    private fun frictionBaseHeight(): Int = when (frictionType) {
        FrictionType.TRACE -> 260
        FrictionType.TAP_SEQUENCE -> 250
        FrictionType.DRAG_SLIDER -> 130
        FrictionType.FLIP -> 190
        FrictionType.HOLD, FrictionType.BREATHE, FrictionType.SQUEEZE,
        FrictionType.READ_PHRASE, FrictionType.COUNTDOWN -> 170
        else -> 300
    }

    init {
        // 沉浸式静谧夜幕遮罩，隔绝底层目标 App 的视觉噪音
        setBackgroundColor(Color.argb(246, 17, 26, 20))
        setPadding(
            dp(20),
            dp(if (compactLayout) 10 else 42),
            dp(20),
            dp(if (compactLayout) 10 else 24)
        )

        traceView = TraceView(context, frictionConfig.traceShape).apply {
            contentDescription = "沿屏幕上的浅色轨迹描摹这道门:${frictionConfig.traceShape.label}"
        }

        val content = LinearLayout(context).apply {
            orientation = if (compactLayout) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (compactLayout) {
                android.view.Gravity.CENTER_VERTICAL
            } else {
                android.view.Gravity.CENTER_HORIZONTAL
            }
            setPadding(
                dp(if (compactLayout) 16 else 20),
                dp(if (compactLayout) 16 else 24),
                dp(if (compactLayout) 16 else 20),
                dp(if (compactLayout) 16 else 20)
            )
            background = UiTheme.shape(context, UiTheme.surface, 24, UiTheme.line)
        }

        // ---- 说明区(先创建,竖屏直接铺进 content,横屏收进左栏) ----
        val doorGapView = UiTheme.doorGap(context)
        val appIcon = if (compactLayout) {
            null
        } else {
            UiTheme.createAppIconView(context, targetPackage, targetLabel, 46, 13)
        }
        val titleView = label(
            if (previewMode) "测试这扇门" else "先停一下",
            if (compactLayout) 21f else 24f,
            true
        )
        val subtitleView = label("正在进入 $targetLabel", 13f).apply {
            setTextColor(UiTheme.muted)
        }

        val actionText = frictionType.action(frictionConfig)
        val guideText = if (continueMinutes == null) {
            "$actionText，本次进入将不再打扰。"
        } else {
            "$actionText，继续使用 $continueMinutes 分钟。"
        }
        val guidePill = TextView(context).apply {
            text = guideText
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(28, 122, 72))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = UiTheme.shape(context, UiTheme.accentSoft, UiTheme.RADIUS_PILL)
        }

        // ---- 摩擦动作视图:这里只负责创建,放在哪一栏由下面的布局分支决定 ----
        val frictionView: View = when (frictionType) {
            FrictionType.TRACE -> traceView
            FrictionType.HOLD ->
                holdFrictionView(continueMinutes, frictionConfig.holdSeconds)
            FrictionType.BREATHE ->
                breatheFrictionView(continueMinutes, frictionConfig.breatheCycles)
            FrictionType.TAP_SEQUENCE ->
                TapSequenceView(context, frictionConfig.tapCount).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                }
            FrictionType.DRAG_SLIDER ->
                DragSliderView(context, frictionConfig.dragPercent).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                }
            FrictionType.SQUEEZE ->
                SqueezeView(context, frictionConfig.squeezePercent).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                }
            FrictionType.READ_PHRASE ->
                readPhraseFrictionView(continueMinutes, frictionConfig.phrases)
            FrictionType.COUNTDOWN ->
                countdownFrictionView(continueMinutes, frictionConfig.countdownSeconds)
            FrictionType.FLIP ->
                FlipSensorView(context).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                }
            FrictionType.MENTAL_MATH ->
                MentalMathView(context, frictionConfig.mathLevel).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                }
            FrictionType.COUNT ->
                CountShapesView(context, frictionConfig.countTarget).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                }
            FrictionType.MEMORY_GRID, FrictionType.KNOB_LOCK, FrictionType.KEY_PATH,
            FrictionType.TARGET_SCAN, FrictionType.BOLT_SEQUENCE,
            FrictionType.REVERSE_MEMORY, FrictionType.PLATE_ASSEMBLY,
            FrictionType.MULTI_STAGE, FrictionType.REPEAT_SCAN, FrictionType.MEMORY_KNOB,
            FrictionType.REFLECT_WAIT, FrictionType.ROUTE_PLAN ->
                StageTaskView(context, frictionStages(frictionType, frictionConfig)).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                    onLeave = { onAlternative() }
                }
            FrictionType.INTENT_CONFIRM ->
                StageTaskView(context, frictionStages(frictionType, frictionConfig)).apply {
                    onCompleted = {
                        decidedMinutes = lastChoice?.filter { it.isDigit() }?.toIntOrNull()
                        completeFriction(decidedMinutes ?: continueMinutes)
                    }
                }
        }

        val lockedButtonText = frictionType.lockedHint
        statusStrip = TextView(context).apply {
            text = lockedButtonText
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(UiTheme.muted)
            background = UiTheme.shape(context, UiTheme.surfaceMuted, UiTheme.RADIUS_BUTTON)
            minHeight = dp(48)
        }
        continueButton = Button(context).apply {
            text = lockedButtonText
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = UiTheme.ripple(context, UiTheme.ink, UiTheme.accent, UiTheme.RADIUS_BUTTON)
            minHeight = dp(50)
            visibility = View.GONE
            setOnClickListener { onContinue() }
        }

        val alternative = Button(context).apply {
            text = "换个去处 · $alternativeLabel"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = UiTheme.ripple(context, UiTheme.accent, Color.rgb(28, 122, 72), UiTheme.RADIUS_BUTTON)
            minHeight = dp(50)
            setOnClickListener { onAlternative() }
        }

        val pause = Button(context).apply {
            text = if (previewMode) {
                "退出测试"
            } else {
                "紧急绕过：长按暂停 ${emergencyPauseMinutes} 分钟"
            }
            isAllCaps = false
            textSize = 13f
            setTextColor(UiTheme.muted)
            background = UiTheme.shape(context, UiTheme.surfaceMuted, UiTheme.RADIUS_PILL)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            if (previewMode) {
                setOnClickListener { onPause() }
            } else if (context.isScreenReaderActive()) {
                text = "紧急绕过：暂停 ${emergencyPauseMinutes} 分钟"
                contentDescription = "紧急绕过:暂停守门 ${emergencyPauseMinutes} 分钟"
                setOnClickListener { onPause() }
            } else {
                setOnLongClickListener {
                    text = "已暂停"
                    Handler(Looper.getMainLooper()).postDelayed({ onPause() }, 250)
                    true
                }
            }
        }

        if (compactLayout) {
            // ---- 横屏:左右两栏。宽度是富余的,把摩擦区放到右栏整块高度上,
            //      不再压成一条一百多 dp 的窄带 ----
            val infoColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            infoColumn.addView(doorGapView, LinearLayout.LayoutParams(dp(4), dp(16)))
            infoColumn.addView(titleView, linearParams(top = 12))
            infoColumn.addView(subtitleView, linearParams(top = 4))
            infoColumn.addView(guidePill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) })

            val actionColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            actionColumn.addView(frictionView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            actionColumn.addView(statusStrip, linearParams(top = 10))
            actionColumn.addView(continueButton, linearParams(top = 10))
            actionColumn.addView(alternative, linearParams(top = 10))
            actionColumn.addView(pause, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            })

            content.addView(infoColumn, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            content.addView(actionColumn, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1.35f).apply {
                marginStart = dp(18)
            })
            addView(content, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            content.addView(doorGapView, LinearLayout.LayoutParams(dp(4), dp(36)))
            if (appIcon != null) {
                content.addView(appIcon, LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                    topMargin = dp(12)
                })
            }
            content.addView(titleView, linearParams(top = 10))
            content.addView(subtitleView, linearParams(top = 2))
            content.addView(guidePill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            content.addView(frictionView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, frictionHeight(frictionBaseHeight())
            ).apply { topMargin = frictionGap() })
            content.addView(statusStrip, linearParams(top = 16))
            content.addView(continueButton, linearParams(top = 16))
            content.addView(alternative, linearParams(top = 10))
            content.addView(pause, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            })

            val scroller = ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = OVER_SCROLL_NEVER
                addView(content, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            addView(scroller, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        traceView.onCompleted = { completeFriction(continueMinutes) }
    }

    private fun completeFriction(continueMinutes: Int?) {
        statusStrip.visibility = View.GONE
        continueButton.visibility = View.VISIBLE
        continueButton.isEnabled = true
        continueButton.alpha = 1f
        continueButton.text = if (continueMinutes == null) {
            "推开它 · 本次继续"
        } else {
            "推开它 · 再用 $continueMinutes 分钟"
        }
    }

    private fun holdFrictionView(continueMinutes: Int?, seconds: Int): View {
        val sec = seconds.coerceIn(1, 30)
        if (context.isScreenReaderActive()) {
            return Button(context).apply {
                text = "我确认要继续"
                textSize = 17f
                isAllCaps = false
                setTextColor(UiTheme.ink)
                contentDescription = "无障碍模式下用一次点击代替长按:确认要继续"
                background = UiTheme.shape(context, UiTheme.accentSoft, 18, UiTheme.accent)
                setOnClickListener { completeFriction(continueMinutes) }
            }
        }
        val hold = Button(context).apply {
            text = "按住门槛"
            textSize = 17f
            isAllCaps = false
            setTextColor(Color.rgb(28, 122, 72))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            contentDescription = "按住 $sec 秒推开门槛"
            background = UiTheme.shape(context, UiTheme.accentSoft, 18, Color.argb(60, 46, 196, 118))
        }
        val handler = Handler(Looper.getMainLooper())
        val complete = Runnable {
            hold.text = "门槛已推开"
            hold.isEnabled = false
            completeFriction(continueMinutes)
        }
        hold.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hold.text = "继续按住…"
                    handler.postDelayed(complete, sec * 1_000L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (hold.isEnabled) hold.text = "按住门槛"
                    handler.removeCallbacks(complete)
                }
            }
            true
        }
        return hold
    }

    private fun readPhraseFrictionView(continueMinutes: Int?, phrases: List<String>): View {
        val pool = phrases.filter { it.isNotBlank() }.ifEmpty { GateFrictionConfig.DEFAULT_PHRASES }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        box.addView(label(pool.random(), 19f).apply {
            gravity = android.view.Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        })
        box.addView(Button(context).apply {
            text = "读完了"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = UiTheme.shape(context, UiTheme.ink, 14)
            setOnClickListener { completeFriction(continueMinutes) }
        }, linearParams(top = 18))
        return box
    }

    private fun countdownFrictionView(continueMinutes: Int?, seconds: Int): View {
        val sec = seconds.coerceIn(1, 30)
        val status = TextView(context).apply {
            text = "$sec"
            gravity = android.view.Gravity.CENTER
            textSize = 54f
            setTextColor(UiTheme.ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val handler = Handler(Looper.getMainLooper())
        for (i in 1 until sec) {
            handler.postDelayed({ status.text = "${sec - i}" }, i * 1_000L)
        }
        handler.postDelayed({
            status.text = "好了"
            completeFriction(continueMinutes)
        }, sec * 1_000L)
        return status
    }

    private fun breatheFrictionView(continueMinutes: Int?, cycles: Int): View {
        val cyc = cycles.coerceIn(1, 5)
        val status = TextView(context).apply {
            text = "吸气"
            gravity = android.view.Gravity.CENTER
            textSize = 26f
            setTextColor(Color.rgb(28, 122, 72))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = UiTheme.shape(context, UiTheme.accentSoft, 20, Color.argb(60, 46, 196, 118))
        }
        val handler = Handler(Looper.getMainLooper())
        var cycle = 0
        val tick = object : Runnable {
            override fun run() {
                cycle++
                if (cycle >= cyc * 2) {
                    status.text = "这次呼吸完成"
                    completeFriction(continueMinutes)
                } else {
                    status.text = if (cycle % 2 == 1) "呼气" else "吸气"
                    handler.postDelayed(this, 2_000)
                }
            }
        }
        handler.postDelayed(tick, 2_000)
        return status
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(UiTheme.ink)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
        if (bold) setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
    }

    private fun linearParams(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class TraceView(context: Context, private val shape: TraceShape) : View(context) {
        var onCompleted: (() -> Unit)? = null
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 243, 239)
            style = Paint.Style.STROKE
            strokeWidth = dp(20).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UiTheme.accent
            style = Paint.Style.STROKE
            strokeWidth = dp(8).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UiTheme.ink
            style = Paint.Style.FILL
        }
        private var guide = Path()
        private val userPath = Path()
        private val checkpoints = mutableListOf<Pair<Float, Float>>()
        private var checkpointIndex = 0
        private var started = false
        private var completed = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            guide = shape.buildPath(w.toFloat(), h.toFloat(), checkpoints)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(guide, guidePaint)
            canvas.drawPath(userPath, linePaint)
            checkpoints.firstOrNull()?.let { canvas.drawCircle(it.first, it.second, dp(7).toFloat(), dotPaint) }
            checkpoints.lastOrNull()?.let {
                dotPaint.style = Paint.Style.STROKE
                dotPaint.strokeWidth = dp(4).toFloat()
                canvas.drawCircle(it.first, it.second, dp(11).toFloat(), dotPaint)
                dotPaint.style = Paint.Style.FILL
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (completed) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (near(event.x, event.y, checkpoints.first())) {
                        started = true
                        checkpointIndex = 1
                        userPath.reset()
                        userPath.moveTo(event.x, event.y)
                        claimGesture(true)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_MOVE -> if (started) {
                    userPath.lineTo(event.x, event.y)
                    if (checkpointIndex < checkpoints.size &&
                        near(event.x, event.y, checkpoints[checkpointIndex])
                    ) {
                        checkpointIndex++
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    claimGesture(false)
                    if (started) {
                        if (checkpointIndex >= checkpoints.size) {
                            completed = true
                            onCompleted?.invoke()
                        } else {
                            started = false
                            checkpointIndex = 0
                            userPath.reset()
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    started = false
                    checkpointIndex = 0
                    userPath.reset()
                    claimGesture(false)
                    invalidate()
                }
            }
            return true
        }

        private fun near(x: Float, y: Float, point: Pair<Float, Float>): Boolean {
            return hypot(x - point.first, y - point.second) < dp(52)
        }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }
}
