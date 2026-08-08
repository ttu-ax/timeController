package com.example.procrastinationgate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object OnboardingDialog {
    fun show(
        activity: Activity,
        installedApps: () -> List<AppEntry>,
        hasUsageAccess: () -> Boolean,
        onComplete: (GateRule) -> Unit
    ) {
        val ink = UiTheme.ink
        val background = UiTheme.canvas
        val surface = UiTheme.surface
        val accent = UiTheme.accent
        val muted = UiTheme.muted
        var step = 0
        var selected: AppEntry? = null

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 28), dp(activity, 24), dp(activity, 24))
            setBackgroundColor(background)
        }
        lateinit var dialog: AlertDialog

        fun render() {
            root.removeAllViews()
            root.addView(text(activity, "门槛 ${step + 1} / 4", 13f, muted, true))
            when (step) {
                0 -> {
                    root.addView(text(activity, "不阻止，只让你停一下", 30f, ink, true)
                        .margins(activity, top = 12))
                    root.addView(text(
                        activity,
                        "打开目标 App 时，拖延之门会请你完成一个两三秒的小动作。你仍然可以继续，也可以换个去处。",
                        16f, muted
                    ).margins(activity, top = 14))
                    root.addView(threshold(activity, ink, accent).margins(activity, top = 28))
                    root.addView(primary(activity, "理解了，继续", accent, ink) {
                        step = 1
                        render()
                    }.margins(activity, top = 28))
                }
                1 -> {
                    root.addView(text(activity, "允许守门工作", 30f, ink, true)
                        .margins(activity, top = 12))
                    root.addView(text(activity, "两项权限都只在本机使用。", 15f, muted)
                        .margins(activity, top = 8, bottom = 18))
                    root.addView(permissionCard(
                        activity, surface, ink, muted,
                        "使用情况访问", hasUsageAccess(),
                        "识别当前打开的是哪个 App"
                    ) { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
                    root.addView(permissionCard(
                        activity, surface, ink, muted,
                        "显示在其他应用上层", Settings.canDrawOverlays(activity),
                        "在目标 App 上显示这扇门"
                    ) {
                        activity.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        ))
                    }.margins(activity, top = 10))
                    root.addView(primary(activity, "权限已处理，继续", accent, ink) {
                        if (!hasUsageAccess() || !Settings.canDrawOverlays(activity)) {
                            Toast.makeText(activity, "两项必要权限尚未全部开启", Toast.LENGTH_SHORT).show()
                        } else {
                            step = 2
                            render()
                        }
                    }.margins(activity, top = 20))
                }
                2 -> {
                    root.addView(text(activity, "先守住一个入口", 30f, ink, true)
                        .margins(activity, top = 12))
                    root.addView(text(
                        activity,
                        "选择你最容易无意识打开的那个 App。之后可以继续添加。",
                        15f, muted
                    ).margins(activity, top = 8, bottom = 18))
                    root.addView(card(activity, surface).apply {
                        addView(text(
                            activity,
                            selected?.label ?: "尚未选择目标 App",
                            20f, ink, true
                        ))
                        addView(secondary(activity, "选择目标 App", ink) {
                            AppPickerDialog.show(activity, "选择第一个目标", installedApps()) {
                                selected = it
                                render()
                            }
                        }.margins(activity, top = 12))
                    })
                    root.addView(primary(activity, "创建这条规则", accent, ink) {
                        if (selected == null) {
                            Toast.makeText(activity, "请先选择目标 App", Toast.LENGTH_SHORT).show()
                        } else {
                            step = 3
                            render()
                        }
                    }.margins(activity, top = 20))
                }
                else -> {
                    val target = selected ?: return
                    val rule = GateRule(
                        targetPackage = target.packageName,
                        targetLabel = target.label,
                        triggerOnOpen = true,
                        frictionType = FrictionType.TRACE
                    )
                    root.addView(text(activity, "亲手推一次", 30f, ink, true)
                        .margins(activity, top = 12))
                    root.addView(text(
                        activity,
                        "这是预览，不会写入正式记录。完成后即可开始守门。",
                        15f, muted
                    ).margins(activity, top = 8))
                    root.addView(primary(activity, "测试这扇门", accent, ink) {
                        GateOverlayController.show(activity, rule, onResult = {}, previewMode = true)
                    }.margins(activity, top = 24))
                    root.addView(secondary(activity, "完成设置，开始守门", ink) {
                        onComplete(rule)
                        dialog.dismiss()
                    }.margins(activity, top = 10))
                    root.addView(text(
                        activity,
                        "如果系统容易清理后台服务，可稍后在“设置 → 后台运行”中调整。",
                        13f, muted
                    ).margins(activity, top = 18))
                }
            }
        }

        render()
        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(false)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun permissionCard(
        activity: Activity,
        surface: Int,
        ink: Int,
        muted: Int,
        title: String,
        granted: Boolean,
        description: String,
        action: () -> Unit
    ) = card(activity, surface).apply {
        addView(text(activity, "$title  ${if (granted) "已开启" else "未开启"}", 17f, ink, true))
        addView(text(activity, description, 13f, muted).margins(activity, top = 5))
        if (!granted) addView(secondary(activity, "去开启", ink, action).margins(activity, top = 10))
    }

    private fun threshold(activity: Activity, ink: Int, accent: Int) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 14), 0, dp(activity, 14))
            background = UiTheme.shape(activity, ink, 20)
            repeat(7) { index ->
                addView(View(activity).apply {
                    setBackgroundColor(if (index == 4) accent else Color.rgb(248, 250, 249))
                }, LinearLayout.LayoutParams(
                    if (index == 4) dp(activity, 4) else dp(activity, 2),
                    if (index == 4) dp(activity, 42) else dp(activity, 20)
                ).apply { setMargins(dp(activity, 12), 0, dp(activity, 12), 0) })
            }
        }

    private fun card(activity: Activity, color: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16))
        background = UiTheme.shape(activity, color, 18, UiTheme.line)
    }

    private fun primary(activity: Activity, value: String, color: Int, textColor: Int, action: () -> Unit) =
        Button(activity).apply {
            text = value
            isAllCaps = false
            setTextColor(textColor)
            background = UiTheme.shape(activity, color, 14)
            setOnClickListener { action() }
        }

    private fun secondary(activity: Activity, value: String, color: Int, action: () -> Unit) =
        Button(activity).apply {
            text = value
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = UiTheme.shape(activity, color, 12)
            setOnClickListener { action() }
        }

    private fun text(activity: Activity, value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun <T : View> T.margins(activity: Activity, top: Int = 0, bottom: Int = 0): T {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(activity, top), 0, dp(activity, bottom)) }
        return this
    }

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()
}
