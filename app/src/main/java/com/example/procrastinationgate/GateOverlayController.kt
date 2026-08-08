package com.example.procrastinationgate

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

object GateOverlayController {
    private var windowManager: WindowManager? = null
    private var gateView: GateView? = null

    // 难度递增状态:上次触发时间、连续触发次数、最近一次展示的摩擦
    private var lastTriggerAt = 0L
    private var streak = 0
    private var lastChosen: FrictionType? = null

    /** 本次门通过后确定的继续时长(分钟);意图确认选定时覆盖规则间隔。 */
    var lastContinueMinutes: Int? = null

    /** 按规则难度档与触发间隔选择摩擦方式(10 分钟内再触发升档,连续多次到最高档,同轮不重复)。 */
    private fun pickFrictionType(rule: GateRule): FrictionType {
        val now = System.currentTimeMillis()
        streak = if (now - lastTriggerAt < 10 * 60_000L) streak + 1 else 1
        lastTriggerAt = now
        val available = rule.frictionTypes.ifEmpty { setOf(FrictionType.TRACE) }
        val level = when (rule.ruleDifficulty) {
            RuleDifficulty.EASY -> Difficulty.EASY
            RuleDifficulty.MEDIUM -> Difficulty.MEDIUM
            RuleDifficulty.HARD -> Difficulty.HARD
            RuleDifficulty.PROGRESSIVE -> when {
                streak >= 3 -> Difficulty.HARD
                streak >= 2 -> Difficulty.MEDIUM
                else -> Difficulty.EASY
            }
        }
        val pool = available.filter { it.difficulty == level }
        val base = if (pool.isNotEmpty()) pool else available
        val candidate = base.filter { it != lastChosen }.ifEmpty { base }
        val chosen = candidate.random()
        lastChosen = chosen
        return chosen
    }

    fun show(
        context: Context,
        rule: GateRule,
        onResult: (GateResult) -> Unit,
        previewMode: Boolean = false
    ) {
        if (gateView != null) return
        lastContinueMinutes = null
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = GatePreferences(appContext)
        lateinit var view: GateView
        view = GateView(
            context = appContext,
            targetLabel = rule.targetLabel,
            continueMinutes = rule.repeatIntervalMinutes,
            frictionType = pickFrictionType(rule),
            frictionConfig = GateFrictionConfig(
                traceShape = rule.traceShapes.ifEmpty { setOf(TraceShape.S) }.random(),
                holdSeconds = rule.holdSeconds,
                breatheCycles = rule.breatheCycles,
                tapCount = rule.tapCount,
                dragPercent = rule.dragPercent,
                pinchPercent = rule.pinchPercent,
                countdownSeconds = rule.countdownSeconds,
                phrases = rule.phrases.ifEmpty { GateFrictionConfig.DEFAULT_PHRASES },
                memoryCount = rule.memoryCount,
                knobCount = rule.knobCount,
                turnCount = rule.turnCount,
                routeSize = rule.routeSize
            ),
            alternativeLabel = rule.exitLabel ?: prefs.alternativeLabel,
            emergencyPauseMinutes = rule.emergencyPauseMinutes,
            previewMode = previewMode,
            onContinue = {
                lastContinueMinutes = view.decidedMinutes
                onResult(GateResult.CONTINUE)
                dismiss()
            },
            onAlternative = {
                onResult(GateResult.LEAVE)
                dismiss()
                val launch = (rule.exitPackage ?: prefs.alternativePackage)?.let {
                    appContext.packageManager.getLaunchIntentForPackage(it)
                } ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launch)
            },
            onPause = {
                if (!previewMode) {
                    prefs.bypassUntil =
                        System.currentTimeMillis() + rule.emergencyPauseMinutes * 60_000L
                }
                onResult(GateResult.BYPASS)
                dismiss()
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager = manager
        gateView = view
        manager.addView(view, params)
    }

    fun isShowing(): Boolean = gateView != null

    fun dismiss() {
        gateView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        gateView = null
        windowManager = null
    }
}
