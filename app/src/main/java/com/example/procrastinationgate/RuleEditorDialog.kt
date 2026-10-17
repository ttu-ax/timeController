package com.example.procrastinationgate

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 规则编辑器。
 *
 * 从"塞满十个区块的模态弹窗"改成全屏编辑器:
 * - 标题栏与保存按钮固定在两端,只有中间内容滚动
 * - 触发方式默认展开;生效时间、出口与紧急绕过收起为一行摘要,点一下展开
 * - 「如何推开」只列出已选中的方式(通常 1~3 行),完整清单放在独立的选择页
 */
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
        val ink = UiTheme.ink
        val surface = UiTheme.surface
        val muted = UiTheme.muted
        val accent = UiTheme.accent
        val canvasColor = UiTheme.canvas

        // ---------------- 表单状态 ----------------
        var enabled = rule.enabled
        var useDelay = !rule.triggerOnOpen
        var delayMinutes = (rule.initialDelayMinutes ?: 5).coerceIn(1, 120)
        var repeatEnabled = rule.repeatIntervalMinutes != null
        var repeatMinutes = (rule.repeatIntervalMinutes ?: 10).coerceIn(1, 120)
        val days = rule.activeDays.filter { it in 1..7 }.toMutableSet().ifEmpty { (1..7).toMutableSet() }
        var allDay = rule.startMinuteOfDay == 0 && rule.endMinuteOfDay == 24 * 60
        var startMinute = rule.startMinuteOfDay
        var endMinute = rule.endMinuteOfDay
        val frictions = LinkedHashSet(rule.frictionTypes.ifEmpty { setOf(FrictionType.TRACE) })
        var frictionState = rule
        var difficulty = rule.ruleDifficulty
        var exitPackage = rule.exitPackage
        var exitLabel = rule.exitLabel
        var pauseMinutes = rule.emergencyPauseMinutes.coerceIn(5, 120)

        fun collect(): GateRule = rule.copy(
            enabled = enabled,
            triggerOnOpen = !useDelay,
            initialDelayMinutes = if (useDelay) delayMinutes else null,
            repeatIntervalMinutes = if (repeatEnabled) repeatMinutes else null,
            activeDays = days.toSortedSet().toSet(),
            startMinuteOfDay = if (allDay) 0 else startMinute,
            endMinuteOfDay = if (allDay) 24 * 60 else endMinute,
            frictionTypes = frictions.toSet(),
            traceShapes = frictionState.traceShapes,
            holdSeconds = frictionState.holdSeconds,
            breatheCycles = frictionState.breatheCycles,
            tapCount = frictionState.tapCount,
            dragPercent = frictionState.dragPercent,
            squeezePercent = frictionState.squeezePercent,
            countdownSeconds = frictionState.countdownSeconds,
            phrases = frictionState.phrases,
            memoryCount = frictionState.memoryCount,
            knobCount = frictionState.knobCount,
            turnCount = frictionState.turnCount,
            routeSize = frictionState.routeSize,
            // 这两项之前漏了,导致心算 / 计数的参数保存不上
            mathLevel = frictionState.mathLevel,
            countTarget = frictionState.countTarget,
            ruleDifficulty = difficulty,
            exitPackage = exitPackage,
            exitLabel = exitLabel,
            emergencyPauseMinutes = pauseMinutes
        )

        // ---------------- 顶部标题栏 ----------------
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 16), dp(activity, 4))
        }
        val backButton = Button(activity).apply {
            text = "返回"
            textSize = 15f
            isAllCaps = false
            setTextColor(muted)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
        }
        topBar.addView(backButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(activity, 48)))
        topBar.addView(text(activity, "设置规则", 18f, ink, true), LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(text(activity, rule.targetLabel, 14f, muted))

        // ---------------- 内容区 ----------------
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 24))
        }

        val enabledSwitch = Switch(activity).apply {
            text = "启用这条规则"
            textSize = 16f
            setTextColor(ink)
            isChecked = enabled
        }
        content.addView(card(activity, surface, enabledSwitch))

        // ---------------- 如何推开 ----------------
        val frictionHost = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val difficultyView = text(activity, "", 12f, muted)
        val addFrictionButton = ghostButton(activity, "＋ 添加推开方式")
        val frictionCard = section(activity, "如何推开", surface).apply {
            addView(frictionHost)
            addView(addFrictionButton.margins(activity, top = 10))
            addView(difficultyView.margins(activity, top = 8))
        }
        content.addView(frictionCard.margins(activity, top = 12))

        // ---------------- 什么时候出现 ----------------
        val triggerGroup = CollapsibleGroup(activity, "什么时候出现", expanded = true)
        val triggerRadios = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val openRadio = RadioButton(activity).apply {
            id = 1
            text = "每次打开时出现"
            textSize = 16f
            setTextColor(ink)
            isChecked = !useDelay
        }
        val delayRadio = RadioButton(activity).apply {
            id = 2
            text = "连续使用后出现"
            textSize = 16f
            setTextColor(ink)
            isChecked = useDelay
        }
        triggerRadios.addView(openRadio)
        triggerRadios.addView(delayRadio)
        val delayStepper = Stepper(activity, delayMinutes, 1..120, "分钟后出现")
        val repeatSwitch = CheckBox(activity).apply {
            text = "推开后再次提醒"
            textSize = 16f
            setTextColor(ink)
            isChecked = repeatEnabled
        }
        val repeatStepper = Stepper(activity, repeatMinutes, 1..120, "分钟后")
        triggerGroup.body.addView(triggerRadios)
        triggerGroup.body.addView(delayStepper.view.margins(activity, top = 2))
        triggerGroup.body.addView(repeatSwitch.margins(activity, top = 12))
        triggerGroup.body.addView(repeatStepper.view.margins(activity, top = 2))
        content.addView(triggerGroup.view.margins(activity, top = 12))

        // ---------------- 生效时间 ----------------
        val timeGroup = CollapsibleGroup(activity, "生效时间", expanded = false)
        val dayChecks = mutableListOf<CheckBox>()
        val daysRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, name ->
            val day = index + 1
            val chip = CheckBox(activity).apply {
                text = name
                gravity = Gravity.CENTER
                buttonTintList = android.content.res.ColorStateList.valueOf(ink)
                isChecked = day in days
            }
            dayChecks += chip
            daysRow.addView(chip, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
        }
        val allDayCheck = CheckBox(activity).apply {
            text = "全天生效"
            textSize = 15f
            setTextColor(ink)
            isChecked = allDay
        }
        val timeHint = text(activity, "", 13f, muted)
        val startButton = ghostButton(activity, timeLabel(startMinute))
        val endButton = ghostButton(activity, timeLabel(endMinute))
        timeGroup.body.addView(daysRow)
        timeGroup.body.addView(allDayCheck.margins(activity, top = 8))
        timeGroup.body.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(startButton, LinearLayout.LayoutParams(0, dp(activity, 46), 1f))
            addView(text(activity, " 至 ", 14f, muted))
            addView(endButton, LinearLayout.LayoutParams(0, dp(activity, 46), 1f))
        }.margins(activity, top = 6))
        timeGroup.body.addView(timeHint.margins(activity, top = 6))
        content.addView(timeGroup.view.margins(activity, top = 12))

        // ---------------- 出口与紧急绕过 ----------------
        val exitGroup = CollapsibleGroup(activity, "出口与紧急绕过", expanded = false)
        val exitValue = text(activity, "", 16f, ink, true)
        val pickExitButton = ghostButton(activity, "选择这条规则的出口")
        val resetExitButton = ghostButton(activity, "改用默认出口")
        val pauseStepper = Stepper(activity, pauseMinutes, 5..120, "分钟")
        exitGroup.body.addView(exitValue)
        exitGroup.body.addView(pickExitButton.margins(activity, top = 8))
        exitGroup.body.addView(resetExitButton.margins(activity, top = 6))
        exitGroup.body.addView(text(activity, "紧急绕过 · 长按后暂停", 14f, ink).margins(activity, top = 14))
        exitGroup.body.addView(pauseStepper.view.margins(activity, top = 4))
        content.addView(exitGroup.view.margins(activity, top = 12))

        // ---------------- 底部动作 ----------------
        val testButton = ghostButton(activity, "测试这扇门")
        content.addView(testButton.margins(activity, top = 16))

        val secondaryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val duplicateButton = ghostButton(activity, "复制规则")
        val deleteButton = outlineButton(activity, "删除规则", Color.rgb(242, 155, 100))
        if (onDuplicate != null) {
            secondaryRow.addView(duplicateButton, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
        }
        if (onDelete != null) {
            secondaryRow.addView(deleteButton, LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply {
                if (onDuplicate != null) leftMargin = dp(activity, 8)
            })
        }
        if (onDuplicate != null || onDelete != null) {
            content.addView(secondaryRow.margins(activity, top = 10))
        }

        // ---------------- 底部保存栏 ----------------
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
        }
        val saveButton = Button(activity).apply {
            text = "保存规则"
            textSize = 16f
            isAllCaps = false
            setTextColor(ink)
            background = UiTheme.shape(activity, accent, 14)
        }
        bottomBar.addView(saveButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 52)))

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
            addView(topBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(activity).apply {
                setBackgroundColor(canvasColor)
                addView(content)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(canvasColor))
        }
        dialog.setCanceledOnTouchOutside(false)

        // ---------------- 刷新函数(必须在视图之后、监听器之前定义) ----------------
        fun refreshFrictionRows() {
            frictionHost.removeAllViews()
            if (frictions.isEmpty()) {
                frictionHost.addView(text(activity, "还没有选择推开方式", 14f, muted))
            } else {
                frictions.sortedBy { it.ordinal }.forEach { type ->
                    frictionHost.addView(detailRow(
                        activity = activity,
                        title = type.title,
                        subtitle = frictionSummary(type, frictionState)
                    ) {
                        showFrictionConfigDialog(activity, type, frictionState) { updated ->
                            frictionState = updated
                            refreshFrictionRows()
                        }
                    })
                }
            }
            difficultyView.text = "难度 · ${difficulty.label}"
        }

        fun refreshTriggerSummary() {
            triggerGroup.setSummary(
                if (useDelay) "连续使用 $delayMinutes 分钟后" else "每次打开时"
            )
            delayStepper.view.visibility = if (useDelay) View.VISIBLE else View.GONE
            repeatStepper.view.visibility = if (repeatEnabled) View.VISIBLE else View.GONE
        }

        fun refreshTimeSummary() {
            val dayText = when (days.size) {
                7 -> "每天"
                else -> "每周 ${days.sorted().joinToString("") { dayNames[it - 1] }}"
            }
            val range = if (allDay) "全天" else "${timeLabel(startMinute)}–${timeLabel(endMinute)}"
            timeGroup.setSummary("$dayText · $range")
            startButton.text = timeLabel(startMinute)
            endButton.text = timeLabel(endMinute)
            startButton.isEnabled = !allDay
            endButton.isEnabled = !allDay
            startButton.alpha = if (allDay) 0.4f else 1f
            endButton.alpha = if (allDay) 0.4f else 1f
            updateTimeHint(timeHint, if (allDay) 0 else startMinute, if (allDay) 24 * 60 else endMinute)
        }

        fun refreshExitSummary() {
            exitValue.text = exitLabel ?: "使用默认出口"
            exitGroup.setSummary("${exitLabel ?: "默认出口"} · 绕过 $pauseMinutes 分钟")
        }

        // ---------------- 监听器 ----------------
        enabledSwitch.setOnCheckedChangeListener { _, checked -> enabled = checked }
        addFrictionButton.setOnClickListener {
            showFrictionPicker(activity, frictions, difficulty) { picked, newDifficulty ->
                frictions.clear()
                frictions.addAll(picked)
                difficulty = newDifficulty
                refreshFrictionRows()
            }
        }
        triggerRadios.setOnCheckedChangeListener { _, id ->
            useDelay = id == delayRadio.id
            refreshTriggerSummary()
        }
        delayStepper.onChanged = { delayMinutes = it; refreshTriggerSummary() }
        repeatSwitch.setOnCheckedChangeListener { _, checked ->
            repeatEnabled = checked
            refreshTriggerSummary()
        }
        repeatStepper.onChanged = { repeatMinutes = it; refreshTriggerSummary() }

        dayChecks.forEachIndexed { index, chip ->
            val day = index + 1
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) days.add(day) else days.remove(day)
                refreshTimeSummary()
            }
        }
        allDayCheck.setOnCheckedChangeListener { _, checked ->
            allDay = checked
            if (checked) {
                startMinute = 0
                endMinute = 24 * 60
            }
            refreshTimeSummary()
        }
        startButton.setOnClickListener {
            selectTime(activity, startMinute) {
                startMinute = it
                allDay = false
                allDayCheck.isChecked = false
                refreshTimeSummary()
            }
        }
        endButton.setOnClickListener {
            selectTime(activity, if (endMinute == 24 * 60) 0 else endMinute) {
                endMinute = if (it == 0) 24 * 60 else it
                allDay = false
                allDayCheck.isChecked = false
                refreshTimeSummary()
            }
        }
        pickExitButton.setOnClickListener {
            AppPickerDialog.show(activity, "选择现实出口", installedApps()) {
                exitPackage = it.packageName
                exitLabel = it.label
                refreshExitSummary()
            }
        }
        resetExitButton.setOnClickListener {
            exitPackage = null
            exitLabel = null
            refreshExitSummary()
        }
        pauseStepper.onChanged = { pauseMinutes = it; refreshExitSummary() }

        testButton.setOnClickListener { onTest(collect()) }
        duplicateButton.setOnClickListener {
            onDuplicate?.invoke(collect())
            dialog.dismiss()
        }
        deleteButton.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("删除 ${rule.targetLabel} 的规则？")
                .setMessage("已有记录不会被删除。")
                .setNegativeButton("保留", null)
                .setPositiveButton("删除") { _, _ ->
                    onDelete?.invoke()
                    dialog.dismiss()
                }
                .show()
        }

        backButton.setOnClickListener { confirmDiscard(activity) { dialog.dismiss() } }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                confirmDiscard(activity) { dialog.dismiss() }
                true
            } else {
                false
            }
        }
        saveButton.setOnClickListener {
            if (days.isEmpty()) {
                toast(activity, "至少选择一天")
                return@setOnClickListener
            }
            if (frictions.isEmpty()) {
                toast(activity, "至少选择一种推开方式")
                return@setOnClickListener
            }
            if (FrictionType.TRACE in frictions && frictionState.traceShapes.isEmpty()) {
                toast(activity, "至少选择一种轨迹形状")
                return@setOnClickListener
            }
            onSave(collect())
            dialog.dismiss()
        }

        refreshFrictionRows()
        refreshTriggerSummary()
        refreshTimeSummary()
        refreshExitSummary()
        dialog.show()
    }

    private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    // ------------------------------------------------------------------
    // 推开方式选择页:24 种只在这里出现,主表单不再全量铺开
    // ------------------------------------------------------------------
    private fun showFrictionPicker(
        activity: Activity,
        initialSelected: Set<FrictionType>,
        initialDifficulty: RuleDifficulty,
        onApply: (Set<FrictionType>, RuleDifficulty) -> Unit
    ) {
        val ink = UiTheme.ink
        val surface = UiTheme.surface
        val muted = UiTheme.muted
        val accent = UiTheme.accent
        val canvasColor = UiTheme.canvas

        val checks = LinkedHashMap<FrictionType, CheckBox>()
        var syncing = false
        var difficulty = initialDifficulty

        val title = text(activity, "选择推开方式", 18f, ink, true)
        val countView = text(activity, "", 13f, muted)
        val difficultyGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }

        fun selectedNow(): Set<FrictionType> = checks.filterValues { it.isChecked }.keys.toSet()

        fun updateCount() {
            countView.text = "已选 ${selectedNow().size} 种 · ${difficulty.label}"
        }

        fun syncDifficultyFromChecks() {
            val selected = selectedNow()
            val easy = FrictionType.entries.filter { it.difficulty == Difficulty.EASY }.toSet()
            val medium = FrictionType.entries.filter { it.difficulty == Difficulty.MEDIUM }.toSet()
            val hard = FrictionType.entries.filter { it.difficulty == Difficulty.HARD }.toSet()
            difficulty = when {
                selected.isEmpty() -> RuleDifficulty.CUSTOM
                selected == easy + medium + hard -> RuleDifficulty.PROGRESSIVE
                selected == easy -> RuleDifficulty.EASY
                selected == medium -> RuleDifficulty.MEDIUM
                selected == hard -> RuleDifficulty.HARD
                else -> RuleDifficulty.CUSTOM
            }
            syncing = true
            difficultyGroup.check(difficulty.ordinal + 1)
            syncing = false
            updateCount()
        }

        fun applyDifficulty(target: RuleDifficulty) {
            syncing = true
            checks.forEach { (type, check) ->
                check.isChecked = when (target) {
                    RuleDifficulty.EASY -> type.difficulty == Difficulty.EASY
                    RuleDifficulty.MEDIUM -> type.difficulty == Difficulty.MEDIUM
                    RuleDifficulty.HARD -> type.difficulty == Difficulty.HARD
                    RuleDifficulty.PROGRESSIVE -> true
                    RuleDifficulty.CUSTOM -> check.isChecked
                }
            }
            syncing = false
            difficulty = target
            updateCount()
        }

        RuleDifficulty.entries.forEach { entry ->
            difficultyGroup.addView(RadioButton(activity).apply {
                id = entry.ordinal + 1
                text = entry.label
                textSize = 15f
                setTextColor(ink)
                isChecked = entry == initialDifficulty
            })
        }
        difficultyGroup.setOnCheckedChangeListener { _, checkedId ->
            if (syncing) return@setOnCheckedChangeListener
            applyDifficulty(RuleDifficulty.entries.getOrNull(checkedId - 1) ?: RuleDifficulty.CUSTOM)
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 24))
            addView(countView.margins(activity, bottom = 8))
            addView(section(activity, "按难度快速选择", surface, difficultyGroup))
            addView(text(activity, "点难度会自动勾选对应组;也可以自己逐项挑", 12f, muted)
                .margins(activity, top = 6))
        }

        listOf(
            Difficulty.EASY to "简易",
            Difficulty.MEDIUM to "中等",
            Difficulty.HARD to "困难"
        ).forEach { (tier, tierLabel) ->
            val group = section(activity, tierLabel, surface)
            FrictionType.entries.filter { it.difficulty == tier }.forEach { type ->
                val check = CheckBox(activity).apply {
                    text = type.label
                    textSize = 15f
                    setTextColor(ink)
                    isChecked = type in initialSelected
                }
                checks[type] = check
                group.addView(check)
            }
            content.addView(group.margins(activity, top = 12))
        }

        // 统一挂监听,避免构造期反复触发
        checks.forEach { (_, check) ->
            check.setOnCheckedChangeListener { _, _ ->
                if (syncing) return@setOnCheckedChangeListener
                syncDifficultyFromChecks()
            }
        }
        updateCount()

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 16), dp(activity, 4))
        }
        val cancelButton = Button(activity).apply {
            text = "取消"
            textSize = 15f
            isAllCaps = false
            setTextColor(muted)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
        }
        topBar.addView(cancelButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(activity, 48)))
        topBar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val bottomBar = LinearLayout(activity).apply {
            setBackgroundColor(surface)
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
        }
        val doneButton = Button(activity).apply {
            text = "完成"
            textSize = 16f
            isAllCaps = false
            setTextColor(ink)
            background = UiTheme.shape(activity, accent, 14)
        }
        bottomBar.addView(doneButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 52)))

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvasColor)
            addView(topBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(activity).apply {
                setBackgroundColor(canvasColor)
                addView(content)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottomBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar)
        dialog.setContentView(container)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(canvasColor))
        }
        dialog.setCanceledOnTouchOutside(false)

        cancelButton.setOnClickListener { dialog.dismiss() }
        doneButton.setOnClickListener {
            onApply(selectedNow(), difficulty)
            dialog.dismiss()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------
    // 单种推开方式的参数配置(保持原有行为,仍用小弹窗)
    // ------------------------------------------------------------------
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
            FrictionType.SQUEEZE -> choiceConfig(activity, rule, listOf(50, 60, 70),
                { "$it%" }, { it.squeezePercent }, { r, v -> r.copy(squeezePercent = v) }, 30..90)
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
            FrictionType.INTENT_CONFIRM -> fixedConfig(
                activity, rule, "意图确认：选择继续原因与时长，再完成一次确认。")
            FrictionType.REVERSE_MEMORY -> choiceConfig(activity, rule, listOf(4, 5, 6),
                { "$it 个点" }, { it.memoryCount }, { r, v -> r.copy(memoryCount = v) }, 3..8)
            FrictionType.PLATE_ASSEMBLY -> fixedConfig(
                activity, rule, "拼合门板：把圆、三角、方块三种拼片拖到对应形状的槽位。")
            FrictionType.MULTI_STAGE -> fixedConfig(
                activity, rule, "多阶段门锁：每次从记忆/扫描/旋钮/钥匙/滑动/长按/拼合中随机组合 2-3 个阶段。")
            FrictionType.REPEAT_SCAN -> fixedConfig(
                activity, rule, "连续扫描：依次完成空心圆、三角形、最大图形的点击，每阶段换一条规则。")
            FrictionType.MEMORY_KNOB -> choiceConfig(activity, rule, listOf(4, 5, 6),
                { "$it 个符号" }, { it.memoryCount }, { r, v -> r.copy(memoryCount = v) }, 3..8)
            FrictionType.REFLECT_WAIT -> fixedConfig(
                activity, rule, "反思式等待：选择是否继续→三次缓慢呼吸→再决定。")
            FrictionType.ROUTE_PLAN -> choiceConfig(activity, rule, listOf(3, 4, 5),
                { "${it}×${it} 网格" }, { it.routeSize }, { r, v -> r.copy(routeSize = v) }, 3..5)
            FrictionType.FLIP -> fixedConfig(
                activity, rule, "翻转手机：把屏幕朝下保持一秒，再翻回来。不需要重力传感器以外的权限。")
            FrictionType.MENTAL_MATH -> choiceConfig(activity, rule, listOf(1, 2, 3),
                { level ->
                    when (level) {
                        1 -> "一位数加减"
                        2 -> "一位数乘法"
                        else -> "两位数乘一位数"
                    }
                }, { it.mathLevel }, { r, v -> r.copy(mathLevel = v) }, 1..3)
            FrictionType.COUNT -> choiceConfig(activity, rule, listOf(3, 5, 7),
                { "上限 $it 个" }, { it.countTarget }, { r, v -> r.copy(countTarget = v) }, 2..8)
            // 故意不写 else:新增 FrictionType 时必须在这里补配置分支
        }
        AlertDialog.Builder(activity)
            .setTitle(type.configTitle)
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton("确定") { _, _ -> onUpdate(collect()) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 每种方式的参数摘要,显示在主表单的已选行上。 */
    private fun frictionSummary(type: FrictionType, rule: GateRule): String = when (type) {
        FrictionType.TRACE -> "${rule.traceShapes.size} 种轨迹"
        FrictionType.HOLD -> "${rule.holdSeconds} 秒"
        FrictionType.BREATHE -> "${rule.breatheCycles} 次"
        FrictionType.TAP_SEQUENCE -> "${rule.tapCount} 个"
        FrictionType.DRAG_SLIDER -> "${rule.dragPercent}%"
        FrictionType.SQUEEZE -> "${rule.squeezePercent}%"
        FrictionType.FLIP -> "固定"
        FrictionType.READ_PHRASE -> "${rule.phrases.size} 条"
        FrictionType.COUNTDOWN -> "${rule.countdownSeconds} 秒"
        FrictionType.MEMORY_GRID -> "${rule.memoryCount} 个点"
        FrictionType.MENTAL_MATH -> "${rule.mathLevel} 档"
        FrictionType.COUNT -> "${rule.countTarget} 个以内"
        FrictionType.KNOB_LOCK -> "${rule.knobCount} 个旋钮"
        FrictionType.KEY_PATH -> "${rule.turnCount} 个转弯"
        FrictionType.TARGET_SCAN -> "固定"
        FrictionType.BOLT_SEQUENCE -> "3 步随机"
        FrictionType.INTENT_CONFIRM -> "固定"
        FrictionType.REVERSE_MEMORY -> "${rule.memoryCount} 个点"
        FrictionType.PLATE_ASSEMBLY -> "3 片"
        FrictionType.MULTI_STAGE -> "2–3 个阶段"
        FrictionType.REPEAT_SCAN -> "3 条规则"
        FrictionType.MEMORY_KNOB -> "${rule.memoryCount} 个符号"
        FrictionType.REFLECT_WAIT -> "固定"
        FrictionType.ROUTE_PLAN -> "${rule.routeSize}×${rule.routeSize}"
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
            visibility = if (isCustom) View.VISIBLE else View.GONE
        }
        customRadio.setOnCheckedChangeListener { _, checked ->
            input.visibility = if (checked) View.VISIBLE else View.GONE
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
        box.addView(text(activity, "每行一条，门出现时随机展示一条", 12f, UiTheme.muted)
            .margins(activity, top = 6))
        return box to {
            val list = input.text.toString().lineSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }.toList()
            rule.copy(phrases = list.ifEmpty { GateFrictionConfig.DEFAULT_PHRASES })
        }
    }

    private fun fixedConfig(activity: Activity, rule: GateRule, message: String): Pair<View, () -> GateRule> {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 8))
        }
        box.addView(text(activity, message, 14f, UiTheme.muted))
        return box to { rule }
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

    private fun confirmDiscard(activity: Activity, discard: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("放弃未保存的修改？")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃修改") { _, _ -> discard() }
            .show()
    }

    private fun updateTimeHint(view: TextView, start: Int, end: Int) {
        view.text = when {
            start == 0 && end == 24 * 60 -> "全天生效"
            start > end -> "跨午夜生效，到第二天 ${timeLabel(end)} 结束"
            else -> "当天 ${timeLabel(start)}–${timeLabel(end)} 生效"
        }
    }

    private fun selectTime(activity: Activity, minute: Int, onSelected: (Int) -> Unit) {
        TimePickerDialog(activity, { _, hour, min -> onSelected(hour * 60 + min) },
            minute / 60, minute % 60, true).show()
    }

    private fun timeLabel(minute: Int): String {
        if (minute == 24 * 60) return "24:00"
        return "%02d:%02d".format(minute / 60, minute % 60)
    }
}

