package com.example.procrastinationgate

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

enum class FrictionType {
    TRACE, HOLD, BREATHE, TAP_SEQUENCE, DRAG_SLIDER, PINCH, READ_PHRASE, COUNTDOWN
}

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
    val frictionTypes: Set<FrictionType> = setOf(FrictionType.TRACE),
    val traceShapes: Set<TraceShape> = setOf(TraceShape.S),
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
        put("frictionTypes", JSONArray(frictionTypes.sortedBy { it.ordinal }.map { it.name }))
        put("traceShapes", JSONArray(traceShapes.sortedBy { it.ordinal }.map { it.name }))
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
            frictionTypes = runCatching { json.optFrictionTypes() }
                .getOrDefault(setOf(FrictionType.TRACE)),
            traceShapes = runCatching { json.optTraceShapes() }
                .getOrDefault(setOf(TraceShape.S)),
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

        /** 读取摩擦方式集合;兼容旧数据的单值 frictionType 字段。 */
        private fun JSONObject.optFrictionTypes(): Set<FrictionType> {
            if (has("frictionTypes") && !isNull("frictionTypes")) {
                val array = getJSONArray("frictionTypes")
                return buildSet {
                    for (index in 0 until array.length()) {
                        runCatching { add(FrictionType.valueOf(array.getString(index))) }
                    }
                }.ifEmpty { setOf(FrictionType.TRACE) }
            }
            val legacy = runCatching {
                FrictionType.valueOf(optString("frictionType", FrictionType.TRACE.name))
            }.getOrDefault(FrictionType.TRACE)
            return setOf(legacy)
        }

        /** 读取形状集合;兼容旧数据的单值 traceShape 字段。 */
        private fun JSONObject.optTraceShapes(): Set<TraceShape> {
            if (has("traceShapes") && !isNull("traceShapes")) {
                val array = getJSONArray("traceShapes")
                return buildSet {
                    for (index in 0 until array.length()) {
                        runCatching { add(TraceShape.valueOf(array.getString(index))) }
                    }
                }.ifEmpty { setOf(TraceShape.S) }
            }
            val legacy = runCatching {
                TraceShape.valueOf(optString("traceShape", TraceShape.S.name))
            }.getOrDefault(TraceShape.S)
            return setOf(legacy)
        }
    }
}
