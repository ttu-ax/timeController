package com.example.procrastinationgate

import android.content.Context

class GatePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("gate_preferences", Context.MODE_PRIVATE)

    var targetPackage: String?
        get() = prefs.getString("target_package", null)
        set(value) = prefs.edit().putString("target_package", value).apply()

    var targetLabel: String
        get() = prefs.getString("target_label", "尚未选择") ?: "尚未选择"
        set(value) = prefs.edit().putString("target_label", value).apply()

    var alternativePackage: String?
        get() = prefs.getString("alternative_package", null)
        set(value) = prefs.edit().putString("alternative_package", value).apply()

    var alternativeLabel: String
        get() = prefs.getString("alternative_label", "返回桌面") ?: "返回桌面"
        set(value) = prefs.edit().putString("alternative_label", value).apply()

    var intervalMinutes: Int
        get() = prefs.getInt("interval_minutes", 5)
        set(value) = prefs.edit().putInt("interval_minutes", value).apply()

    var monitoringEnabled: Boolean
        get() = prefs.getBoolean("monitoring_enabled", false)
        set(value) = prefs.edit().putBoolean("monitoring_enabled", value).apply()

    var bypassUntil: Long
        get() = prefs.getLong("bypass_until", 0L)
        set(value) = prefs.edit().putLong("bypass_until", value).apply()

    var sessionAllowedUntil: Long
        get() = prefs.getLong("session_allowed_until", 0L)
        set(value) = prefs.edit().putLong("session_allowed_until", value).apply()

    var serviceHeartbeatAt: Long
        get() = prefs.getLong("service_heartbeat_at", 0L)
        set(value) = prefs.edit().putLong("service_heartbeat_at", value).apply()

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()
}