// ---------------------------------------------------------------------------
// 文件级小工具
// ---------------------------------------------------------------------------

private fun dp(activity: Activity, value: Int) =
    (value * activity.resources.displayMetrics.density).toInt()

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
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

private fun card(activity: Activity, color: Int, vararg children: View) =
    LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 15), dp(activity, 14), dp(activity, 15), dp(activity, 14))
        background = UiTheme.shape(activity, color, 18, UiTheme.line)
        children.forEachIndexed { index, child ->
            addView(child.margins(activity, top = if (index == 0) 0 else 8))
        }
    }

private fun section(activity: Activity, title: String, color: Int, vararg children: View) =
    card(activity, color, text(activity, title, 12f, UiTheme.muted, true), *children)

private fun outlineButton(
    activity: Activity,
    value: String,
    color: Int,
    action: () -> Unit = {}
) = Button(activity).apply {
    text = value
    textSize = 15f
    isAllCaps = false
    setTextColor(Color.WHITE)
    background = UiTheme.shape(activity, color, 12)
    setOnClickListener { action() }
}

/** 轻量按钮:白底 + 描边。用来替代"每个动作都是实心黑块"的做法。 */
private fun ghostButton(
    activity: Activity,
    value: String,
    contentColor: Int = UiTheme.ink,
    action: () -> Unit = {}
) = Button(activity).apply {
    text = value
    textSize = 15f
    isAllCaps = false
    setTextColor(contentColor)
    background = UiTheme.shape(activity, UiTheme.surface, 12, UiTheme.line)
    setOnClickListener { action() }
}

