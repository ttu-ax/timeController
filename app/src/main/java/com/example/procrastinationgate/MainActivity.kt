package com.example.procrastinationgate

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var preferences: GatePreferences
    private lateinit var repository: GateRepository
    private lateinit var content: FrameLayout
    private var currentTab = Tab.HOME

    private class NavItem(
        val container: LinearLayout,
        val iconPill: FrameLayout,
        val icon: ImageView,
        val label: TextView
    )
    private val navigationItems = mutableMapOf<Tab, NavItem>()

    private val ink = UiTheme.ink
    private val background = UiTheme.canvas
    private val surface = UiTheme.surface
    private val accent = UiTheme.accent
    private val muted = UiTheme.muted
    private val warning = UiTheme.warning

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == UpdateChecker.pendingDownloadId
            ) {
                val file = UpdateChecker.pendingFile
                if (file != null && file.exists()) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("下载完成")
                        .setMessage("新版本已下载，是否安装？")
                        .setPositiveButton("安装") { _, _ ->
                            ensureInstallPermission { installUpdate(file) }
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = GatePreferences(this)
        repository = GateRepository(this)
        setContentView(buildShell())
        showTab(Tab.HOME)
        if (!preferences.onboardingCompleted && repository.rules().isEmpty()) {
            content.post { showOnboarding() }
        }
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        restoreMonitoringServiceIfNeeded()
        showTab(currentTab)
        checkForUpdates(manual = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(downloadReceiver) }
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

        // 顶层极细分隔线
        root.addView(View(this).apply { setBackgroundColor(UiTheme.line) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        // 极简现代导航栏
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(6))
            setBackgroundColor(this@MainActivity.surface)
        }
        Tab.entries.forEach { tab ->
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(2))
                isClickable = true
                isFocusable = true
                background = UiTheme.ripple(this@MainActivity, Color.TRANSPARENT, UiTheme.accentSoft, 16)
                setOnClickListener { showTab(tab) }
            }
            val iconPill = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(26)).apply {
                    gravity = Gravity.CENTER
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(tab.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
            }
            iconPill.addView(icon)
            val text = label(tab.label, 11f, muted).apply {
                gravity = Gravity.CENTER
            }
            container.addView(iconPill)
            container.addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })

            navigationItems[tab] = NavItem(container, iconPill, icon, text)
            navigation.addView(container, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(navigation)
        return root
    }

    private fun showTab(tab: Tab) {
        currentTab = tab
        navigationItems.forEach { (item, nav) ->
            val selected = item == tab
            if (selected) {
                nav.iconPill.background = UiTheme.shape(this, UiTheme.accentSoft, 13)
                nav.icon.setColorFilter(UiTheme.accent)
                nav.label.setTextColor(UiTheme.ink)
                nav.label.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            } else {
                nav.iconPill.background = null
                nav.icon.setColorFilter(UiTheme.muted)
                nav.label.setTextColor(UiTheme.muted)
                nav.label.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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

    private fun homePage(): View = page("拖延之门", "让每次继续都成为一次清醒的抉择") {
        val enabledRules = repository.enabledRules()
        val events = repository.todayEvents()
        val shown = events.count { it.result == GateResult.SHOWN }
        val left = events.count { it.result == GateResult.LEAVE }
        val now = System.currentTimeMillis()
        val pauseActive = preferences.bypassUntil > now

        addView(thresholdPanel(shown, left, events))

        if (pauseActive) {
            val until = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(preferences.bypassUntil))
            addView(notice("守门已暂停 · 至 $until", UiTheme.warningSoft, "取消暂停") {
                preferences.bypassUntil = 0L
                toast("已恢复守门")
                showTab(Tab.HOME)
            })
        }

        val healthy = hasUsageAccess() && Settings.canDrawOverlays(this@MainActivity)
        if (!healthy) {
            addView(notice("必要权限未就绪，守门暂时停止", UiTheme.warningSoft, "去授权") {
                showTab(Tab.SETTINGS)
            })
        }

        addView(sectionLabel("守护中的门"))
        if (enabledRules.isEmpty()) {
            addView(emptyCard("还没有守护目标", "选择一个经常无意识打开的 App，开启第一次清醒停留。", "添加目标") {
                chooseNewTarget()
            })
        } else {
            enabledRules.take(3).forEach { addView(ruleCard(it, compact = true).margins(bottom = 10)) }
        }

        addView(primaryButton(
            when {
                pauseActive -> "取消暂停 · 立即恢复守护"
                preferences.monitoringEnabled -> "暂停守门"
                else -> "开启守门"
            }
        ) {
            if (pauseActive) {
                preferences.bypassUntil = 0L
                toast("已恢复守门")
                showTab(Tab.HOME)
            } else if (preferences.monitoringEnabled) {
                showPauseDialog()
            } else {
                startMonitoring()
                showTab(Tab.HOME)
            }
        }.margins(top = 16))
    }

    private fun showPauseDialog() {
        val now = System.currentTimeMillis()
        val endOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }.timeInMillis
        AlertDialog.Builder(this)
            .setTitle("暂停守门")
            .setItems(arrayOf("暂停 15 分钟", "暂停 30 分钟", "今天就到这里")) { _, which ->
                preferences.bypassUntil = when (which) {
                    0 -> now + 15 * 60_000L
                    1 -> now + 30 * 60_000L
                    else -> endOfToday
                }
                toast(if (which == 2) "今天不再守门" else "已暂停 ${if (which == 0) 15 else 30} 分钟")
                showTab(Tab.HOME)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun targetsPage(): View = page("目标", "已配置 ${repository.rules().size} 条规则 · ${repository.enabledRules().size} 条正在生效") {
        addView(ghostButton("＋ 添加目标 App") { chooseNewTarget() })
        val rules = repository.rules()
        if (rules.isEmpty()) {
            addView(emptyCard("从一个容易沉迷的 App 开始", "建议先选择最容易让你无意识刷屏的那个应用。", "选择 App") {
                chooseNewTarget()
            }.margins(top = 16))
        } else {
            rules.forEach { addView(ruleCard(it, compact = false).margins(top = 12)) }
        }
    }

    private fun exitsPage(): View = page("出口", "离开不是工作要求，也可以是更想做的娱乐") {
        addView(card().apply {
            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val appIcon = UiTheme.createAppIconView(
                this@MainActivity,
                preferences.alternativePackage,
                preferences.alternativeLabel,
                46,
                12
            )
            headerRow.addView(appIcon, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(14) })

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("当前默认出口", 13f, muted))
                addView(label(preferences.alternativeLabel, 20f, ink, true).margins(top = 3))
            }
            headerRow.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(headerRow)

            addView(label(
                "当门被触发时，你可以直接点击「换个去处」一键进入这里，顺畅打破无意识沉迷。",
                14f,
                UiTheme.inkSoft
            ).margins(top = 14))

            addView(primaryButton("选择其他替代活动") { chooseAlternative() }.margins(top = 14))
            addView(ghostButton("改用「返回系统桌面」") {
                preferences.alternativePackage = null
                preferences.alternativeLabel = "返回桌面"
                showTab(Tab.EXITS)
            }.margins(top = 8))
        })

        addView(sectionLabel("推荐去处"))
        val recRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val quickExits = listOf("微信读书", "网易云音乐", "备忘录", "相册")
        quickExits.forEach { name ->
            val chip = UiTheme.chip(this@MainActivity, "＋ $name", false).apply {
                isClickable = true
                setOnClickListener {
                    val found = installedApps().firstOrNull { it.label.contains(name) }
                    if (found != null) {
                        preferences.alternativePackage = found.packageName
                        preferences.alternativeLabel = found.label
                        toast("已设出口为：${found.label}")
                        showTab(Tab.EXITS)
                    } else {
                        toast("本机未找到包含「$name」的应用")
                    }
                }
            }
            recRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
        }
        addView(recRow)
    }

    private fun historyPage(): View = page("记录", "只记录选择，不评价选择") {
        val events = repository.todayEvents().filter { it.result != GateResult.SHOWN }.reversed()
        val continued = events.count { it.result == GateResult.CONTINUE }
        val left = events.count { it.result == GateResult.LEAVE }
        val total = continued + left
        val turnaroundRate = if (total > 0) (left * 100 / total) else 0

        addView(card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(metric("$continued", "这次继续"), LinearLayout.LayoutParams(0, dp(76), 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(UiTheme.line) },
                LinearLayout.LayoutParams(dp(1), dp(44)))
            addView(metric("$left", "清醒离开"), LinearLayout.LayoutParams(0, dp(76), 1f))
            if (total > 0) {
                addView(View(this@MainActivity).apply { setBackgroundColor(UiTheme.line) },
                    LinearLayout.LayoutParams(dp(1), dp(44)))
                addView(metric("$turnaroundRate%", "清醒率"), LinearLayout.LayoutParams(0, dp(76), 1f))
            }
        })

        addView(sectionLabel("今天的时间足迹"))
        if (events.isEmpty()) {
            addView(emptyCard("今天还没有记录", "门出现后，你的每次停留与选择都会优雅记录在这里。", null, null))
        } else {
            val formatter = SimpleDateFormat("HH:mm", Locale.CHINA)
            events.take(30).forEach { event ->
                val card = card().apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))

                    // App 图标
                    val appIcon = UiTheme.createAppIconView(
                        this@MainActivity,
                        event.targetPackage,
                        event.targetLabel,
                        36,
                        10
                    )
                    addView(appIcon, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(12) })

                    val textCol = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(event.targetLabel, 15f, ink, true))
                        addView(label(formatter.format(Date(event.occurredAt)), 12f, muted).margins(top = 2))
                    }
                    addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                    // 结果徽章
                    val (badgeText, badgeBg, badgeColor) = when (event.result) {
                        GateResult.LEAVE -> Triple("换个去处", UiTheme.accentSoft, Color.rgb(28, 122, 72))
                        GateResult.BYPASS -> Triple("紧急绕过", UiTheme.warningSoft, UiTheme.warning)
                        else -> Triple("这次继续", UiTheme.surfaceMuted, UiTheme.inkSoft)
                    }
                    val badge = TextView(this@MainActivity).apply {
                        text = badgeText
                        textSize = 12f
                        setTextColor(badgeColor)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = UiTheme.shape(this@MainActivity, badgeBg, UiTheme.RADIUS_PILL)
                    }
                    addView(badge)
                }.margins(bottom = 8)
                addView(card)
            }
        }
    }

    private fun settingsPage(): View = page("设置", "权限与运行状态") {
        val serviceRunning = isServiceHeartbeatHealthy()
        val batteryUnrestricted = isIgnoringBatteryOptimizations()
        addView(card().apply {
            addView(label("运行诊断", 17f, ink, true))
            addView(diagnosticLine(
                "守门服务",
                serviceRunning,
                if (serviceRunning) "正在持续运行" else "未运行"
            ).margins(top = 12))
            addView(diagnosticLine(
                "电池后台限制",
                batteryUnrestricted,
                if (batteryUnrestricted) "不受限制" else "可能被系统节电停止"
            ).margins(top = 8))
            addView(label(
                "手机重启后，仅当守门总开关开启且必要权限仍有效时自动恢复守护。",
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
            "在目标 App 上展示清醒停留门"
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
                "允许前台服务保活通知持续运行"
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
            }.margins(top = 10))
        }

        addView(sectionLabel("后台防杀守护"))
        addView(card().apply {
            addView(label("${Build.MANUFACTURER} 系统后台优化", 17f, ink, true))
            addView(label(
                "请将本应用加入自启动和后台无限制白名单，避免系统清理守门服务。",
                13f, muted
            ).margins(top = 6))
            addView(secondaryButton("打开系统后台管理") {
                openManufacturerBackgroundSettings()
            }.margins(top = 12))
            addView(ghostButton("打开系统电池优化设置") {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.margins(top = 8))
        })

        addView(sectionLabel("守护控制"))
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
                "所有规则与时间足迹均 100% 仅保存在本机。不读取任何屏幕内容，不使用无障碍权限。",
                13f, muted
            ).margins(top = 10))
            addView(ghostButton("重新查看首次使用引导") {
                showOnboarding(allowDismiss = true)
            }.margins(top = 12))
        })

        addView(sectionLabel("版本信息"))
        addView(card().apply {
            addView(label("拖延之门 v${currentVersionName()}", 17f, ink, true))
            addView(label("自主掌控时间，从每次清醒抉择开始。", 13f, muted).margins(top = 4))
            addView(secondaryButton("检查版本更新") { checkForUpdates(manual = true) }.margins(top = 12))
        })
    }

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    private fun checkForUpdates(manual: Boolean) {
        val current = currentVersionName()
        Thread {
            val info = UpdateChecker.checkLatest()
            runOnUiThread {
                when {
                    info == null -> if (manual) toast("检查更新失败，请检查网络后重试")
                    UpdateChecker.isNewer(info.latestVersion, current) -> showUpdateDialog(info)
                    manual -> toast("已是最新版本 v$current")
                }
            }
        }.start()
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.releaseNotes.takeIf { it.isNotBlank() }
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${info.latestVersion}")
            .setMessage(
                "当前版本 v${currentVersionName()}\n\n" +
                    (notes?.let { "更新内容：\n$it\n" } ?: "") +
                    "\n是否下载并安装？"
            )
            .setPositiveButton("下载") { _, _ ->
                UpdateChecker.startDownload(this, info.downloadUrl)
                toast("正在后台下载，完成后会提醒你安装")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun ensureInstallPermission(continueAction: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("请允许「安装未知应用」，才能安装更新。")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    ))
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            continueAction()
        }
    }

    private fun installUpdate(file: File) {
        if (!UpdateChecker.install(this, file)) {
            toast("无法启动安装")
        }
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

    private fun showOnboarding(allowDismiss: Boolean = false) {
        OnboardingDialog.show(
            activity = this,
            installedApps = ::installedApps,
            hasUsageAccess = ::hasUsageAccess,
            allowDismiss = allowDismiss
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
        orientation = LinearLayout.VERTICAL
        isClickable = true
        background = UiTheme.ripple(this@MainActivity, surface, UiTheme.accentSoft, UiTheme.RADIUS_CARD, UiTheme.line, 1)
        setOnClickListener { editRule(rule) }

        val topRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 真实 App 图标
        val appIcon = UiTheme.createAppIconView(this@MainActivity, rule.targetPackage, rule.targetLabel, 44, 12)
        topRow.addView(appIcon, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            rightMargin = dp(12)
        })

        val infoCol = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(rule.targetLabel, 17f, ink, true))
            val summaryChips = ruleChips(rule)
            val chipsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            summaryChips.take(3).forEach { chipText ->
                chipsRow.addView(UiTheme.chip(this@MainActivity, chipText, rule.enabled),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(6)
                    })
            }
            addView(chipsRow)
        }
        topRow.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Switch(this@MainActivity).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, checked ->
                repository.setRuleEnabled(rule.id, checked)
                if (!compact) showTab(Tab.TARGETS)
            }
        }
        topRow.addView(toggle)
        addView(topRow)

        if (!compact) {
            val actions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            actions.addView(secondaryButton("编辑规则") { editRule(rule) },
                LinearLayout.LayoutParams(0, dp(40), 1f))
            actions.addView(ghostButton("测试") { previewRule(rule) }.margins(left = 8),
                LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(actions)
        }
    }

    private fun ruleChips(rule: GateRule): List<String> = buildList {
        if (rule.triggerOnOpen) add("打开即触发")
        rule.initialDelayMinutes?.let { add("连续 $it 分钟") }
        rule.repeatIntervalMinutes?.let { add("每 $it 分钟") }
        if (rule.frictionTypes.isNotEmpty()) {
            add(rule.frictionTypes.first().title + if (rule.frictionTypes.size > 1) "等" else "")
        }
        if (rule.activeDays.size in 1..6) add("周 ${rule.activeDays.size} 天")
        if (rule.startMinuteOfDay != 0 || rule.endMinuteOfDay != 24 * 60) {
            add("${minuteLabel(rule.startMinuteOfDay)}–${minuteLabel(rule.endMinuteOfDay)}")
        }
    }

    private fun thresholdPanel(shown: Int, left: Int, events: List<GateEvent>): View = card().apply {
        background = UiTheme.gradient(
            this@MainActivity,
            intArrayOf(UiTheme.heroSurface, UiTheme.heroSurfaceAlt),
            UiTheme.RADIUS_CARD,
            GradientDrawable.Orientation.TL_BR,
            UiTheme.heroLine,
            1
        )
        setPadding(dp(20), dp(20), dp(20), dp(18))
        val paused = preferences.bypassUntil > System.currentTimeMillis()
        val status = when {
            !preferences.monitoringEnabled -> "守门已关闭"
            paused -> "守门已暂停"
            else -> "守门中 · 正在守护"
        }
        val statusRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiTheme.doorGap(this@MainActivity, preferences.monitoringEnabled && !paused),
                LinearLayout.LayoutParams(dp(4), dp(28)).apply { rightMargin = dp(12) })
            val titleStack = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(status, 16f, Color.WHITE, true))
                addView(label(
                    if (preferences.monitoringEnabled && !paused) "每次进入均提供清醒选择" else "触摸主按钮即可恢复",
                    12f,
                    Color.argb(160, 255, 255, 255)
                ).margins(top = 2))
            }
            addView(titleStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiTheme.statusPill(
                this@MainActivity,
                if (preferences.monitoringEnabled && !paused) "守护中" else "待恢复",
                preferences.monitoringEnabled && !paused
            ))
        }
        addView(statusRow)
        addView(ThresholdView(this@MainActivity, events),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)).apply {
                topMargin = dp(14)
                bottomMargin = dp(4)
            })

        val metrics = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metrics.addView(darkMetric("$shown", "今日触发"), LinearLayout.LayoutParams(0, dp(64), 1f))
        metrics.addView(View(this@MainActivity).apply {
            setBackgroundColor(UiTheme.heroLine)
        }, LinearLayout.LayoutParams(dp(1), dp(32)))
        metrics.addView(darkMetric("$left", "清醒离开"), LinearLayout.LayoutParams(0, dp(64), 1f))

        if (shown > 0) {
            metrics.addView(View(this@MainActivity).apply {
                setBackgroundColor(UiTheme.heroLine)
            }, LinearLayout.LayoutParams(dp(1), dp(32)))
            val rate = (left * 100 / shown).coerceIn(0, 100)
            metrics.addView(darkMetric("$rate%", "清醒率"), LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        addView(metrics)

        val last = repository.events().lastOrNull { it.result != GateResult.SHOWN }
        if (last != null) {
            val footer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(View(this@MainActivity).apply {
                    background = UiTheme.shape(this@MainActivity, UiTheme.accentGlow, 999)
                }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(6) })
                addView(label(
                    "上次 · ${eventTimeLabel(last.occurredAt)} ${resultLabel(last.result)}",
                    UiTheme.TEXT_CAPTION,
                    Color.argb(170, 255, 255, 255)
                ))
            }
            addView(footer)
        }
    }

    private fun eventTimeLabel(at: Long): String {
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at))
        return when {
            at >= startOfToday -> "今天 $time"
            at >= startOfToday - 24L * 60 * 60 * 1000 -> "昨天 $time"
            else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(at))
        }
    }

    private fun permissionRow(title: String, granted: Boolean, description: String, action: () -> Unit) =
        card().apply {
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(label(title, UiTheme.TEXT_CARD_TITLE, ink, true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(TextView(this@MainActivity).apply {
                text = if (granted) "已开启" else "未开启"
                textSize = UiTheme.TEXT_CAPTION
                setTextColor(if (granted) Color.rgb(28, 122, 72) else Color.rgb(180, 100, 30))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = UiTheme.shape(
                    this@MainActivity,
                    if (granted) UiTheme.accentSoft else Color.rgb(254, 244, 233),
                    UiTheme.RADIUS_PILL
                )
            })
            addView(header)
            addView(label(description, 13f, muted).margins(top = 6))
            if (!granted) addView(ghostButton("去授权开启") { action() }.margins(top = 10))
        }

    private fun diagnosticLine(title: String, healthy: Boolean, detail: String) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@MainActivity).apply {
                background = UiTheme.shape(this@MainActivity, if (healthy) accent else warning, 999)
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) })
            addView(label(title, 15f, ink, true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(detail, 13f, if (healthy) Color.rgb(28, 122, 72) else warning))
        }

    private fun page(title: String, subtitle: String, build: LinearLayout.() -> Unit): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(this@MainActivity.background)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            addView(label(title, 30f, ink, true))
            addView(label(subtitle, 14f, UiTheme.inkSoft).margins(top = 6, bottom = 20))
            build()
        }
        scroll.addView(root)
        return scroll
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(UiTheme.PAD_CARD), dp(UiTheme.PAD_CARD),
            dp(UiTheme.PAD_CARD), dp(UiTheme.PAD_CARD)
        )
        background = UiTheme.shape(this@MainActivity, surface, UiTheme.RADIUS_CARD, UiTheme.line)
    }

    private fun notice(
        message: String,
        color: Int,
        actionLabel: String = "查看",
        action: () -> Unit
    ) = card().apply {
        background = UiTheme.shape(this@MainActivity, color, 16, Color.argb(40, 235, 146, 60))
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(message, 14f, ink, true), LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(ghostButton(actionLabel) { action() })
    }.margins(top = 12)

    private fun emptyCard(title: String, body: String, actionLabel: String?, action: (() -> Unit)?) =
        card().apply {
            addView(label(title, 17f, ink, true))
            addView(label(body, 14f, muted).margins(top = 6))
            if (actionLabel != null && action != null) {
                addView(ghostButton(actionLabel) { action() }.margins(top = 12))
            }
        }

    private fun sectionLabel(value: String) =
        label(value.uppercase(), UiTheme.TEXT_CAPTION, muted, true)
            .margins(top = UiTheme.GAP_SECTION, bottom = UiTheme.GAP_ITEM)

    private fun primaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = UiTheme.ripple(this@MainActivity, ink, accent, UiTheme.RADIUS_BUTTON)
        setOnClickListener { action() }
        minHeight = dp(UiTheme.HEIGHT_PRIMARY)
    }

    private fun secondaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = UiTheme.ripple(this@MainActivity, ink, accent, UiTheme.RADIUS_BUTTON)
        setOnClickListener { action() }
        minHeight = dp(UiTheme.HEIGHT_ACTION)
    }

    private fun ghostButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 14f
        isAllCaps = false
        setTextColor(ink)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = UiTheme.ripple(this@MainActivity, surface, UiTheme.accentSoft, UiTheme.RADIUS_BUTTON, UiTheme.line, 1)
        setOnClickListener { action() }
        minHeight = dp(UiTheme.HEIGHT_ACTION)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun metric(value: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(label(value, 24f, ink, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(label(caption, 12f, muted).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
    }

    private fun darkMetric(value: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(label(value, 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        addView(label(caption, 12f, Color.argb(170, 255, 255, 255)).apply { gravity = Gravity.CENTER }.margins(top = 2))
    }

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

    private enum class Tab(val label: String, val icon: Int) {
        HOME("首页", R.drawable.ic_tab_home),
        TARGETS("目标", R.drawable.ic_tab_targets),
        EXITS("出口", R.drawable.ic_tab_exits),
        HISTORY("记录", R.drawable.ic_tab_history),
        SETTINGS("设置", R.drawable.ic_tab_settings)
    }
}
