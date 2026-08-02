package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.ActionResult
import com.example.testandroidenv.agent.contract.ActionResultCode
import com.example.testandroidenv.agent.contract.ActionHistoryRecord
import com.example.testandroidenv.agent.contract.CompactUiElement
import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.PlannerContractJson
import com.example.testandroidenv.agent.contract.PlannerDecision
import com.example.testandroidenv.agent.contract.PlannerRequest
import com.example.testandroidenv.agent.contract.TaskStatus
import com.example.testandroidenv.agent.contract.UiRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContractTest {
    @Test
    fun enumWireValuesExactlyMatchContract() {
        assertEquals(
            setOf(
                "continue",
                "task_completed",
                "needs_user_input",
                "needs_confirmation",
                "failed",
                "end_session"
            ),
            TaskStatus.entries.map { it.wireValue }.toSet()
        )
        assertEquals(
            setOf(
                "open_app",
                "tap",
                "type",
                "scroll",
                "back",
                "read_aloud",
                "ask_user",
                "confirm_with_user",
                "none"
            ),
            PlannerAction.entries.map { it.wireValue }.toSet()
        )
    }

    @Test
    fun decisionJsonHasExactlySevenFieldsAndExplicitNulls() {
        val json = PlannerContractJson.decision(
            PlannerDecision(
                reason = "Current app is not WhatsApp.",
                status = TaskStatus.CONTINUE,
                action = PlannerAction.OPEN_APP,
                target = "WhatsApp",
                targetId = null,
                value = null,
                message = null
            )
        )

        assertEquals(
            setOf("reason", "status", "action", "target", "target_id", "value", "message"),
            json.keys().asSequence().toSet()
        )
        assertTrue(json.isNull("target_id"))
        assertTrue(json.isNull("value"))
        assertTrue(json.isNull("message"))
    }

    @Test
    fun requestJsonContainsExactlySevenLogicalInputs() {
        val request = PlannerRequest(
            userInput = null,
            activeGoal = "افتح محادثة أحمد محمد",
            currentApp = CurrentApp("com.whatsapp", "WhatsApp"),
            uiState = CompactUiState(
                screenVersion = 12,
                elements = listOf(
                    CompactUiElement(
                        targetId = "e017",
                        role = UiRole.LIST_ITEM,
                        label = "أحمد محمد",
                        actions = listOf(PlannerAction.TAP)
                    )
                )
            ),
            conversationContext = emptyList(),
            lastActionResult = ActionResult(
                action = PlannerAction.OPEN_APP,
                success = true,
                resultCode = ActionResultCode.ACTION_SUCCEEDED,
                details = "Foreground package changed."
            ),
            actionHistory = emptyList()
        )
        val json: JSONObject = PlannerContractJson.request(request)

        assertEquals(
            setOf(
                "user_input",
                "active_goal",
                "current_app",
                "ui_state",
                "conversation_context",
                "last_action_result",
                "action_history"
            ),
            json.keys().asSequence().toSet()
        )
        assertTrue(json.isNull("user_input"))
        assertFalse(json.has("user_goal"))
        assertFalse(json.has("current_screen"))
        assertEquals(
            setOf("app_id", "package_name", "display_name", "screen_name"),
            json.getJSONObject("current_app").keys().asSequence().toSet()
        )
        assertEquals(JSONObject::class.java, json.get("last_action_result")::class.java)
        assertEquals("e017", json.getJSONObject("ui_state")
            .getJSONArray("elements").getJSONObject(0).getString("target_id"))
        assertEquals(
            "أحمد محمد",
            json.getJSONObject("ui_state")
                .getJSONArray("elements").getJSONObject(0).getString("text")
        )
        assertEquals(
            setOf(
                "target_id",
                "text",
                "content_description",
                "role",
                "clickable",
                "enabled"
            ),
            json.getJSONObject("ui_state")
                .getJSONArray("elements").getJSONObject(0)
                .keys().asSequence().toSet()
        )
    }

    @Test
    fun initialRequestSerializesEmptyObjectAndCollections() {
        val json = PlannerContractJson.request(
            PlannerRequest(
                userInput = "افتح واتساب",
                activeGoal = "افتح واتساب",
                currentApp = CurrentApp(
                    packageName = "device.launcher",
                    displayName = "Home",
                    appId = "android_launcher",
                    screenName = "Home Screen"
                ),
                uiState = CompactUiState(1, emptyList()),
                conversationContext = emptyList(),
                lastActionResult = null,
                actionHistory = emptyList()
            )
        )

        assertEquals(0, json.getJSONObject("last_action_result").length())
        assertEquals(0, json.getJSONArray("conversation_context").length())
        assertEquals(0, json.getJSONArray("action_history").length())
        assertEquals("افتح واتساب", json.getString("user_input"))
    }

    @Test
    fun actionResultAndHistoryUseOnlyTheProvenCompactFields() {
        val json = PlannerContractJson.request(
            PlannerRequest(
                userInput = null,
                activeGoal = "افتح محادثة خالد",
                currentApp = CurrentApp("com.whatsapp", "WhatsApp"),
                uiState = CompactUiState(5, emptyList()),
                conversationContext = emptyList(),
                lastActionResult = ActionResult(
                    action = PlannerAction.TAP,
                    success = false,
                    resultCode = ActionResultCode.TARGET_NOT_FOUND,
                    details = "The target disappeared before execution",
                    targetId = "s4_e002_aabbcc"
                ),
                actionHistory = listOf(
                    ActionHistoryRecord(
                        action = PlannerAction.OPEN_APP,
                        target = "WhatsApp",
                        targetId = null,
                        value = null,
                        success = true,
                        resultCode = ActionResultCode.ACTION_SUCCEEDED,
                        screenVersion = 4,
                        screenFingerprint = "not-serialized"
                    )
                )
            )
        )

        assertEquals(
            setOf("action", "target", "target_id", "success", "message"),
            json.getJSONObject("last_action_result").keys().asSequence().toSet()
        )
        assertEquals(
            setOf("action", "target", "target_id", "success"),
            json.getJSONArray("action_history").getJSONObject(0)
                .keys().asSequence().toSet()
        )
    }
}
