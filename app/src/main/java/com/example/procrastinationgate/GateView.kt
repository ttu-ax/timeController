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

    init {
        setBackgroundColor(UiTheme.canvas)
        setPadding(dp(22), dp(48), dp(22), dp(24))

        traceView = TraceView(context, frictionConfig.traceShape)

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
        val actionText = when (frictionType) {
            FrictionType.TRACE -> "沿着轨迹画出这道门"
            FrictionType.HOLD -> "按住门槛 ${frictionConfig.holdSeconds} 秒"
            FrictionType.BREATHE -> if (frictionConfig.breatheCycles > 1) {
                "跟随门槛完成 ${frictionConfig.breatheCycles} 次呼吸"
            } else {
                "跟随门槛完成一次呼吸"
            }
            FrictionType.TAP_SEQUENCE -> "依次点亮 ${frictionConfig.tapCount} 个圆点"
            FrictionType.DRAG_SLIDER -> "把门槛拖到尽头"
            FrictionType.PINCH -> "双指按住，向内合拢"
            FrictionType.READ_PHRASE -> "读完这句话，再决定"
            FrictionType.COUNTDOWN -> "等 ${frictionConfig.countdownSeconds} 秒，再决定"
        }
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
            FrictionType.PINCH -> content.addView(
                PinchView(context, frictionConfig.pinchPercent).apply { onCompleted = { completeFriction(continueMinutes) } },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)).apply { topMargin = dp(24) }
            )
            FrictionType.READ_PHRASE -> content.addView(readPhraseFrictionView(continueMinutes, frictionConfig.phrases), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(24) })
            FrictionType.COUNTDOWN -> content.addView(countdownFrictionView(continueMinutes, frictionConfig.countdownSeconds), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(24) })
        }

        val lockedButtonText = when (frictionType) {
            FrictionType.TRACE -> "沿轨迹画完即可推开"
            FrictionType.HOLD -> "完成长按后即可推开"
            FrictionType.BREATHE -> "完成呼吸后即可推开"
            FrictionType.TAP_SEQUENCE -> "点亮全部圆点后即可推开"
            FrictionType.DRAG_SLIDER -> "拖到底即可推开"
            FrictionType.PINCH -> "完成捏合后即可推开"
            FrictionType.READ_PHRASE -> "读完后即可推开"
            FrictionType.COUNTDOWN -> "倒计时结束后即可推开"
        }
        continueButton = actionButton(lockedButtonText).apply {
            isEnabled = false
            alpha = 0.45f
            setOnClickListener { onContinue() }
        }
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
            } else {
                setOnLongClickListener {
                    text = "已暂停"
                    Handler(Looper.getMainLooper()).postDelayed({ onPause() }, 250)
                    true
                }
            }
        }
        content.addView(pause, linearParams(top = 8))

        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        traceView.onCompleted = { completeFriction(continueMinutes) }
    }

    private fun completeFriction(continueMinutes: Int?) {
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
        val hold = Button(context).apply {
            text = "按住门槛"
            textSize = 18f
            isAllCaps = false
            setTextColor(UiTheme.ink)
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
                MotionEvent.ACTION_UP -> if (started) {
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
            return true
        }

        private fun near(x: Float, y: Float, point: Pair<Float, Float>): Boolean {
            return hypot(x - point.first, y - point.second) < dp(52)
        }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    }
}
