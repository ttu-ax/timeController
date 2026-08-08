package com.example.procrastinationgate

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

object GateOverlayController {
    private var windowManager: WindowManager? = null
    private var gateView: GateView? = null

    fun show(
        context: Context,
        rule: GateRule,
        onResult: (GateResult) -> Unit,
        previewMode: Boolean = false
    ) {
        if (gateView != null) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = GatePreferences(appContext)
        val view = GateView(
            context = appContext,
            targetLabel = rule.targetLabel,
            continueMinutes = rule.repeatIntervalMinutes,
            frictionType = rule.frictionType,
            traceShape = rule.traceShapes.ifEmpty { setOf(TraceShape.S) }.random(),
            alternativeLabel = rule.exitLabel ?: prefs.alternativeLabel,
            emergencyPauseMinutes = rule.emergencyPauseMinutes,
            previewMode = previewMode,
            onContinue = {
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
