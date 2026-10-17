package com.example.procrastinationgate

import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

enum class Difficulty { EASY, MEDIUM, HARD }

/** 规则的难度档:PROGRESSIVE = 随时间从低到高递增;CUSTOM = 手动勾选组合。 */
enum class RuleDifficulty { EASY, MEDIUM, HARD, PROGRESSIVE, CUSTOM }

/**
 * 摩擦方式。
 *
 * 用户可见的文案、难度档都挂在这里:新增一种摩擦只需要在枚举里加一行,
 * 各处的 when / 编辑器分组会自动覆盖(编辑器分组直接从 entries 派生),
 * 不会再出现"漏改一处、静默变成死门"的情况。
 */
enum class FrictionType(
    val difficulty: Difficulty,
    /** 规则摘要里的短标签。 */
    val title: String,
    /** 规则编辑器里的勾选文案。 */
    val label: String,
    /** 门上的动作说明(后面还会拼上"继续使用 N 分钟")。 */
    val actionText: String,
    /** 动作未完成时按钮上的提示。 */
    val lockedHint: String,
    /** 参数配置弹窗的标题。 */
    val configTitle: String,
    /** 需要把参数写进动作文案时的覆写。 */
    private val actionWithConfig: ((GateFrictionConfig) -> String)? = null
) {
    // 简易组
    TRACE(
        Difficulty.EASY, "描摹", "描摹轨迹 · 手眼配合",
        "沿着轨迹画出这道门", "沿轨迹画完即可推开", "描摹轨迹 · 选择随机出现的轨迹"
    ),
    HOLD(
        Difficulty.EASY, "长按", "长按 · 安静明确",
        "按住门槛", "完成长按后即可推开", "长按 · 按住秒数",
        { "按住门槛 ${it.holdSeconds} 秒" }
    ),
    BREATHE(
        Difficulty.EASY, "呼吸", "呼吸 · 跟随节奏",
        "跟随门槛完成一次呼吸", "完成呼吸后即可推开", "呼吸 · 完成次数",
        { if (it.breatheCycles > 1) "跟随门槛完成 ${it.breatheCycles} 次呼吸" else "跟随门槛完成一次呼吸" }
    ),
    TAP_SEQUENCE(
        Difficulty.EASY, "点按", "点按序列 · 依次点亮",
        "依次点亮圆点", "点亮全部圆点后即可推开", "点按序列 · 圆点数量",
        { "依次点亮 ${it.tapCount} 个圆点" }
    ),
    DRAG_SLIDER(
        Difficulty.EASY, "拖拽", "拖滑块 · 滑到尽头",
        "把门槛拖到尽头", "拖到底即可推开", "拖滑块 · 完成进度"
    ),
    SQUEEZE(
        Difficulty.EASY, "推合", "向内推合 · 单指合门缝",
        "把门板向中线推合", "门板合上后即可推开", "向内推合 · 合拢比例"
    ),
    FLIP(
        Difficulty.EASY, "翻转", "翻转手机 · 离开屏幕",
        "把手机屏幕朝下，再翻回来", "翻回来即可推开", "翻转手机 · 离屏动作"
    ),
    READ_PHRASE(
        Difficulty.EASY, "读句", "读一句话 · 读完再决定",
        "读完这句话，再决定", "读完后即可推开", "读一句话 · 语库内容"
    ),
    COUNTDOWN(
        Difficulty.EASY, "倒数", "倒计时 · 等一等",
        "等一等，再决定", "倒计时结束后即可推开", "倒计时 · 秒数",
        { "等 ${it.countdownSeconds} 秒，再决定" }
    ),
    // 中等组
    MEMORY_GRID(
        Difficulty.MEDIUM, "记忆", "记忆点阵 · 记住顺序点亮",
        "记住点的顺序并点亮", "点亮全部点后即可推开", "记忆点阵 · 圆点数量"
    ),
    MENTAL_MATH(
        Difficulty.MEDIUM, "心算", "心算 · 算完再决定",
        "算出答案，再决定", "算对即可推开", "心算 · 计算位数"
    ),
    COUNT(
        Difficulty.MEDIUM, "计数", "计数 · 数清楚再决定",
        "数清目标图形，选出数量", "数对即可推开", "计数 · 答案上限"
    ),
    BOLT_SEQUENCE(
        Difficulty.MEDIUM, "门栓", "三段式门栓 · 旋转+滑动+长按",
        "依次完成门栓动作", "完成门栓动作后即可推开", "三段式门栓"
    ),
    KEY_PATH(
        Difficulty.MEDIUM, "钥匙", "推钥匙 · 穿过门缝",
        "把钥匙拖到锁孔", "钥匙到位后即可推开", "推钥匙 · 通道转弯数"
    ),
    TARGET_SCAN(
        Difficulty.MEDIUM, "扫描", "目标扫描 · 按规则点击",
        "按提示点出目标图形", "点完目标后即可推开", "目标扫描"
    ),
    KNOB_LOCK(
        Difficulty.MEDIUM, "旋钮", "旋钮锁 · 对齐旋钮",
        "旋转旋钮对齐标记", "对齐旋钮后即可推开", "旋钮锁 · 旋钮数量"
    ),
    INTENT_CONFIRM(
        Difficulty.MEDIUM, "意图", "意图确认 · 想清楚再继续",
        "先想清楚为什么再继续", "完成确认后即可推开", "意图确认"
    ),
    // 困难组
    MULTI_STAGE(
        Difficulty.HARD, "多段", "多阶段门锁 · 2–3 个随机动作",
        "完成多阶段门锁", "完成全部阶段后即可推开", "多阶段门锁"
    ),
    REPEAT_SCAN(
        Difficulty.HARD, "连扫", "连续扫描 · 每阶段换一条规则",
        "每阶段换一条扫描规则", "完成全部扫描后即可推开", "连续扫描"
    ),
    REVERSE_MEMORY(
        Difficulty.HARD, "倒记", "反向记忆 · 倒序点亮",
        "记住顺序，倒序点亮", "完成记忆后即可推开", "反向记忆 · 圆点数量"
    ),
    PLATE_ASSEMBLY(
        Difficulty.HARD, "拼合", "拼合门板 · 拖拼片到槽",
        "对齐门板拼片", "拼合完成后即可推开", "拼合门板"
    ),
    ROUTE_PLAN(
        Difficulty.HARD, "路线", "路线规划 · 避障走到出口",
        "规划路线走到出口", "到达出口后即可推开", "路线规划 · 网格大小"
    ),
    MEMORY_KNOB(
        Difficulty.HARD, "记钮", "记忆+旋钮 · 记住后按序对齐",
        "记住符号，按序转旋钮", "完成记忆与旋钮后即可推开", "记忆+旋钮 · 符号数量"
    ),
    REFLECT_WAIT(
        Difficulty.HARD, "反思", "反思式等待 · 选择+呼吸+决定",
        "慢下来，诚实地决定", "完成反思后即可推开", "反思式等待"
    );

    /** 门上的动作说明,带参数的几种在此覆写默认文案。 */
    fun action(config: GateFrictionConfig): String =
        actionWithConfig?.invoke(config) ?: actionText

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
    val routeSize: Int = 3,
    /** 心算档位:1 一位数加减,2 一位数乘法,3 两位数乘一位数。 */
    val mathLevel: Int = 2,
    /** 计数题答案的上限:实际答案每轮在 2..上限 之间随机。 */
    val countTarget: Int = 5
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
    val mathLevel: Int = 2,
    val countTarget: Int = 5,
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
        put("mathLevel", mathLevel)
        put("countTarget", countTarget)
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
            mathLevel = json.optInt("mathLevel", 2),
            countTarget = json.optInt("countTarget", 5),
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
