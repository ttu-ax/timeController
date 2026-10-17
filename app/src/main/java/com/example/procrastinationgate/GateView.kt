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

class GateView(
    context: Context,
    targetLabel: String,
    continueMinutes: Int?,
    frictionType: FrictionType,
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

    init {
        setBackgroundColor(UiTheme.canvas)
        setPadding(dp(22), dp(48), dp(22), dp(24))

        traceView = TraceView(context, frictionConfig.traceShape).apply {
            contentDescription = "沿屏幕上的浅色轨迹描摹这道门:${frictionConfig.traceShape.label}"
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(26), dp(18), dp(18))
            background = UiTheme.shape(context, UiTheme.surface, 24, UiTheme.line)
        }

        content.addView(UiTheme.doorGap(context), LinearLayout.LayoutParams(dp(5), dp(48)))
        content.addView(label(if (previewMode) "测试这扇门" else "先停一下", 30f, true),
            linearParams(top = 18))
        content.addView(label(targetLabel, 14f).apply {
            setTextColor(UiTheme.muted)
        }, linearParams(top = 6))
        // 动作文案统一由 FrictionType 提供,新增类型不会再漏改这里
        val actionText = frictionType.action(frictionConfig)
        val guideText = if (continueMinutes == null) {
            "$actionText，本次进入将不再打扰。"
        } else {
            "$actionText，继续使用 $continueMinutes 分钟。"
        }
        content.addView(label(guideText, 17f).apply {
            gravity = android.view.Gravity.CENTER
        }, linearParams(top = 12))

        when (frictionType) {
            FrictionType.TRACE -> content.addView(traceView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(280)
            ).apply { topMargin = dp(24) })
            FrictionType.HOLD -> content.addView(holdFrictionView(continueMinutes, frictionConfig.holdSeconds), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(24) })
            FrictionType.BREATHE -> content.addView(breatheFrictionView(continueMinutes, frictionConfig.breatheCycles), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(24) })
            FrictionType.TAP_SEQUENCE -> content.addView(
                TapSequenceView(context, frictionConfig.tapCount).apply { onCompleted = { completeFriction(continueMinutes) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)).apply { topMargin = dp(24) }
            )
            FrictionType.DRAG_SLIDER -> content.addView(
                DragSliderView(context, frictionConfig.dragPercent).apply { onCompleted = { completeFriction(continueMinutes) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)).apply { topMargin = dp(24) }
            )
            FrictionType.SQUEEZE -> content.addView(
                SqueezeView(context, frictionConfig.squeezePercent).apply { onCompleted = { completeFriction(continueMinutes) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)).apply { topMargin = dp(24) }
            )
            FrictionType.READ_PHRASE -> content.addView(readPhraseFrictionView(continueMinutes, frictionConfig.phrases), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(24) })
            FrictionType.COUNTDOWN -> content.addView(countdownFrictionView(continueMinutes, frictionConfig.countdownSeconds), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(24) })
            FrictionType.FLIP -> content.addView(
                FlipSensorView(context).apply { onCompleted = { completeFriction(continueMinutes) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(24) }
            )
            FrictionType.MENTAL_MATH -> content.addView(
                MentalMathView(context, frictionConfig.mathLevel).apply { onCompleted = { completeFriction(continueMinutes) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(24) }
            )
            FrictionType.COUNT -> content.addView(
                CountShapesView(context, frictionConfig.countTarget).apply { onCompleted = { completeFriction(continueMinutes) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(24) }
            )
            FrictionType.MEMORY_GRID, FrictionType.KNOB_LOCK, FrictionType.KEY_PATH,
            FrictionType.TARGET_SCAN, FrictionType.BOLT_SEQUENCE,
            FrictionType.REVERSE_MEMORY, FrictionType.PLATE_ASSEMBLY,
            FrictionType.MULTI_STAGE, FrictionType.REPEAT_SCAN, FrictionType.MEMORY_KNOB,
            FrictionType.REFLECT_WAIT, FrictionType.ROUTE_PLAN -> content.addView(
                StageTaskView(context, frictionStages(frictionType, frictionConfig)).apply {
                    onCompleted = { completeFriction(continueMinutes) }
                    // 阶段里选择「离开」类选项时,和点「换个去处」走同一条出口
                    onLeave = { onAlternative() }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(24) }
            )
            FrictionType.INTENT_CONFIRM -> content.addView(
                StageTaskView(context, frictionStages(frictionType, frictionConfig)).apply {
                    onCompleted = {
                        decidedMinutes = lastChoice?.filter { it.isDigit() }?.toIntOrNull()
                        completeFriction(decidedMinutes ?: continueMinutes)
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(24) }
            )
            // 故意不写 else:新增 FrictionType 时这里必须补分支,
            // 否则编译不过,不会再静默显示"此摩擦即将上线"的死门。
        }

        // 未完成动作时这里不是"一个灰掉的按钮",而是一条状态提示:
        // 之前用户面对一扇门、前两秒没有任何看起来能点的东西,不知道要做什么。
        val lockedButtonText = frictionType.lockedHint
        statusStrip = TextView(context).apply {
            text = lockedButtonText
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(UiTheme.muted)
            background = UiTheme.shape(context, UiTheme.surfaceMuted, UiTheme.RADIUS_BUTTON)
            minHeight = dp(52)
        }
        continueButton = actionButton(lockedButtonText).apply {
            visibility = View.GONE
            setOnClickListener { onContinue() }
        }
        content.addView(statusStrip, linearParams(top = 18))
        content.addView(continueButton, linearParams(top = 18))

        val alternative = actionButton("换个去处 · $alternativeLabel").apply {
            setTextColor(UiTheme.ink)
            background = UiTheme.shape(context, UiTheme.accent, 14)
            setOnClickListener { onAlternative() }
        }
        content.addView(alternative, linearParams(top = 10))

        val pause = Button(context).apply {
            text = if (previewMode) {
                "退出测试"
            } else {
                "紧急绕过：长按暂停${emergencyPauseMinutes}分钟"
            }
            isAllCaps = false
            setTextColor(UiTheme.muted)
            setBackgroundColor(Color.TRANSPARENT)
            if (previewMode) {
                setOnClickListener { onPause() }
            } else if (context.isScreenReaderActive()) {
                // 读屏模式下「长按」不可靠,降级为一次明确的点击
                text = "紧急绕过：暂停${emergencyPauseMinutes}分钟"
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
        content.addView(pause, linearParams(top = 8))

        // 内容可能高于小屏可用高度,用 ScrollView 兜住,避免把「紧急绕过」裁掉
        val scroller = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

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
        // 读屏模式下「按住 N 秒」既听不见进度也容易误松手,降级为一次明确的点击确认
        if (context.isScreenReaderActive()) {
            return Button(context).apply {
                text = "我确认要继续"
                textSize = 18f
                isAllCaps = false
                setTextColor(UiTheme.ink)
                contentDescription = "无障碍模式下用一次点击代替长按:确认要继续"
                background = UiTheme.shape(context, UiTheme.accentSoft, 18, UiTheme.accent)
                setOnClickListener { completeFriction(continueMinutes) }
            }
        }
        val hold = Button(context).apply {
            text = "按住门槛"
            textSize = 18f
            isAllCaps = false
            setTextColor(UiTheme.ink)
            contentDescription = "按住 $sec 秒推开门槛"
            background = UiTheme.shape(context, UiTheme.accentSoft, 18, UiTheme.accent)
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
        box.addView(label(pool.random(), 20f).apply {
            gravity = android.view.Gravity.CENTER
        })
        box.addView(Button(context).apply {
            text = "读完了"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
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
            textSize = 56f
            setTextColor(UiTheme.ink)
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
            textSize = 28f
            setTextColor(UiTheme.ink)
            background = UiTheme.shape(context, UiTheme.accentSoft, 18, UiTheme.accent)
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
        setLineSpacing(0f, 1.12f)
        if (bold) setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
    }

    private fun actionButton(value: String) = Button(context).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = UiTheme.shape(context, UiTheme.ink, 14)
        minHeight = dp(54)
    }

    private fun linearParams(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class TraceView(context: Context, private val shape: TraceShape) : View(context) {
        var onCompleted: (() -> Unit)? = null
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UiTheme.line
            style = Paint.Style.STROKE
            strokeWidth = dp(18).toFloat()
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
