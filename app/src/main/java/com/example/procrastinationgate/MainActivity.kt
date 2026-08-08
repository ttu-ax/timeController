package com.example.procrastinationgate

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var preferences: GatePreferences
    private lateinit var repository: GateRepository
    private lateinit var content: FrameLayout
    private var currentTab = Tab.HOME
    private val navigationButtons = mutableMapOf<Tab, Button>()

    private val ink = UiTheme.ink
    private val background = UiTheme.canvas
    private val surface = UiTheme.surface
    private val accent = UiTheme.accent
    private val muted = UiTheme.muted
    private val warning = UiTheme.warning

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = GatePreferences(this)
        repository = GateRepository(this)
        setContentView(buildShell())
        showTab(Tab.HOME)
        if (!preferences.onboardingCompleted && repository.rules().isEmpty()) {
            content.post { showOnboarding() }
        }
    }

    override fun onResume() {
        super.onResume()
        restoreMonitoringServiceIfNeeded()
        showTab(currentTab)
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@MainActivity.background)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(this@MainActivity.surface)
            elevation = 0f
        }
        Tab.entries.forEach { tab ->
            val button = Button(this).apply {
                text = "${tab.symbol}\n${tab.label}"
                textSize = 11f
                isAllCaps = false
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, dp(3))
                minHeight = 0
                minimumHeight = 0
                setOnClickListener { showTab(tab) }
            }
            navigationButtons[tab] = button
            navigation.addView(button, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(navigation)
        return root
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        navigationButtons.forEach { (item, button) ->
            val selected = item == tab
            button.setTextColor(if (selected) ink else muted)
            button.typeface = Typeface.create("sans-serif", if (selected) Typeface.BOLD else Typeface.NORMAL)
            button.background = if (selected) {
                UiTheme.shape(this, UiTheme.accentSoft, 12)
            } else {
                UiTheme.shape(this, Color.TRANSPARENT, 12)
            }
        }
        content.removeAllViews()
        content.addView(when (tab) {
            Tab.HOME -> homePage()
            Tab.TARGETS -> targetsPage()
            Tab.EXITS -> exitsPage()
            Tab.HISTORY -> historyPage()
            Tab.SETTINGS -> settingsPage()
        })
    }

    private fun homePage(): View = page("拖延之门", "让每次继续都经过一次选择") {
        val enabledRules = repository.enabledRules()
        val events = repository.todayEvents()
        val shown = events.count { it.result == GateResult.SHOWN }
        val left = events.count { it.result == GateResult.LEAVE }
        val now = System.currentTimeMillis()
        val pauseActive = preferences.bypassUntil > now

        addView(thresholdPanel(shown, left))
        if (pauseActive) {
            val remainingMinutes = ((preferences.bypassUntil - now + 59_999) / 60_000).toInt()
            addView(notice("守门已暂停 · 约剩 $remainingMinutes 分钟", accent, "取消暂停") {
                preferences.bypassUntil = 0L
                toast("已恢复守门")
                showTab(Tab.HOME)
            })
        }

        val healthy = hasUsageAccess() && Settings.canDrawOverlays(this@MainActivity)
        if (!healthy) {
            addView(notice("权限已关闭，守门暂时停止", warning) {
                showTab(Tab.SETTINGS)
            })
        }

        addView(sectionLabel("生效中的门"))
        if (enabledRules.isEmpty()) {
            addView(emptyCard("还没有目标", "选择一个经常无意识打开的 App。", "添加目标") {
                chooseNewTarget()
            })
        } else {
            enabledRules.take(3).forEach { addView(ruleCard(it, compact = true)) }
        }

        addView(primaryButton(
            when {
                pauseActive -> "取消暂停 · 立即恢复"
                preferences.monitoringEnabled -> "暂停守门 30 分钟"
                else -> "开始守门"
            }
        ) {
            if (pauseActive) {
                preferences.bypassUntil = 0L
                toast("已恢复守门")
            } else if (preferences.monitoringEnabled) {
                preferences.bypassUntil = System.currentTimeMillis() + 30 * 60_000L
                toast("已暂停 30 分钟")
            } else {
                startMonitoring()
            }
            showTab(Tab.HOME)
        }.margins(top = 14))
    }

    private fun targetsPage(): View = page("目标", "${repository.enabledRules().size} 条规则正在生效") {
        addView(primaryButton("添加目标 App") { chooseNewTarget() })
        val rules = repository.rules()
        if (rules.isEmpty()) {
            addView(emptyCard("从一个 App 开始", "建议先选择最容易无意识打开的那个。", "选择 App") {
                chooseNewTarget()
            }.margins(top = 16))
        } else {
            rules.forEach { addView(ruleCard(it, compact = false).margins(top = 12)) }
        }
    }

    private fun exitsPage(): View = page("出口", "离开不是工作要求，也可以是更想做的娱乐") {
        addView(card().apply {
            addView(label("默认去处", 13f, muted))
            addView(label(preferences.alternativeLabel, 22f, ink, true).margins(top = 8))
            addView(secondaryButton("选择替代活动") { chooseAlternative() }.margins(top = 12))
            addView(secondaryButton("设为返回桌面") {
                preferences.alternativePackage = null
                preferences.alternativeLabel = "返回桌面"
                showTab(Tab.EXITS)
            }.margins(top = 8))
        })
        addView(sectionLabel("更多出口"))
        addView(card().apply {
            addView(label("今日小事 · 短时计时器 · 播放歌单", 16f, muted))
            addView(label("这些出口会在下一轮实现。", 13f, muted).margins(top = 6))
        })
    }

    private fun historyPage(): View = page("记录", "只记录选择，不评价选择") {
        val events = repository.todayEvents().filter { it.result != GateResult.SHOWN }.reversed()
        val continued = events.count { it.result == GateResult.CONTINUE }
        val left = events.count { it.result == GateResult.LEAVE }
        addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            addView(metric("$continued", "这次继续"), LinearLayout.LayoutParams(0, dp(84), 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(UiTheme.line) },
                LinearLayout.LayoutParams(dp(1), dp(54)).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(metric("$left", "换个去处"), LinearLayout.LayoutParams(0, dp(84), 1f))
        })
        addView(sectionLabel("今天"))
        if (events.isEmpty()) {
            addView(emptyCard("今天还没有记录", "门出现后，你的选择会显示在这里。", null, null))
        } else {
            val formatter = SimpleDateFormat("HH:mm", Locale.CHINA)
            events.take(30).forEach { event ->
                addView(card().apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(label(formatter.format(Date(event.occurredAt)), 13f, muted),
                        LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT))
                    addView(label(event.targetLabel, 16f, ink, true),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label(resultLabel(event.result), 14f,
                        if (event.result == GateResult.LEAVE) ink else muted))
                }.margins(bottom = 8))
            }
        }
    }

    private fun settingsPage(): View = page("设置", "权限与运行状态") {
        val serviceRunning = isServiceHeartbeatHealthy()
        val batteryUnrestricted = isIgnoringBatteryOptimizations()
        addView(card().apply {
            addView(label("运行诊断", 18f, ink, true))
            addView(diagnosticLine(
                "守门服务",
                serviceRunning,
                if (serviceRunning) "正在运行" else "未运行"
            ).margins(top = 12))
            addView(diagnosticLine(
                "电池后台限制",
                batteryUnrestricted,
                if (batteryUnrestricted) "不受限制" else "可能被系统停止"
            ).margins(top = 8))
            addView(label(
                "手机重启后，仅当守门总开关开启且必要权限仍有效时自动恢复。",
                13f, muted
            ).margins(top = 12))
            if (!serviceRunning && preferences.monitoringEnabled) {
                addView(secondaryButton("立即恢复守门") {
                    startMonitoring()
                    showTab(Tab.SETTINGS)
                }.margins(top = 12))
            }
        })

        addView(sectionLabel("必要权限"))
        addView(permissionRow(
            "使用情况访问",
            hasUsageAccess(),
            "识别当前打开的目标 App"
        ) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        addView(permissionRow(
            "显示在其他应用上层",
            Settings.canDrawOverlays(this@MainActivity),
            "在目标 App 上展示拖延之门"
        ) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }.margins(top = 10))
        if (Build.VERSION.SDK_INT >= 33) {
            addView(permissionRow(
                "运行状态通知",
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                "让系统允许守门服务持续运行"
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }.margins(top = 10))
        }
        addView(sectionLabel("后台运行"))
        addView(card().apply {
            addView(label("${Build.MANUFACTURER} 后台管理", 17f, ink, true))
            addView(label(
                "允许自启动和后台高耗电，避免系统清理守门服务。",
                13f, muted
            ).margins(top = 6))
            addView(secondaryButton("打开后台管理") {
                openManufacturerBackgroundSettings()
            }.margins(top = 10))
            addView(secondaryButton("打开电池优化设置") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.margins(top = 8))
        })
        addView(sectionLabel("守门"))
        addView(card().apply {
            val toggle = Switch(this@MainActivity).apply {
                text = "守门总开关"
                textSize = 17f
                setTextColor(ink)
                isChecked = preferences.monitoringEnabled
                setOnCheckedChangeListener { _, checked ->
                    if (checked) startMonitoring() else {
                        preferences.monitoringEnabled = false
                        stopService(Intent(this@MainActivity, GateMonitorService::class.java))
                    }
                }
            }
            addView(toggle)
            addView(label(
                "所有规则和记录只保存在当前设备。应用不读取屏幕内容，也不使用无障碍服务。",
                13f, muted
            ).margins(top = 10))
            addView(secondaryButton("重新查看首次引导") {
                showOnboarding()
            }.margins(top = 12))
        })
    }

    private fun chooseNewTarget() {
        AppPickerDialog.show(this, "选择目标 App", installedApps()) { selected ->
            val existing = repository.rules().firstOrNull { it.targetPackage == selected.packageName }
            editRule(existing ?: GateRule(
                targetPackage = selected.packageName,
                targetLabel = selected.label
            ))
        }
    }

    private fun chooseAlternative() {
        AppPickerDialog.show(this, "选择替代活动", installedApps()) { selected ->
            preferences.alternativePackage = selected.packageName
            preferences.alternativeLabel = selected.label
            showTab(Tab.EXITS)
        }
    }

    private fun previewRule(rule: GateRule) {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先开启悬浮窗权限")
            return
        }
        GateOverlayController.show(
            context = this,
            rule = rule,
            onResult = {},
            previewMode = true
        )
    }

    private fun showOnboarding() {
        OnboardingDialog.show(
            activity = this,
            installedApps = ::installedApps,
            hasUsageAccess = ::hasUsageAccess
        ) { rule ->
            repository.saveRule(rule)
            preferences.targetPackage = rule.targetPackage
            preferences.targetLabel = rule.targetLabel
            preferences.onboardingCompleted = true
            startMonitoring()
            showTab(Tab.HOME)
        }
    }

    private fun installedApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
    }

    private fun editRule(rule: GateRule) {
        RuleEditorDialog.show(
            activity = this,
            rule = rule,
            installedApps = ::installedApps,
            onSave = { updated ->
                repository.saveRule(updated)
                preferences.targetPackage = rule.targetPackage
                preferences.targetLabel = rule.targetLabel
                startMonitoring()
                showTab(Tab.TARGETS)
            },
            onTest = { previewRule(it) },
            onDuplicate = {
                repository.saveRule(it.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    targetLabel = "${it.targetLabel} 副本",
                    enabled = false
                ))
                showTab(Tab.TARGETS)
            },
            onDelete = if (repository.rules().any { it.id == rule.id }) {
                {
                    repository.deleteRule(rule.id)
                    showTab(Tab.TARGETS)
                }
            } else null
        )
    }

    private fun ruleCard(rule: GateRule, compact: Boolean): View = card().apply {
        orientation = LinearLayout.HORIZONTAL
        isClickable = true
        setOnClickListener { editRule(rule) }
        addView(UiTheme.doorGap(this@MainActivity, rule.enabled),
            LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                rightMargin = dp(14)
            })
        val body = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label(rule.targetLabel, 18f, ink, true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this@MainActivity).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                repository.setRuleEnabled(rule.id, checked)
                if (!compact) showTab(Tab.TARGETS)
            }
        }
        header.addView(toggle)
        body.addView(header)
        body.addView(label(ruleSummary(rule), 14f, muted).margins(top = 7))
        if (!compact) {
            val actions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(secondaryButton("编辑") { editRule(rule) },
                LinearLayout.LayoutParams(0, dp(46), 1f))
            actions.addView(ghostButton("测试") { previewRule(rule) }.margins(left = 8),
                LinearLayout.LayoutParams(0, dp(46), 1f))
            body.addView(actions.margins(top = 12))
        }
        addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun thresholdPanel(shown: Int, left: Int): View = card().apply {
        background = UiTheme.shape(this@MainActivity, ink, 20)
        setPadding(dp(20), dp(20), dp(20), dp(18))
        val paused = preferences.bypassUntil > System.currentTimeMillis()
        val status = when {
            !preferences.monitoringEnabled -> "守门已关闭  ○"
            paused -> "守门已暂停  ◐"
            else -> "守门中  ●"
        }
        val statusRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiTheme.doorGap(this@MainActivity, preferences.monitoringEnabled && !paused),
                LinearLayout.LayoutParams(dp(5), dp(34)).apply { rightMargin = dp(12) })
            addView(label(status, 15f, surface, true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiTheme.statusPill(
                this@MainActivity,
                if (preferences.monitoringEnabled && !paused) "已连接" else "待恢复",
                preferences.monitoringEnabled && !paused
            ))
        }
        addView(statusRow)
        addView(ThresholdView(this@MainActivity, shown, left),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = dp(15)
            })
        val metrics = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        metrics.addView(darkMetric("$shown", "今天经过"), LinearLayout.LayoutParams(0, dp(72), 1f))
        metrics.addView(darkMetric("$left", "选择离开"), LinearLayout.LayoutParams(0, dp(72), 1f))
        addView(metrics)
    }

    private fun permissionRow(title: String, granted: Boolean, description: String, action: () -> Unit) =
        card().apply {
            addView(label("$title  ${if (granted) "已开启" else "未开启"}", 17f,
                if (granted) ink else warning, true))
            addView(label(description, 13f, muted).margins(top = 5))
            if (!granted) addView(secondaryButton("去开启") { action() }.margins(top = 10))
        }

    private fun diagnosticLine(title: String, healthy: Boolean, detail: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(if (healthy) "●" else "○", 16f, if (healthy) accent else warning),
                LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(label(title, 15f, ink, true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(detail, 13f, muted))
        }

    private fun page(title: String, subtitle: String, build: LinearLayout.() -> Unit): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(this@MainActivity.background) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(30))
            addView(label(title, 32f, ink, true))
            addView(label(subtitle, 15f, UiTheme.inkSoft).margins(top = 7, bottom = 24))
            build()
        }
        scroll.addView(root)
        return scroll
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(17), dp(18), dp(17))
        background = UiTheme.shape(this@MainActivity, surface, 18, UiTheme.line)
    }

    private fun notice(
        message: String,
        color: Int,
        actionLabel: String = "查看",
        action: () -> Unit
    ) = card().apply {
        background = UiTheme.shape(this@MainActivity, color, 16)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(message, 14f, ink, true), LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(secondaryButton(actionLabel) { action() })
    }.margins(top = 12)

    private fun emptyCard(title: String, body: String, actionLabel: String?, action: (() -> Unit)?) =
        card().apply {
            addView(label(title, 18f, ink, true))
            addView(label(body, 14f, muted).margins(top = 6))
            if (actionLabel != null && action != null) {
                addView(secondaryButton(actionLabel) { action() }.margins(top = 12))
            }
        }

    private fun sectionLabel(value: String) = label(value.uppercase(), 12f, muted, true)
        .margins(top = 24, bottom = 10)

    private fun primaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        setTextColor(ink)
        background = UiTheme.shape(this@MainActivity, accent, 14)
        setOnClickListener { action() }
        minHeight = dp(54)
    }

    private fun secondaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setTextColor(surface)
        background = UiTheme.shape(this@MainActivity, ink, 12)
        setOnClickListener { action() }
    }

    private fun ghostButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setTextColor(ink)
        background = UiTheme.shape(this@MainActivity, surface, 12, UiTheme.line)
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(0f, 1.12f)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun metric(value: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(label(value, 25f, ink, true))
        addView(label(caption, 12f, muted).margins(top = 3))
    }

    private fun darkMetric(value: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(value, 25f, surface, true))
        addView(label(caption, 12f, muted).margins(top = 3))
    }

    private fun ruleSummary(rule: GateRule): String = buildList {
        if (rule.triggerOnOpen) add("打开时")
        rule.initialDelayMinutes?.let { add("使用 $it 分钟后") }
        rule.repeatIntervalMinutes?.let { add("每 $it 分钟") }
        if (rule.frictionTypes.isNotEmpty()) {
            add(rule.frictionTypes.sortedBy { it.ordinal }.joinToString("/") { friction ->
                when (friction) {
                    FrictionType.TRACE -> "描摹"
                    FrictionType.HOLD -> "长按"
                    FrictionType.BREATHE -> "呼吸"
                    FrictionType.TAP_SEQUENCE -> "点按"
                    FrictionType.DRAG_SLIDER -> "拖拽"
                    FrictionType.PINCH -> "捏合"
                    FrictionType.READ_PHRASE -> "读句"
                    FrictionType.COUNTDOWN -> "倒数"
                    FrictionType.MEMORY_GRID -> "记忆"
                    FrictionType.KNOB_LOCK -> "旋钮"
                    FrictionType.KEY_PATH -> "钥匙"
                    FrictionType.TARGET_SCAN -> "扫描"
                    FrictionType.BOLT_SEQUENCE -> "门栓"
                    FrictionType.GUIDED_SWIPE -> "滑组"
                    FrictionType.INTENT_CONFIRM -> "意图"
                    FrictionType.REVERSE_MEMORY -> "倒记"
                    FrictionType.PLATE_ASSEMBLY -> "拼合"
                    FrictionType.MULTI_STAGE -> "多段"
                    FrictionType.RULE_SWITCH -> "切规"
                    FrictionType.MEMORY_KNOB -> "记钮"
                    FrictionType.REFLECT_WAIT -> "反思"
                    FrictionType.ROUTE_PLAN -> "路线"
                    else -> "新"
                }
            })
        }
        if (rule.activeDays.size < 7) add("每周 ${rule.activeDays.size} 天")
        if (rule.startMinuteOfDay != 0 || rule.endMinuteOfDay != 24 * 60) {
            add("${minuteLabel(rule.startMinuteOfDay)}–${minuteLabel(rule.endMinuteOfDay)}")
        }
    }.joinToString(" · ")

    private fun minuteLabel(minute: Int): String =
        if (minute == 24 * 60) "24:00" else "%02d:%02d".format(minute / 60, minute % 60)

    private fun resultLabel(result: GateResult) = when (result) {
        GateResult.CONTINUE -> "这次继续"
        GateResult.LEAVE -> "换个去处"
        GateResult.BYPASS -> "紧急绕过"
        GateResult.MANUAL_LEAVE -> "自行离开"
        GateResult.SHOWN -> "门出现"
    }

    private fun startMonitoring() {
        if (repository.enabledRules().isEmpty()) {
            toast("请先添加一条目标规则")
            return
        }
        if (!hasUsageAccess() || !Settings.canDrawOverlays(this)) {
            toast("请先开启必要权限")
            showTab(Tab.SETTINGS)
            return
        }
        preferences.monitoringEnabled = true
        startForegroundService(Intent(this, GateMonitorService::class.java))
    }

    private fun restoreMonitoringServiceIfNeeded() {
        if (
            preferences.monitoringEnabled &&
            repository.enabledRules().isNotEmpty() &&
            hasUsageAccess() &&
            Settings.canDrawOverlays(this)
        ) {
            startForegroundService(Intent(this, GateMonitorService::class.java))
        }
    }

    private fun hasUsageAccess(): Boolean {
        val manager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return manager.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun isServiceHeartbeatHealthy(): Boolean =
        preferences.monitoringEnabled &&
            System.currentTimeMillis() - preferences.serviceHeartbeatAt < 12_000L

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openManufacturerBackgroundSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val candidates = when {
            manufacturer.contains("vivo") -> listOf(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity")
            )
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            else -> emptyList()
        }
        for (component in candidates) {
            val opened = runCatching {
                startActivity(Intent().setComponent(component))
                true
            }.getOrDefault(false)
            if (opened) return
        }
        startActivity(Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ))
    }

    private fun <T : View> T.margins(top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): T {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }
        return this
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private enum class Tab(val label: String, val symbol: String) {
        HOME("首页", "⌂"),
        TARGETS("目标", "◎"),
        EXITS("出口", "↗"),
        HISTORY("记录", "≡"),
        SETTINGS("设置", "◇")
    }
}
