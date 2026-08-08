package com.example.procrastinationgate

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

enum class FrictionType {
    TRACE, HOLD, BREATHE, TAP_SEQUENCE, DRAG_SLIDER, PINCH, READ_PHRASE, COUNTDOWN
}

/** 单扇门的摩擦配置(触发时已选定摩擦方式与轨迹形状)。 */
data class GateFrictionConfig(
    val traceShape: TraceShape = TraceShape.S,
    val holdSeconds: Int = 3,
    val breatheCycles: Int = 1,
    val tapCount: Int = 4,
    val dragPercent: Int = 96,
    val pinchPercent: Int = 55,
    val countdownSeconds: Int = 3,
    val phrases: List<String> = DEFAULT_PHRASES
) {
    companion object {
        val DEFAULT_PHRASES = listOf(
            "喝口水，活动一下肩膀。",
            "现在打开它，是想要什么？",
            "先做一次深呼吸，再决定。"
        )
    }
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
    val holdSeconds: Int = 3,
    val breatheCycles: Int = 1,
    val tapCount: Int = 4,
    val dragPercent: Int = 96,
    val pinchPercent: Int = 55,
    val countdownSeconds: Int = 3,
    val phrases: List<String> = GateFrictionConfig.DEFAULT_PHRASES,
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
        put("holdSeconds", holdSeconds)
        put("breatheCycles", breatheCycles)
        put("tapCount", tapCount)
        put("dragPercent", dragPercent)
        put("pinchPercent", pinchPercent)
        put("countdownSeconds", countdownSeconds)
        put("phrases", JSONArray(phrases))
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
            holdSeconds = json.optInt("holdSeconds", 3),
            breatheCycles = json.optInt("breatheCycles", 1),
            tapCount = json.optInt("tapCount", 4),
            dragPercent = json.optInt("dragPercent", 96),
            pinchPercent = json.optInt("pinchPercent", 55),
            countdownSeconds = json.optInt("countdownSeconds", 3),
            phrases = json.optStringList("phrases").ifEmpty { GateFrictionConfig.DEFAULT_PHRASES },
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

        private fun JSONObject.optStringList(key: String): List<String> {
            if (!has(key) || isNull(key)) return emptyList()
            val array = getJSONArray(key)
            return buildList {
                for (index in 0 until array.length()) add(array.getString(index))
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
