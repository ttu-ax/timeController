package com.example.procrastinationgate

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 首次使用的四步引导。
 *
 * 从"背后露出首页的半屏对话框"改成全屏:第一次打开应用时用户还没有任何心智模型,
 * 屏幕里不该同时出现两个界面。步骤指示固定在顶部,内容可滚动。
 */
object OnboardingDialog {
    private const val STEP_COUNT = 4

    fun show(
        activity: Activity,
        installedApps: () -> List<AppEntry>,
        hasUsageAccess: () -> Boolean,
        /** 从「设置 → 重新查看首次引导」进来时允许关闭,否则用户会被困在引导里。 */
        allowDismiss: Boolean = false,
        onComplete: (GateRule) -> Unit
    ) {
        val ink = UiTheme.ink
        val canvasColor = UiTheme.canvas
        val surface = UiTheme.surface
        val accent = UiTheme.accent
        val muted = UiTheme.muted
        var step = 0
        var selected: AppEntry? = null

        val stepLabel = text(activity, "", 13f, muted, true)
        val stepDots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(canvasColor)
            setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 8))
        }
        header.addView(stepLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(stepDots)
        lateinit var dialog: Dialog
        if (allowDismiss) {
            header.addView(Button(activity).apply {
                text = "关闭"
                textSize = 14f
                isAllCaps = false
                setTextColor(muted)
                setBackgroundColor(Color.TRANSPARENT)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(activity, 12), 0, 0, 0)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(activity, 40)).apply {
                marginStart = dp(activity, 12)
            })
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 32))
        }

        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 16))
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
            addView(header, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(activity).apply {
                setBackgroundColor(canvasColor)
                addView(content)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(canvasColor))
        }
        // 首次引导不可跳过;从设置里重新查看时可以用「关闭」退出
        dialog.setCancelable(allowDismiss)
        dialog.setCanceledOnTouchOutside(false)

        fun render() {
            stepLabel.text = "门槛 ${step + 1} / $STEP_COUNT"
            stepDots.removeAllViews()
            repeat(STEP_COUNT) { index ->
                stepDots.addView(View(activity).apply {
                    background = UiTheme.shape(
                        activity,
                        if (index <= step) accent else UiTheme.line,
                        UiTheme.RADIUS_PILL
                    )
                }, LinearLayout.LayoutParams(
                    dp(activity, if (index == step) 18 else 6), dp(activity, 6)
                ).apply {
                    if (index > 0) marginStart = dp(activity, 5)
                })
            }

            content.removeAllViews()
            // 主操作固定在底栏:内容多的时候不用滚到底,单手也够得到
            footer.removeAllViews()
            when (step) {
                0 -> {
                    content.addView(title(activity, "不阻止，只让你停一下"))
                    content.addView(body(activity, "打开目标 App 时，拖延之门会请你完成一个两三秒的小动作。" +
                            "你仍然可以继续，也可以换个去处。"
                    ))
                    content.addView(threshold(activity, ink, accent).margins(activity, top = 28))
                    footer.addView(primary(activity, "理解了，继续", accent) {
                        step = 1
                        render()
                    })
                }
                1 -> {
                    content.addView(title(activity, "允许守门工作"))
                    content.addView(body(activity, "两项权限都只在本机使用，不会上传任何内容。"))
                    content.addView(permissionCard(
                        activity, "使用情况访问", hasUsageAccess(),
                        "识别当前打开的是哪个 App"
                    ) { activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
                    content.addView(permissionCard(
                        activity, "显示在其他应用上层", Settings.canDrawOverlays(activity),
                        "在目标 App 上显示这扇门"
                    ) {
                        activity.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${activity.packageName}")
                        ))
                    }.margins(activity, top = 10))
                    footer.addView(primary(activity, "权限已处理，继续", accent) {
                        if (!hasUsageAccess() || !Settings.canDrawOverlays(activity)) {
                            Toast.makeText(activity, "两项必要权限尚未全部开启", Toast.LENGTH_SHORT).show()
                        } else {
                            step = 2
                            render()
                        }
                    })
                }
                2 -> {
                    content.addView(title(activity, "先守住一个入口"))
                    content.addView(body(activity, "选择你最容易无意识打开的那个 App。之后可以继续添加更多目标。"))
                    content.addView(card(activity, surface).apply {
                        if (selected != null) {
                            val appRow = LinearLayout(activity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            val appIcon = UiTheme.createAppIconView(activity, selected?.packageName, selected?.label ?: "", 44, 12)
                            appRow.addView(appIcon, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply { rightMargin = dp(activity, 12) })
                            appRow.addView(text(activity, selected?.label ?: "", 18f, ink, true))
                            addView(appRow)
                        } else {
                            addView(text(activity, "尚未选择目标 App", 18f, ink, true))
                        }
                        addView(ghost(activity, if (selected == null) "选择目标 App" else "重新选择") {
                            AppPickerDialog.show(activity, "选择第一个目标", installedApps()) {
                                selected = it
                                render()
                            }
                        }.margins(activity, top = 14))
                    })
                    footer.addView(primary(activity, "创建这条规则", accent) {
                        if (selected == null) {
                            Toast.makeText(activity, "请先选择目标 App", Toast.LENGTH_SHORT).show()
                        } else {
                            step = 3
                            render()
                        }
                    })
                }
                else -> {
                    val target = selected ?: return
                    val rule = GateRule(
                        targetPackage = target.packageName,
                        targetLabel = target.label,
                        triggerOnOpen = true,
                        frictionTypes = setOf(FrictionType.TRACE)
                    )
                    content.addView(title(activity, "亲手推一次"))
                    content.addView(body(activity, "这是预览，不会写入正式记录。完成后即可开始守门。"))
                    content.addView(text(
                        activity,
                        "如果系统容易清理后台服务，可稍后在「设置 → 后台运行」中调整。",
                        13f, muted
                    ).margins(activity, top = 20))
                    footer.addView(primary(activity, "测试这扇门", accent) {
                        GateOverlayController.show(activity, rule, onResult = {}, previewMode = true)
                    })
                    footer.addView(ghost(activity, "完成设置，开始守门") {
                        onComplete(rule)
                        dialog.dismiss()
                    }.margins(activity, top = 10))
                }
            }
        }

        render()
        dialog.show()
    }

    // ------------------------------------------------------------------

    private fun title(activity: Activity, value: String) =
        text(activity, value, UiTheme.TEXT_PAGE_TITLE, UiTheme.ink, true)
            .margins(activity, top = 8)

    private fun body(activity: Activity, value: String) =
        text(activity, value, 16f, UiTheme.muted).margins(activity, top = 12)

    private fun permissionCard(
        activity: Activity,
        title: String,
        granted: Boolean,
        description: String,
        action: () -> Unit
    ) = card(activity, UiTheme.surface).apply {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(text(activity, title, UiTheme.TEXT_CARD_TITLE, UiTheme.ink, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(
            activity,
            if (granted) "已开启" else "未开启",
            UiTheme.TEXT_CAPTION,
            if (granted) UiTheme.ink else Color.rgb(154, 93, 32),
            true
        ).apply {
            setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5))
            background = UiTheme.shape(
                activity,
                if (granted) UiTheme.accentSoft else Color.rgb(253, 236, 219),
                UiTheme.RADIUS_PILL
            )
        })
        addView(row)
        addView(text(activity, description, 13f, UiTheme.muted).margins(activity, top = 6))
        if (!granted) addView(ghost(activity, "去开启", action).margins(activity, top = 12))
    }

    private fun threshold(activity: Activity, ink: Int, accent: Int) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 16), 0, dp(activity, 16))
            background = UiTheme.shape(activity, ink, UiTheme.RADIUS_CARD)
            repeat(7) { index ->
                addView(View(activity).apply {
                    setBackgroundColor(if (index == 4) accent else Color.rgb(248, 250, 249))
                }, LinearLayout.LayoutParams(
                    if (index == 4) dp(activity, 4) else dp(activity, 2),
                    if (index == 4) dp(activity, 44) else dp(activity, 20)
                ).apply { setMargins(dp(activity, 12), 0, dp(activity, 12), 0) })
            }
        }

    private fun card(activity: Activity, color: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(activity, UiTheme.PAD_CARD)
        setPadding(pad, pad, pad, pad)
        background = UiTheme.shape(activity, color, UiTheme.RADIUS_CARD, UiTheme.line)
    }

    private fun primary(activity: Activity, value: String, color: Int, action: () -> Unit) =
        Button(activity).apply {
            text = value
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = UiTheme.ripple(activity, UiTheme.ink, UiTheme.accent, UiTheme.RADIUS_BUTTON)
            minHeight = dp(activity, UiTheme.HEIGHT_PRIMARY)
            setOnClickListener { action() }
        }

    private fun ghost(activity: Activity, value: String, action: () -> Unit = {}) =
        Button(activity).apply {
            text = value
            textSize = 14f
            isAllCaps = false
            setTextColor(UiTheme.ink)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = UiTheme.ripple(
                activity, UiTheme.surface, UiTheme.accentSoft, UiTheme.RADIUS_BUTTON, UiTheme.line, 1)
            minHeight = dp(activity, UiTheme.HEIGHT_ACTION)
            setOnClickListener { action() }
        }

    private fun text(
        activity: Activity,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
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
