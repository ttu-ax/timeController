package com.example.procrastinationgate

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import java.util.Calendar

class GateMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: GatePreferences
    private lateinit var repository: GateRepository
    private var session: TargetSession? = null
    private var currentForegroundPackage: String? = null
    private var currentForegroundEventAt = 0L
    private var lastEventQueryAt = 0L
    private var lastHeartbeatWriteAt = 0L

    private val monitor = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastHeartbeatWriteAt >= HEARTBEAT_INTERVAL_MS) {
                preferences.serviceHeartbeatAt = now
                lastHeartbeatWriteAt = now
            }
            if (!preferences.monitoringEnabled) {
                endSession(recordManualLeave = false)
                stopSelf()
                return
            }
            updateSession()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = GatePreferences(this)
        repository = GateRepository(this)
        preferences.serviceHeartbeatAt = System.currentTimeMillis()
        createChannel()
        startForeground(
            1001,
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("拖延之门正在守门")
                .setContentText("只在本机观察你选择的 App")
                .setSmallIcon(R.drawable.ic_gate)
                .setOngoing(true)
                .build()
        )
        handler.post(monitor)
    }

    private fun updateSession() {
        val now = System.currentTimeMillis()

        if (!Settings.canDrawOverlays(this) || !isScreenAvailable()) {
            endSession(recordManualLeave = GateOverlayController.isShowing())
            return
        }

        val foreground = findForegroundPackage(now) ?: return
        val activeSession = session

        if (activeSession != null && foreground != activeSession.rule.targetPackage) {
            endSession(recordManualLeave = activeSession.state == SessionState.BLOCKING)
        }

        if (now < preferences.bypassUntil) return

        val rule = repository.ruleForPackage(foreground)
        if (rule == null || !isRuleActiveNow(rule, now)) return

        val current = session
        if (current == null || current.rule.id != rule.id) {
            session = TargetSession(
                rule = rule,
                enteredAt = now,
                state = SessionState.OBSERVING
            )
        }

        evaluateSession(now)
    }

    private fun evaluateSession(now: Long) {
        val current = session ?: return
        when (current.state) {
            SessionState.OBSERVING -> {
                when {
                    current.rule.triggerOnOpen ->
                        showGate(current, TriggerType.OPEN)

                    current.rule.initialDelayMinutes != null &&
                        now - current.enteredAt >= current.rule.initialDelayMinutes * 60_000L ->
                        showGate(current, TriggerType.DURATION)
                }
            }

            SessionState.ALLOWED -> {
                if (
                    current.rule.repeatIntervalMinutes != null &&
                    now >= current.allowedUntil
                ) {
                    showGate(current, TriggerType.REPEAT)
                }
            }

            SessionState.BLOCKING -> Unit
        }
    }

    private fun showGate(current: TargetSession, triggerType: TriggerType) {
        if (GateOverlayController.isShowing()) return
        current.state = SessionState.BLOCKING
        current.activeTrigger = triggerType
        repository.addEvent(eventFor(current.rule, triggerType, GateResult.SHOWN))

        GateOverlayController.show(
            context = this,
            rule = current.rule,
            onResult = { result -> handleGateResult(current, triggerType, result) }
        )
    }

    private fun handleGateResult(
        targetSession: TargetSession,
        triggerType: TriggerType,
        result: GateResult
    ) {
        if (session !== targetSession) return
        repository.addEvent(eventFor(targetSession.rule, triggerType, result))
        targetSession.activeTrigger = null

        when (result) {
            GateResult.CONTINUE -> {
                targetSession.state = SessionState.ALLOWED
                val minutes = GateOverlayController.lastContinueMinutes
                    ?: targetSession.rule.repeatIntervalMinutes
                targetSession.allowedUntil = minutes?.let {
                    System.currentTimeMillis() + it * 60_000L
                } ?: Long.MAX_VALUE
            }

            GateResult.LEAVE -> {
                // 保持会话到系统报告前台 App 已切换，避免出口启动期间再次弹门。
                targetSession.state = SessionState.ALLOWED
                targetSession.allowedUntil = Long.MAX_VALUE
            }

            GateResult.BYPASS -> endSession(recordManualLeave = false)
            GateResult.SHOWN, GateResult.MANUAL_LEAVE -> Unit
        }
    }

    private fun endSession(recordManualLeave: Boolean) {
        val current = session
        if (recordManualLeave && current != null) {
            repository.addEvent(
                eventFor(
                    current.rule,
                    current.activeTrigger ?: TriggerType.OPEN,
                    GateResult.MANUAL_LEAVE
                )
            )
        }
        GateOverlayController.dismiss()
        session = null
    }

    private fun isScreenAvailable(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun isRuleActiveNow(rule: GateRule, now: Long): Boolean {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val day = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }
        if (day !in rule.activeDays) return false
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (rule.startMinuteOfDay <= rule.endMinuteOfDay) {
            minute in rule.startMinuteOfDay until rule.endMinuteOfDay
        } else {
            minute >= rule.startMinuteOfDay || minute < rule.endMinuteOfDay
        }
    }

    private fun eventFor(
        rule: GateRule,
        triggerType: TriggerType,
        result: GateResult
    ) = GateEvent(
        ruleId = rule.id,
        targetPackage = rule.targetPackage,
        targetLabel = rule.targetLabel,
        triggerType = triggerType,
        result = result
    )

    private fun findForegroundPackage(now: Long): String? {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val queryStart = if (lastEventQueryAt == 0L) now - 10_000 else lastEventQueryAt - 250
        val events = manager.queryEvents(queryStart, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) &&
                event.timeStamp > currentForegroundEventAt
            ) {
                currentForegroundEventAt = event.timeStamp
                currentForegroundPackage = event.packageName
            }
        }
        lastEventQueryAt = now
        return currentForegroundPackage
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        endSession(recordManualLeave = false)
        preferences.serviceHeartbeatAt = 0L
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "守门服务",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class TargetSession(
        val rule: GateRule,
        val enteredAt: Long,
        var state: SessionState,
        var allowedUntil: Long = 0L,
        var activeTrigger: TriggerType? = null
    )

    private enum class SessionState {
        OBSERVING,
        BLOCKING,
        ALLOWED
    }

    companion object {
        private const val CHANNEL_ID = "gate_monitor"
        private const val POLL_INTERVAL_MS = 500L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }
}
