package com.example.testandroidenv.agent.contract

import org.json.JSONArray
import org.json.JSONObject

/** Explicit snake_case boundary mapping used by contract fixtures and a future remote Planner. */
object PlannerContractJson {
    fun decision(decision: PlannerDecision): JSONObject = JSONObject()
        .putNullable("reason", decision.reason)
        .put("status", decision.status.wireValue)
        .put("action", decision.action.wireValue)
        .putNullable("target", decision.target)
        .putNullable("target_id", decision.targetId)
        .putNullable("value", decision.value)
        .putNullable("message", decision.message)

    fun request(request: PlannerRequest): JSONObject = JSONObject()
        .putNullable("user_input", request.userInput)
        .put("active_goal", request.activeGoal)
        .put("current_app", currentApp(request.currentApp))
        .put("ui_state", uiState(request.uiState))
        .put(
            "conversation_context",
            JSONArray().apply {
                request.conversationContext.forEach { entry ->
                    put(
                        JSONObject()
                            .put("role", entry.role)
                            .put("message", entry.message)
                    )
                }
            }
        )
        .put(
            "last_action_result",
            request.lastActionResult?.let(::actionResult) ?: JSONObject()
        )
        .put(
            "action_history",
            JSONArray().apply {
                request.actionHistory.forEach { put(actionHistory(it)) }
            }
        )

    private fun currentApp(app: CurrentApp): JSONObject = JSONObject()
        .put("app_id", app.appId)
        .putNullable("package_name", app.packageName)
        .putNullable("display_name", app.displayName)
        .putNullable("screen_name", app.screenName)

    private fun uiState(state: CompactUiState): JSONObject = JSONObject()
        .put("screen_version", state.screenVersion)
        .put(
            "elements",
            JSONArray().apply {
                state.elements.forEach { element ->
                    put(
                        JSONObject()
                            .put("target_id", element.targetId)
                            .put("text", element.label)
                            .putNullable(
                                "content_description",
                                element.contentDescription
                            )
                            .put("role", element.role.wireValue)
                            .put("clickable", PlannerAction.TAP in element.actions)
                            .put("enabled", element.enabled)
                    )
                }
            }
        )

    private fun actionResult(result: ActionResult): JSONObject = JSONObject()
        .put("action", result.action.wireValue)
        .putNullable("target", result.target)
        .putNullable("target_id", result.targetId)
        .put("success", result.success)
        .put("message", result.details)

    private fun actionHistory(record: ActionHistoryRecord): JSONObject = JSONObject()
        .put("action", record.action.wireValue)
        .putNullable("target", record.target)
        .putNullable("target_id", record.targetId)
        .put("success", record.success)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
    }
}
