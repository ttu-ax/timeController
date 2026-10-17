package com.example.procrastinationgate

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

enum class Difficulty { EASY, MEDIUM, HARD }

/** 规则的难度档:PROGRESSIVE = 随时间从低到高递增;CUSTOM = 手动勾选组合。 */
enum class RuleDifficulty { EASY, MEDIUM, HARD, PROGRESSIVE, CUSTOM }

enum class FrictionType(val difficulty: Difficulty) {
    // 简易组
    TRACE(Difficulty.EASY),
    HOLD(Difficulty.EASY),
    BREATHE(Difficulty.EASY),
    TAP_SEQUENCE(Difficulty.EASY),
    DRAG_SLIDER(Difficulty.EASY),
    SQUEEZE(Difficulty.EASY),
    READ_PHRASE(Difficulty.EASY),
    COUNTDOWN(Difficulty.EASY),
    // 中等组
    MEMORY_GRID(Difficulty.MEDIUM),
    BOLT_SEQUENCE(Difficulty.MEDIUM),
    KEY_PATH(Difficulty.MEDIUM),
    TARGET_SCAN(Difficulty.MEDIUM),
    KNOB_LOCK(Difficulty.MEDIUM),
    INTENT_CONFIRM(Difficulty.MEDIUM),
    // 困难组
    MULTI_STAGE(Difficulty.HARD),
    REPEAT_SCAN(Difficulty.HARD),
    REVERSE_MEMORY(Difficulty.HARD),
    PLATE_ASSEMBLY(Difficulty.HARD),
    ROUTE_PLAN(Difficulty.HARD),
    MEMORY_KNOB(Difficulty.HARD),
    REFLECT_WAIT(Difficulty.HARD);

    companion object {
        /** 旧版本持久化过的名字:改名或合并后仍要能读回来。 */
        private val LEGACY_NAMES = mapOf(
            "PINCH" to FrictionType.SQUEEZE,
            "GUIDED_SWIPE" to FrictionType.BOLT_SEQUENCE,
            "RULE_SWITCH" to FrictionType.REPEAT_SCAN
        )

        /** 按持久化名字解析摩擦方式,兼容改名与合并。 */
        fun fromPersistedName(name: String): FrictionType? =
            runCatching { valueOf(name) }.getOrNull() ?: LEGACY_NAMES[name]
    }
}

/** 单扇门的摩擦配置(触发时已选定摩擦方式与轨迹形状)。 */
data class GateFrictionConfig(
    val traceShape: TraceShape = TraceShape.S,
    val holdSeconds: Int = 3,
    val breatheCycles: Int = 1,
    val tapCount: Int = 4,
    val dragPercent: Int = 96,
    val squeezePercent: Int = 55,
    val countdownSeconds: Int = 3,
    val phrases: List<String> = DEFAULT_PHRASES,
    val memoryCount: Int = 4,
    val knobCount: Int = 2,
    val turnCount: Int = 2,
    val routeSize: Int = 3
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
    val squeezePercent: Int = 55,
    val countdownSeconds: Int = 3,
    val phrases: List<String> = GateFrictionConfig.DEFAULT_PHRASES,
    val memoryCount: Int = 4,
    val knobCount: Int = 2,
    val turnCount: Int = 2,
    val routeSize: Int = 3,
    val difficulty: Difficulty = Difficulty.EASY,
    val ruleDifficulty: RuleDifficulty = RuleDifficulty.EASY,
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
        put("squeezePercent", squeezePercent)
        put("countdownSeconds", countdownSeconds)
        put("phrases", JSONArray(phrases))
        put("memoryCount", memoryCount)
        put("knobCount", knobCount)
        put("turnCount", turnCount)
        put("routeSize", routeSize)
        put("difficulty", difficulty.name)
        put("ruleDifficulty", ruleDifficulty.name)
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
            // 旧数据里这一项叫 pinchPercent,读不到新键时回落到它
            squeezePercent = json.optInt("squeezePercent", json.optInt("pinchPercent", 55)),
            countdownSeconds = json.optInt("countdownSeconds", 3),
            phrases = json.optStringList("phrases").ifEmpty { GateFrictionConfig.DEFAULT_PHRASES },
            memoryCount = json.optInt("memoryCount", 4),
            knobCount = json.optInt("knobCount", 2),
            turnCount = json.optInt("turnCount", 2),
            routeSize = json.optInt("routeSize", 3),
            difficulty = runCatching {
                Difficulty.valueOf(json.optString("difficulty", Difficulty.EASY.name))
            }.getOrDefault(Difficulty.EASY),
            ruleDifficulty = runCatching {
                RuleDifficulty.valueOf(json.optString("ruleDifficulty", RuleDifficulty.EASY.name))
            }.getOrDefault(RuleDifficulty.EASY),
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

        /** 读取摩擦方式集合;兼容旧数据的单值 frictionType 字段与已改名的枚举。 */
        private fun JSONObject.optFrictionTypes(): Set<FrictionType> {
            if (has("frictionTypes") && !isNull("frictionTypes")) {
                val array = getJSONArray("frictionTypes")
                return buildSet {
                    for (index in 0 until array.length()) {
                        FrictionType.fromPersistedName(array.getString(index))?.let { add(it) }
                    }
                }.ifEmpty { setOf(FrictionType.TRACE) }
            }
            val legacy = FrictionType.fromPersistedName(
                optString("frictionType", FrictionType.TRACE.name)
            ) ?: FrictionType.TRACE
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
