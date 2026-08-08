package com.example.procrastinationgate

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

object RuleEditorDialog {
    fun show(
        activity: Activity,
        rule: GateRule,
        installedApps: () -> List<AppEntry>,
        onSave: (GateRule) -> Unit,
        onTest: (GateRule) -> Unit,
        onDuplicate: ((GateRule) -> Unit)? = null,
        onDelete: (() -> Unit)? = null
    ) {
        lateinit var dialog: AlertDialog
        val ink = UiTheme.ink
        val background = UiTheme.canvas
        val surface = UiTheme.surface
        val accent = UiTheme.accent
        val muted = UiTheme.muted

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 24))
            setBackgroundColor(background)
        }
        root.addView(UiTheme.doorGap(activity), LinearLayout.LayoutParams(dp(activity, 5), dp(activity, 38)))
        root.addView(text(activity, rule.targetLabel, 28f, ink, true).margins(activity, top = 14))
        root.addView(text(activity, "决定门在什么时候出现，以及如何推开。", 14f, muted)
            .margins(activity, top = 4, bottom = 18))

        val enabled = Switch(activity).apply {
            text = "启用这条规则"
            textSize = 16f
            setTextColor(ink)
            isChecked = rule.enabled
        }
        root.addView(card(activity, surface, enabled))

        val triggerOnOpen = CheckBox(activity).apply {
            text = "每次打开时出现"
            textSize = 16f
            setTextColor(ink)
            isChecked = rule.triggerOnOpen
        }
        val delayEnabled = CheckBox(activity).apply {
            text = "连续使用后出现"
            textSize = 16f
            setTextColor(ink)
            isChecked = rule.initialDelayMinutes != null
        }
        val delayPicker = numberPicker(activity, rule.initialDelayMinutes ?: 5, 1, 120)
        val repeatEnabled = CheckBox(activity).apply {
            text = "推开后再次提醒"
            textSize = 16f
            setTextColor(ink)
            isChecked = rule.repeatIntervalMinutes != null
        }
        val repeatPicker = numberPicker(activity, rule.repeatIntervalMinutes ?: 10, 1, 120)
        root.addView(section(activity, "什么时候出现", surface).apply {
            addView(triggerOnOpen)
            addView(pickerRow(activity, delayEnabled, delayPicker, "分钟"))
            addView(pickerRow(activity, repeatEnabled, repeatPicker, "分钟"))
        }.margins(activity, top = 12))

        val dayChecks = mutableListOf<CheckBox>()
        val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")
        val daysRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dayNames.forEachIndexed { index, name ->
            val day = index + 1
            val check = CheckBox(activity).apply {
                text = name
                buttonTintList = android.content.res.ColorStateList.valueOf(ink)
                isChecked = day in rule.activeDays
                gravity = Gravity.CENTER
            }
            dayChecks += check
            daysRow.addView(check, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        }
        var startMinute = rule.startMinuteOfDay
        var endMinute = rule.endMinuteOfDay
        val allDay = CheckBox(activity).apply {
            text = "全天生效"
            textSize = 15f
            setTextColor(ink)
            isChecked = startMinute == 0 && endMinute == 24 * 60
        }
        val timeHint = text(activity, "", 13f, muted)
        val startButton = outlineButton(activity, timeLabel(startMinute), ink) {}
        startButton.setOnClickListener {
            selectTime(activity, startMinute) {
                startMinute = it
                startButton.text = timeLabel(it)
                allDay.isChecked = false
                updateTimeHint(timeHint, startMinute, endMinute)
            }
        }
        val endButton = outlineButton(activity, timeLabel(endMinute), ink) {}
        endButton.setOnClickListener {
            selectTime(activity, if (endMinute == 24 * 60) 0 else endMinute) {
                endMinute = if (it == 0) 24 * 60 else it
                endButton.text = timeLabel(endMinute)
                allDay.isChecked = false
                updateTimeHint(timeHint, startMinute, endMinute)
            }
        }
        allDay.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                startMinute = 0
                endMinute = 24 * 60
                startButton.text = "00:00"
                endButton.text = "24:00"
            }
            updateTimeHint(timeHint, startMinute, endMinute)
        }
        updateTimeHint(timeHint, startMinute, endMinute)
        root.addView(section(activity, "哪几天生效", surface).apply {
            addView(daysRow)
            addView(allDay)
            addView(text(activity, "生效时段", 13f, muted).margins(activity, top = 10))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(startButton, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
                addView(text(activity, " 至 ", 14f, muted))
                addView(endButton, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            }.margins(activity, top = 6))
            addView(timeHint.margins(activity, top = 6))
        }.margins(activity, top = 12))

        val frictionGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val frictionOptions = listOf(
            FrictionType.TRACE to "描摹轨迹 · 需要手眼配合",
            FrictionType.HOLD to "长按 3 秒 · 安静而明确",
            FrictionType.BREATHE to "呼吸一次 · 等待 4 秒"
        )
        frictionOptions.forEach { (type, label) ->
            frictionGroup.addView(RadioButton(activity).apply {
                id = type.ordinal + 100
                text = label
                textSize = 15f
                setTextColor(ink)
                isChecked = rule.frictionType == type
            })
        }
        root.addView(section(activity, "如何推开", surface, frictionGroup).margins(activity, top = 12))

        var exitPackage = rule.exitPackage
        var exitLabel = rule.exitLabel
        val exitValue = text(activity, exitLabel ?: "使用默认出口", 17f, ink, true)
        val exitSection = section(activity, "换个去处", surface).apply {
            addView(exitValue)
            addView(outlineButton(activity, "选择这条规则的出口", ink) {
                AppPickerDialog.show(activity, "选择现实出口", installedApps()) {
                    exitPackage = it.packageName
                    exitLabel = it.label
                    exitValue.text = it.label
                }
            }.margins(activity, top = 10))
            addView(outlineButton(activity, "使用默认出口", ink) {
                exitPackage = null
                exitLabel = null
                exitValue.text = "使用默认出口"
            }.margins(activity, top = 6))
        }
        root.addView(exitSection.margins(activity, top = 12))

        val pausePicker = numberPicker(activity, rule.emergencyPauseMinutes, 5, 120).apply {
            displayedValues = (5..120).map { "$it" }.toTypedArray()
        }
        root.addView(section(activity, "紧急绕过", surface).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(activity, "长按后暂停", 15f, ink),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(pausePicker, LinearLayout.LayoutParams(dp(activity, 82), dp(activity, 66)))
                addView(text(activity, "分钟", 14f, muted))
            })
        }.margins(activity, top = 12))

        root.addView(outlineButton(activity, "测试这扇门", ink) {
            onTest(buildRule(
                rule, enabled, triggerOnOpen, delayEnabled, delayPicker, repeatEnabled,
                repeatPicker, dayChecks, startMinute, endMinute, frictionGroup,
                exitPackage, exitLabel, pausePicker
            ))
        }.margins(activity, top = 12))

        if (onDuplicate != null || onDelete != null) {
            root.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                onDuplicate?.let { duplicate ->
                    addView(outlineButton(activity, "复制规则", ink) {
                        duplicate(buildRule(
                            rule, enabled, triggerOnOpen, delayEnabled, delayPicker, repeatEnabled,
                            repeatPicker, dayChecks, startMinute, endMinute, frictionGroup,
                            exitPackage, exitLabel, pausePicker
                        ))
                        dialog.dismiss()
                    }, LinearLayout.LayoutParams(0, dp(activity, 50), 1f))
                }
                onDelete?.let { delete ->
                    addView(outlineButton(activity, "删除规则", Color.rgb(242, 155, 100)) {
                        AlertDialog.Builder(activity)
                            .setTitle("删除 ${rule.targetLabel} 的规则？")
                            .setMessage("已有记录不会被删除。")
                            .setNegativeButton("保留", null)
                            .setPositiveButton("删除") { _, _ ->
                                delete()
                                dialog.dismiss()
                            }
                            .show()
                    }.margins(activity, top = 0), LinearLayout.LayoutParams(0, dp(activity, 50), 1f))
                }
            }.margins(activity, top = 8))
        }

        val scroll = ScrollView(activity).apply { addView(root) }
        dialog = AlertDialog.Builder(activity)
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存规则", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                confirmDiscard(activity) { dialog.dismiss() }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(ink)
                this.background = UiTheme.shape(activity, accent, 12)
                setOnClickListener {
                    val selectedDays = selectedDays(dayChecks)
                    if (!triggerOnOpen.isChecked && !delayEnabled.isChecked) {
                        android.widget.Toast.makeText(activity, "至少选择一种出现方式", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (selectedDays.isEmpty()) {
                        android.widget.Toast.makeText(activity, "至少选择一天", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    onSave(buildRule(
                        rule, enabled, triggerOnOpen, delayEnabled, delayPicker, repeatEnabled,
                        repeatPicker, dayChecks, startMinute, endMinute, frictionGroup,
                        exitPackage, exitLabel, pausePicker
                    ))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun buildRule(
        rule: GateRule,
        enabled: Switch,
        triggerOnOpen: CheckBox,
        delayEnabled: CheckBox,
        delayPicker: NumberPicker,
        repeatEnabled: CheckBox,
        repeatPicker: NumberPicker,
        dayChecks: List<CheckBox>,
        startMinute: Int,
        endMinute: Int,
        frictionGroup: RadioGroup,
        exitPackage: String?,
        exitLabel: String?,
        pausePicker: NumberPicker
    ): GateRule {
        val friction = FrictionType.entries.firstOrNull {
            it.ordinal + 100 == frictionGroup.checkedRadioButtonId
        } ?: FrictionType.TRACE
        return rule.copy(
            enabled = enabled.isChecked,
            triggerOnOpen = triggerOnOpen.isChecked,
            initialDelayMinutes = delayPicker.value.takeIf { delayEnabled.isChecked },
            repeatIntervalMinutes = repeatPicker.value.takeIf { repeatEnabled.isChecked },
            activeDays = selectedDays(dayChecks),
            startMinuteOfDay = startMinute,
            endMinuteOfDay = endMinute,
            frictionType = friction,
            exitPackage = exitPackage,
            exitLabel = exitLabel,
            emergencyPauseMinutes = pausePicker.value
        )
    }

    private fun selectedDays(checks: List<CheckBox>) =
        checks.mapIndexedNotNull { index, check -> (index + 1).takeIf { check.isChecked } }.toSet()

    private fun updateTimeHint(view: TextView, start: Int, end: Int) {
        view.text = when {
            start == 0 && end == 24 * 60 -> "全天生效"
            start > end -> "跨午夜生效，到第二天 ${timeLabel(end)} 结束"
            else -> "当天 ${timeLabel(start)}–${timeLabel(end)} 生效"
        }
    }

    private fun confirmDiscard(activity: Activity, discard: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("放弃未保存的修改？")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃修改") { _, _ -> discard() }
            .show()
    }

    private fun section(
        activity: Activity,
        title: String,
        color: Int,
        vararg children: android.view.View
    ) = card(activity, color, text(activity, title.uppercase(), 12f, UiTheme.muted, true), *children)

    private fun card(activity: Activity, color: Int, vararg children: android.view.View) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 15), dp(activity, 14), dp(activity, 15), dp(activity, 14))
            background = UiTheme.shape(activity, color, 18, UiTheme.line)
            children.forEachIndexed { index, child ->
                addView(child.margins(activity, top = if (index == 0) 0 else 8))
            }
        }

    private fun pickerRow(activity: Activity, check: CheckBox, picker: NumberPicker, suffix: String) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(picker, LinearLayout.LayoutParams(dp(activity, 82), dp(activity, 66)))
            addView(text(activity, suffix, 14f, UiTheme.muted))
        }

    private fun numberPicker(activity: Activity, value: Int, min: Int, max: Int) =
        NumberPicker(activity).apply {
            minValue = min
            maxValue = max
            this.value = value.coerceIn(min, max)
            wrapSelectorWheel = false
        }

    private fun selectTime(activity: Activity, minute: Int, onSelected: (Int) -> Unit) {
        TimePickerDialog(activity, { _, hour, min -> onSelected(hour * 60 + min) },
            minute / 60, minute % 60, true).show()
    }

    private fun timeLabel(minute: Int): String {
        if (minute == 24 * 60) return "24:00"
        return "%02d:%02d".format(minute / 60, minute % 60)
    }

    private fun text(activity: Activity, value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun outlineButton(activity: Activity, value: String, color: Int, action: () -> Unit) =
        Button(activity).apply {
            text = value
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = UiTheme.shape(activity, color, 12)
            setOnClickListener { action() }
        }

    private fun <T : android.view.View> T.margins(
        activity: Activity,
        top: Int = 0,
        bottom: Int = 0
    ): T {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(activity, top), 0, dp(activity, bottom)) }
        return this
    }

    private fun dp(activity: Activity, value: Int) =
        (value * activity.resources.displayMetrics.density).toInt()
}
