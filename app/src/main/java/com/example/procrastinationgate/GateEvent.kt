package com.example.procrastinationgate

import org.json.JSONObject
import java.util.UUID

enum class TriggerType { OPEN, DURATION, REPEAT }
enum class GateResult { SHOWN, CONTINUE, LEAVE, BYPASS, MANUAL_LEAVE }

data class GateEvent(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val targetPackage: String,
    val targetLabel: String,
    val triggerType: TriggerType,
    val result: GateResult,
    val occurredAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("ruleId", ruleId)
        put("targetPackage", targetPackage)
        put("targetLabel", targetLabel)
        put("triggerType", triggerType.name)
        put("result", result.name)
        put("occurredAt", occurredAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = GateEvent(
            id = json.getString("id"),
            ruleId = json.getString("ruleId"),
            targetPackage = json.getString("targetPackage"),
            targetLabel = json.optString("targetLabel"),
            triggerType = TriggerType.valueOf(json.getString("triggerType")),
            result = GateResult.valueOf(json.getString("result")),
            occurredAt = json.getLong("occurredAt")
        )
    }
}