/** 一行可点条目:标题 + 说明 + 右侧箭头。 */
private fun detailRow(
    activity: Activity,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) = LinearLayout(activity).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, dp(activity, 10), 0, dp(activity, 10))
    isClickable = true
    setOnClickListener { onClick() }
    addView(LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(activity, title, 16f, UiTheme.ink, true))
        addView(text(activity, subtitle, 12f, UiTheme.muted))
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    addView(ChevronView(activity), LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18)))
}

private fun toast(activity: Activity, message: String) {
    android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
}

private fun <T : View> T.margins(activity: Activity, top: Int = 0, bottom: Int = 0): T {
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(activity, top), 0, dp(activity, bottom)) }
    return this
}

/** 折线箭头,展开时旋转成向下。 */
private class ChevronView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var expanded: Boolean = false
        set(value) {
            field = value
            animate().rotation(if (value) 90f else 0f).setDuration(150).start()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val dx = dp(3).toFloat()
        val dy = dp(5).toFloat()
        canvas.drawLine(cx - dx / 2f, cy - dy, cx + dx / 2f, cy, paint)
        canvas.drawLine(cx + dx / 2f, cy, cx - dx / 2f, cy + dy, paint)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** 可折叠分组:标题 + 右侧摘要 + 箭头,点标题展开/收起内容。 */
private class CollapsibleGroup(activity: Activity, title: String, expanded: Boolean) {
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = if (expanded) View.VISIBLE else View.GONE
    }
    private val summaryView = text(activity, "", 12f, UiTheme.muted)
    private val chevron = ChevronView(activity)
    val view: LinearLayout

    var expandedState = expanded
        private set

    init {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(0, dp(activity, 6), 0, dp(activity, 8))
        }
        header.addView(text(activity, title, 12f, UiTheme.muted, true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        header.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(summaryView)
            addView(chevron, LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18)).apply {
                leftMargin = dp(activity, 6)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        chevron.expanded = expanded
        header.setOnClickListener {
            expandedState = !expandedState
            body.visibility = if (expandedState) View.VISIBLE else View.GONE
            chevron.expanded = expandedState
        }

        view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 15), dp(activity, 12), dp(activity, 15), dp(activity, 14))
            background = UiTheme.shape(activity, UiTheme.surface, 18, UiTheme.line)
            addView(header)
            addView(body)
        }
    }

    fun setSummary(value: String) {
        summaryView.text = value
    }
}

