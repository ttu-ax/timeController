package com.example.procrastinationgate

import android.content.Context
import org.json.JSONArray

class GateRepository(context: Context) {
    private val prefs = context.getSharedPreferences("gate_repository", Context.MODE_PRIVATE)
    private val legacy = GatePreferences(context)

    init {
        migrateLegacyRule()
    }

    fun rules(): List<GateRule> = parseArray(prefs.getString("rules", null), GateRule::fromJson)

    fun enabledRules(): List<GateRule> = rules().filter { it.enabled }

    fun ruleForPackage(packageName: String): GateRule? =
        enabledRules().firstOrNull { it.targetPackage == packageName }

    fun saveRule(rule: GateRule) {
        val all = rules().toMutableList()
        val index = all.indexOfFirst { it.id == rule.id || it.targetPackage == rule.targetPackage }
        val updated = rule.copy(updatedAt = System.currentTimeMillis())
        if (index >= 0) all[index] = updated else all.add(updated)
        writeArray("rules", all.map(GateRule::toJson))
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        val all = rules().map {
            if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it
        }
        writeArray("rules", all.map(GateRule::toJson))
    }

    fun deleteRule(id: String) {
        writeArray("rules", rules().filterNot { it.id == id }.map(GateRule::toJson))
    }

    fun addEvent(event: GateEvent) {
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
        val all = events().filter { it.occurredAt >= cutoff }.toMutableList()
        all.add(event)
        writeArray("events", all.takeLast(2_000).map(GateEvent::toJson))
    }

    fun events(): List<GateEvent> =
        parseArray(prefs.getString("events", null), GateEvent::fromJson)

    fun todayEvents(): List<GateEvent> {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return events().filter { it.occurredAt >= calendar.timeInMillis }
    }

    private fun migrateLegacyRule() {
        if (prefs.getBoolean("legacy_migrated", false)) return
        val targetPackage = legacy.targetPackage
        if (targetPackage != null && rules().none { it.targetPackage == targetPackage }) {
            val minutes = legacy.intervalMinutes
            saveRule(
                GateRule(
                    targetPackage = targetPackage,
                    targetLabel = legacy.targetLabel,
                    triggerOnOpen = minutes == 0,
                    initialDelayMinutes = minutes.takeIf { it > 0 },
                    repeatIntervalMinutes = minutes.takeIf { it > 0 }
                )
            )
        }
        prefs.edit().putBoolean("legacy_migrated", true).apply()
    }

    private fun <T> parseArray(raw: String?, parser: (org.json.JSONObject) -> T): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(parser(array.getJSONObject(index)))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeArray(key: String, values: List<org.json.JSONObject>) {
        val array = JSONArray()
        values.forEach(array::put)
        prefs.edit().putString(key, array.toString()).apply()
    }
}
