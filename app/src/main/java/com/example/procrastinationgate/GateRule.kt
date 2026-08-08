package com.example.procrastinationgate

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

enum class FrictionType { TRACE, HOLD, BREATHE }

data class GateRule(
    val id: String = UUID.randomUUID().toString(),
    val targetPackage: String,
    val targetLabel: String,
    val enabled: Boolean = true,
    val triggerOnOpen: Boolean = true,
    val initialDelayMinutes: Int? = null,
    val repeatIntervalMinutes: Int? = null,
    val activeDays: Set<Int> = (1..7).toSet(),
    val startMinuteOfDay: Int = 0,
    val endMinuteOfDay: Int = 24 * 60,
    val frictionType: FrictionType = FrictionType.TRACE,
    val exitPackage: String? = null,
    val exitLabel: String? = null,
    val emergencyPauseMinutes: Int = 30,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("targetPackage", targetPackage)
        put("targetLabel", targetLabel)
        put("enabled", enabled)
        put("triggerOnOpen", triggerOnOpen)
        put("initialDelayMinutes", initialDelayMinutes ?: JSONObject.NULL)
        put("repeatIntervalMinutes", repeatIntervalMinutes ?: JSONObject.NULL)
        put("activeDays", JSONArray(activeDays.sorted()))
        put("startMinuteOfDay", startMinuteOfDay)
        put("endMinuteOfDay", endMinuteOfDay)
        put("frictionType", frictionType.name)
        put("exitPackage", exitPackage ?: JSONObject.NULL)
        put("exitLabel", exitLabel ?: JSONObject.NULL)
        put("emergencyPauseMinutes", emergencyPauseMinutes)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = GateRule(
            id = json.getString("id"),
            targetPackage = json.getString("targetPackage"),
            targetLabel = json.getString("targetLabel"),
            enabled = json.optBoolean("enabled", true),
            triggerOnOpen = json.optBoolean("triggerOnOpen", true),
            initialDelayMinutes = json.optIntOrNull("initialDelayMinutes"),
            repeatIntervalMinutes = json.optIntOrNull("repeatIntervalMinutes"),
            activeDays = json.optIntSet("activeDays").ifEmpty { (1..7).toSet() },
            startMinuteOfDay = json.optInt("startMinuteOfDay", 0),
            endMinuteOfDay = json.optInt("endMinuteOfDay", 24 * 60),
            frictionType = runCatching {
                FrictionType.valueOf(json.optString("frictionType", FrictionType.TRACE.name))
            }.getOrDefault(FrictionType.TRACE),
            exitPackage = json.optStringOrNull("exitPackage"),
            exitLabel = json.optStringOrNull("exitLabel"),
            emergencyPauseMinutes = json.optInt("emergencyPauseMinutes", 30),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (!has(key) || isNull(key)) null else getInt(key)

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        private fun JSONObject.optIntSet(key: String): Set<Int> {
            if (!has(key) || isNull(key)) return emptySet()
            val array = getJSONArray(key)
            return buildSet {
                for (index in 0 until array.length()) add(array.getInt(index))
            }
        }
    }
}
