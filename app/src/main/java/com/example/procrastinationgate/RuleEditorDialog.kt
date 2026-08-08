package com.example.procrastinationgate

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
            addView(selectAllButton(activity, dayChecks).margins(activity, top = 6))
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

        var frictionState = rule
        val frictionChecks = mutableListOf<CheckBox>()
        val frictionTypeList = mutableListOf<FrictionType>()
        val frictionRows = mutableListOf<android.view.View>()
        var onFrictionChanged: (() -> Unit)? = null
        fun frictionConfigSummary(type: FrictionType): String = when (type) {
            FrictionType.TRACE -> "${frictionState.traceShapes.size} 种轨迹"
            FrictionType.HOLD -> "${frictionState.holdSeconds} 秒"
            FrictionType.BREATHE -> "${frictionState.breatheCycles} 次"
            FrictionType.TAP_SEQUENCE -> "${frictionState.tapCount} 个"
            FrictionType.DRAG_SLIDER -> "${frictionState.dragPercent}%"
            FrictionType.PINCH -> "${frictionState.pinchPercent}%"
            FrictionType.READ_PHRASE -> "${frictionState.phrases.size} 条"
            FrictionType.COUNTDOWN -> "${frictionState.countdownSeconds} 秒"
            FrictionType.MEMORY_GRID -> "${frictionState.memoryCount} 个点"
            FrictionType.KNOB_LOCK -> "${frictionState.knobCount} 个旋钮"
            FrictionType.KEY_PATH -> "${frictionState.turnCount} 个转弯"
            FrictionType.TARGET_SCAN -> "固定"
            FrictionType.BOLT_SEQUENCE -> "3 步随机"
            FrictionType.GUIDED_SWIPE -> "3 步随机"
            FrictionType.INTENT_CONFIRM -> "固定"
            FrictionType.REVERSE_MEMORY -> "${frictionState.memoryCount} 个点"
            FrictionType.PLATE_ASSEMBLY -> "3 片"
            else -> "未配置"
        }
        fun addFrictionOption(type: FrictionType, label: String) {
            val check = CheckBox(activity).apply {
                text = label
                textSize = 15f
                setTextColor(ink)
                isChecked = type in rule.frictionTypes
            }
            frictionChecks += check
            frictionTypeList += type
            val configButton = Button(activity)
            configButton.apply {
                text = "配置 ${frictionConfigSummary(type)}"
                textSize = 12f
                isAllCaps = false
                setTextColor(ink)
                this.background = UiTheme.shape(activity, UiTheme.accentSoft, 12, UiTheme.line)
                setOnClickListener {
                    showFrictionConfigDialog(activity, type, frictionState) { updated ->
                        frictionState = updated
                        this.text = "配置 ${frictionConfigSummary(type)}"
                    }
                }
            }
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(configButton, LinearLayout.LayoutParams(dp(activity, 104), dp(activity, 42)))
            }
            frictionRows += row
        }
        fun groupLabel(value: String) = text(activity, value.uppercase(), 12f, muted, true)
            .margins(activity, top = 10)

        frictionRows += groupLabel("简易")
        listOf(
            FrictionType.TRACE to "描摹轨迹 · 手眼配合",
            FrictionType.HOLD to "长按 · 安静明确",
            FrictionType.BREATHE to "呼吸 · 跟随节奏",
            FrictionType.TAP_SEQUENCE to "点按序列 · 依次点亮",
            FrictionType.DRAG_SLIDER to "拖滑块 · 滑到尽头",
            FrictionType.PINCH to "双指捏合 · 向内合拢",
            FrictionType.READ_PHRASE to "读一句话 · 读完再决定",
            FrictionType.COUNTDOWN to "倒计时 · 等一等"
        ).forEach { (t, l) -> addFrictionOption(t, l) }
        frictionRows += groupLabel("中等")
        listOf(
            FrictionType.MEMORY_GRID to "记忆点阵 · 记住顺序点亮",
            FrictionType.KNOB_LOCK to "旋钮锁 · 对齐旋钮",
            FrictionType.KEY_PATH to "推钥匙 · 穿过门缝",
            FrictionType.TARGET_SCAN to "目标扫描 · 按规则点击",
            FrictionType.BOLT_SEQUENCE to "三段式门栓 · 旋转+滑动+长按",
            FrictionType.GUIDED_SWIPE to "组合滑动 · 按提示依次滑动",
            FrictionType.INTENT_CONFIRM to "意图确认 · 想清楚再继续"
        ).forEach { (t, l) -> addFrictionOption(t, l) }
        frictionRows += groupLabel("困难")
        listOf(
            FrictionType.REVERSE_MEMORY to "反向记忆 · 倒序点亮",
            FrictionType.PLATE_ASSEMBLY to "拼合门板 · 对齐拼片"
        ).forEach { (t, l) -> addFrictionOption(t, l) }
        root.addView(section(
            activity, "如何推开", surface,
            *frictionRows.toTypedArray(),
            selectAllButton(activity, frictionChecks, onChange = { onFrictionChanged?.invoke() }),
            text(activity, "勾选多个 = 每扇门随机切换一种", 12f, muted),
            text(activity, "点击右侧「配置」调整每种方式的细节", 12f, muted)
        ).margins(activity, top = 12))

        val difficultyGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        listOf(
            RuleDifficulty.EASY to "仅简易 · 轻松稳定",
            RuleDifficulty.MEDIUM to "仅中等 · 需要一点注意",
            RuleDifficulty.HARD to "仅困难 · 高投入",
            RuleDifficulty.PROGRESSIVE to "递增 · 随时间从易到难",
            RuleDifficulty.CUSTOM to "自定义 · 手动勾选"
        ).forEachIndexed { index, (d, label) ->
            difficultyGroup.addView(RadioButton(activity).apply {
                id = index + 1
                text = label
                textSize = 15f
                setTextColor(ink)
                isChecked = rule.ruleDifficulty == d
            })
        }
        var syncingDifficulty = false
        fun selectFrictionsForDifficulty(d: RuleDifficulty) {
            frictionTypeList.forEachIndexed { index, type ->
                if (index < frictionChecks.size) {
                    frictionChecks[index].isChecked = when (d) {
                        RuleDifficulty.EASY -> type.difficulty == Difficulty.EASY
                        RuleDifficulty.MEDIUM -> type.difficulty == Difficulty.MEDIUM
                        RuleDifficulty.HARD -> type.difficulty == Difficulty.HARD
                        RuleDifficulty.PROGRESSIVE -> true
                        RuleDifficulty.CUSTOM -> frictionChecks[index].isChecked
                    }
                }
            }
        }
        fun syncDifficultyFromChecks() {
            val selected = frictionTypeList.filterIndexed { index, _ ->
                index < frictionChecks.size && frictionChecks[index].isChecked
            }.toSet()
            val easyAll = frictionTypeList.filter { it.difficulty == Difficulty.EASY }.toSet()
            val mediumAll = frictionTypeList.filter { it.difficulty == Difficulty.MEDIUM }.toSet()
            val hardAll = frictionTypeList.filter { it.difficulty == Difficulty.HARD }.toSet()
            val all = easyAll + mediumAll + hardAll
            val difficulty = when {
                selected.isEmpty() -> RuleDifficulty.CUSTOM
                selected == all -> RuleDifficulty.PROGRESSIVE
                selected == easyAll -> RuleDifficulty.EASY
                selected == mediumAll -> RuleDifficulty.MEDIUM
                selected == hardAll -> RuleDifficulty.HARD
                else -> RuleDifficulty.CUSTOM
            }
            syncingDifficulty = true
            difficultyGroup.check(difficulty.ordinal + 1)
            syncingDifficulty = false
        }
        difficultyGroup.setOnCheckedChangeListener { _, checkedId ->
            if (syncingDifficulty) return@setOnCheckedChangeListener
            selectFrictionsForDifficulty(
                RuleDifficulty.entries.getOrNull(checkedId - 1) ?: RuleDifficulty.EASY
            )
        }
        onFrictionChanged = { syncDifficultyFromChecks() }
        syncDifficultyFromChecks()
        root.addView(section(
            activity, "难度", surface, difficultyGroup,
            text(activity, "选择难度会自动勾选对应组的推门方式，可再手动增删", 12f, muted)
        ).margins(activity, top = 12))

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
                repeatPicker, dayChecks, startMinute, endMinute, frictionChecks, frictionTypeList, frictionState, difficultyGroup,
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
                            repeatPicker, dayChecks, startMinute, endMinute, frictionChecks, frictionTypeList, frictionState, difficultyGroup,
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
                    val frictions = frictionTypeList.filterIndexed { index, _ ->
                        index < frictionChecks.size && frictionChecks[index].isChecked
                    }.toSet()
                    if (frictions.isEmpty()) {
                        android.widget.Toast.makeText(activity, "至少选择一种摩擦方式", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (FrictionType.TRACE in frictions && frictionState.traceShapes.isEmpty()) {
                        android.widget.Toast.makeText(activity, "至少选择一种轨迹形状", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    onSave(buildRule(
                        rule, enabled, triggerOnOpen, delayEnabled, delayPicker, repeatEnabled,
                        repeatPicker, dayChecks, startMinute, endMinute, frictionChecks, frictionTypeList, frictionState, difficultyGroup,
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
        frictionChecks: List<CheckBox>,
        frictionTypeList: List<FrictionType>,
        frictionState: GateRule,
        difficultyGroup: RadioGroup,
        exitPackage: String?,
        exitLabel: String?,
        pausePicker: NumberPicker
    ): GateRule {
        val frictions = frictionTypeList.filterIndexed { index, _ ->
            index < frictionChecks.size && frictionChecks[index].isChecked
        }.toSet()
        val ruleDiff = RuleDifficulty.entries.getOrNull(difficultyGroup.checkedRadioButtonId - 1)
            ?: RuleDifficulty.EASY
        return rule.copy(
            enabled = enabled.isChecked,
            triggerOnOpen = triggerOnOpen.isChecked,
            initialDelayMinutes = delayPicker.value.takeIf { delayEnabled.isChecked },
            repeatIntervalMinutes = repeatPicker.value.takeIf { repeatEnabled.isChecked },
            activeDays = selectedDays(dayChecks),
            startMinuteOfDay = startMinute,
            endMinuteOfDay = endMinute,
            frictionTypes = frictions,
            traceShapes = frictionState.traceShapes,
            holdSeconds = frictionState.holdSeconds,
            breatheCycles = frictionState.breatheCycles,
            tapCount = frictionState.tapCount,
            dragPercent = frictionState.dragPercent,
            pinchPercent = frictionState.pinchPercent,
            countdownSeconds = frictionState.countdownSeconds,
            phrases = frictionState.phrases,
            memoryCount = frictionState.memoryCount,
            knobCount = frictionState.knobCount,
            turnCount = frictionState.turnCount,
            routeSize = frictionState.routeSize,
            ruleDifficulty = ruleDiff,
            exitPackage = exitPackage,
            exitLabel = exitLabel,
            emergencyPauseMinutes = pausePicker.value
        )
    }

    private fun showFrictionConfigDialog(
        activity: Activity,
        type: FrictionType,
        rule: GateRule,
        onUpdate: (GateRule) -> Unit
    ) {
        val (view, collect) = when (type) {
            FrictionType.TRACE -> traceConfig(activity, rule)
            FrictionType.HOLD -> choiceConfig(activity, rule, listOf(2, 3, 5),
                { "$it 秒" }, { it.holdSeconds }, { r, v -> r.copy(holdSeconds = v) }, 1..60)
            FrictionType.BREATHE -> choiceConfig(activity, rule, listOf(1, 2, 3),
                { "$it 次" }, { it.breatheCycles }, { r, v -> r.copy(breatheCycles = v) }, 1..10)
            FrictionType.TAP_SEQUENCE -> choiceConfig(activity, rule, listOf(3, 4, 5),
                { "$it 个" }, { it.tapCount }, { r, v -> r.copy(tapCount = v) }, 2..8)
            FrictionType.DRAG_SLIDER -> choiceConfig(activity, rule, listOf(80, 90, 100),
                { "$it%" }, { it.dragPercent }, { r, v -> r.copy(dragPercent = v) }, 50..100)
            FrictionType.PINCH -> choiceConfig(activity, rule, listOf(50, 60, 70),
                { "$it%" }, { it.pinchPercent }, { r, v -> r.copy(pinchPercent = v) }, 30..90)
            FrictionType.COUNTDOWN -> choiceConfig(activity, rule, listOf(3, 5, 10),
                { "$it 秒" }, { it.countdownSeconds }, { r, v -> r.copy(countdownSeconds = v) }, 1..60)
            FrictionType.READ_PHRASE -> phraseConfig(activity, rule)
            FrictionType.MEMORY_GRID -> choiceConfig(activity, rule, listOf(3, 4, 5),
                { "$it 个点" }, { it.memoryCount }, { r, v -> r.copy(memoryCount = v) }, 2..8)
            FrictionType.KNOB_LOCK -> choiceConfig(activity, rule, listOf(1, 2, 3, 6, 9),
                { "$it 个旋钮" }, { it.knobCount }, { r, v -> r.copy(knobCount = v) }, 1..9)
            FrictionType.KEY_PATH -> choiceConfig(activity, rule, listOf(0, 1, 2),
                { "$it 个转弯" }, { it.turnCount }, { r, v -> r.copy(turnCount = v) }, 0..3)
            FrictionType.TARGET_SCAN -> fixedConfig(
                activity, rule, "目标扫描为固定设计：按提示点出目标图形，点错不惩罚。")
            FrictionType.BOLT_SEQUENCE -> fixedConfig(
                activity, rule, "三段式门栓：旋转、滑动、长按三个动作随机顺序出现。")
            FrictionType.GUIDED_SWIPE -> fixedConfig(
                activity, rule, "组合滑动：按提示依次完成两个方向滑动与一次长按。")
            FrictionType.INTENT_CONFIRM -> fixedConfig(
                activity, rule, "意图确认：选择继续原因与时长，再完成一次确认。")
            FrictionType.REVERSE_MEMORY -> choiceConfig(activity, rule, listOf(4, 5, 6),
                { "$it 个点" }, { it.memoryCount }, { r, v -> r.copy(memoryCount = v) }, 3..8)
            FrictionType.PLATE_ASSEMBLY -> fixedConfig(
                activity, rule, "拼合门板：对齐三块拼片。")
            else -> fixedConfig(activity, rule, "此方式尚未开放配置。")
        }
        val scroll = ScrollView(activity).apply { addView(view) }
        AlertDialog.Builder(activity)
            .setTitle(frictionConfigTitle(type))
            .setView(scroll)
            .setPositiveButton("确定") { _, _ -> onUpdate(collect()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun frictionConfigTitle(type: FrictionType): String = when (type) {
        FrictionType.TRACE -> "描摹轨迹 · 选择随机出现的轨迹"
        FrictionType.HOLD -> "长按 · 按住秒数"
        FrictionType.BREATHE -> "呼吸 · 完成次数"
        FrictionType.TAP_SEQUENCE -> "点按序列 · 圆点数量"
        FrictionType.DRAG_SLIDER -> "拖滑块 · 完成进度"
        FrictionType.PINCH -> "双指捏合 · 目标距离"
        FrictionType.READ_PHRASE -> "读一句话 · 语库内容"
        FrictionType.COUNTDOWN -> "倒计时 · 秒数"
        FrictionType.MEMORY_GRID -> "记忆点阵 · 圆点数量"
        FrictionType.KNOB_LOCK -> "旋钮锁 · 旋钮数量"
        FrictionType.KEY_PATH -> "推钥匙 · 通道转弯数"
        FrictionType.TARGET_SCAN -> "目标扫描"
        FrictionType.BOLT_SEQUENCE -> "三段式门栓"
        FrictionType.GUIDED_SWIPE -> "组合滑动"
        FrictionType.INTENT_CONFIRM -> "意图确认"
        FrictionType.REVERSE_MEMORY -> "反向记忆 · 圆点数量"
        FrictionType.PLATE_ASSEMBLY -> "拼合门板"
        else -> "摩擦配置"
    }

    private fun traceConfig(activity: Activity, rule: GateRule): Pair<View, () -> GateRule> {
        val checks = mutableListOf<CheckBox>()
        TraceShape.entries.forEach { shape ->
            checks += CheckBox(activity).apply {
                text = shape.label
                textSize = 15f
                setTextColor(UiTheme.ink)
                isChecked = shape in rule.traceShapes
            }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 8))
        }
        box.addView(selectAllButton(activity, checks))
        checks.forEach { box.addView(it) }
        box.addView(text(activity, "勾选多个 = 每扇门随机切换一种", 12f, UiTheme.muted))
        return box to {
            val shapes = TraceShape.entries.filterIndexed { index, _ ->
                index < checks.size && checks[index].isChecked
            }.toSet()
            rule.copy(traceShapes = shapes.ifEmpty { setOf(TraceShape.S) })
        }
    }

    private fun choiceConfig(
        activity: Activity,
        rule: GateRule,
        values: List<Int>,
        optionLabel: (Int) -> String,
        current: (GateRule) -> Int,
        update: (GateRule, Int) -> GateRule,
        customRange: IntRange
    ): Pair<View, () -> GateRule> {
        val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        values.forEachIndexed { index, value ->
            group.addView(RadioButton(activity).apply {
                id = index + 1
                text = optionLabel(value)
                textSize = 15f
                setTextColor(UiTheme.ink)
                isChecked = current(rule) == value
            })
        }
        val currentValue = current(rule)
        val isCustom = currentValue !in values
        val customId = values.size + 1
        val customRadio = RadioButton(activity).apply {
            id = customId
            text = "自定义"
            textSize = 15f
            setTextColor(UiTheme.ink)
            isChecked = isCustom
        }
        group.addView(customRadio)
        val input = EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setTextColor(UiTheme.ink)
            setHintTextColor(UiTheme.muted)
            hint = "${customRange.first}–${customRange.last}"
            setText(if (isCustom) "$currentValue" else "")
            visibility = if (isCustom) android.view.View.VISIBLE else android.view.View.GONE
        }
        customRadio.setOnCheckedChangeListener { _, checked ->
            input.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 8))
        }
        box.addView(group)
        box.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 56)
        ).apply { topMargin = dp(activity, 10) })
        return box to {
            val id = group.checkedRadioButtonId
            val value = when {
                id == customId -> input.text.toString().toIntOrNull()?.coerceIn(customRange)
                id > 0 -> values.getOrNull(id - 1)
                else -> null
            }
            if (value != null) update(rule, value) else rule
        }
    }

    private fun phraseConfig(activity: Activity, rule: GateRule): Pair<View, () -> GateRule> {
        val input = EditText(activity).apply {
            setText(rule.phrases.joinToString("\n"))
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            textSize = 15f
            setTextColor(UiTheme.ink)
            setHintTextColor(UiTheme.muted)
            hint = "每行一条提醒语"
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 8))
        }
        box.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 190)))
        box.addView(text(activity, "每行一条，门出现时随机展示一条", 12f, UiTheme.muted).margins(activity, top = 6))
        return box to {
            val list = input.text.toString().lineSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }.toList()
            rule.copy(phrases = list.ifEmpty { GateFrictionConfig.DEFAULT_PHRASES })
        }
    }

    /** 全选/取消全选切换按钮,文案随勾选状态变化。 */
    private fun selectAllButton(
        activity: Activity,
        checks: List<CheckBox>,
        heightDp: Int = 42,
        onChange: () -> Unit = {}
    ): Button {
        val button = Button(activity)
        fun refresh() {
            val all = checks.isNotEmpty() && checks.all { it.isChecked }
            button.text = if (all) "取消全选" else "全选"
        }
        button.apply {
            textSize = 13f
            isAllCaps = false
            setTextColor(UiTheme.ink)
            this.background = UiTheme.shape(activity, UiTheme.accentSoft, 10, UiTheme.line)
            minHeight = dp(activity, heightDp)
            setOnClickListener {
                val all = checks.isNotEmpty() && checks.all { it.isChecked }
                checks.forEach { it.isChecked = !all }
                refresh()
            }
        }
        checks.forEach { it.setOnCheckedChangeListener { _, _ -> refresh(); onChange() } }
        refresh()
        return button
    }

    private fun fixedConfig(activity: Activity, rule: GateRule, message: String): Pair<View, () -> GateRule> {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 8))
        }
        box.addView(text(activity, message, 14f, UiTheme.muted))
        return box to { rule }
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