/** 「－ 数值 单位 ＋」步进器,替代原来的裸 NumberPicker。 */
private class Stepper(
    activity: Activity,
    initial: Int,
    private val range: IntRange,
    private val suffix: String
) {
    var value: Int = initial.coerceIn(range.first, range.last)
        private set
    var onChanged: (Int) -> Unit = {}

    private val valueView = text(activity, "", 16f, UiTheme.ink, true)
    val view: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(stepButton(activity, "-") { change(-1) },
            LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
        addView(valueView, LinearLayout.LayoutParams(dp(activity, 132), dp(activity, 44)))
        addView(stepButton(activity, "+") { change(1) },
            LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
    }

    init {
        render()
    }

    private fun change(delta: Int) {
        val next = (value + delta).coerceIn(range.first, range.last)
        if (next == value) return
        value = next
        render()
        onChanged(value)
    }

    private fun render() {
        valueView.text = "$value $suffix"
    }
}

private fun stepButton(activity: Activity, label: String, action: () -> Unit) = Button(activity).apply {
    text = label
    textSize = 18f
    isAllCaps = false
    setTextColor(UiTheme.ink)
    background = UiTheme.shape(activity, UiTheme.accentSoft, 12, UiTheme.line)
    minWidth = 0
    minimumWidth = 0
    setOnClickListener { action() }
}
