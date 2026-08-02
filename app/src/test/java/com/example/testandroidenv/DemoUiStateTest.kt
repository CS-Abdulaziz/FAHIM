package com.example.testandroidenv

import com.example.testandroidenv.agent.loop.AgentPhase
import com.example.testandroidenv.agent.loop.AgentRuntimeState
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.PlannerDecision
import com.example.testandroidenv.agent.contract.TaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoUiStateTest {
    @Test
    fun submissionRequiresCommandConnectedServiceAndFreshTree() {
        val now = 10_000L
        val ready = DemoUiState(
            recognizedCommand = "افتح المحادثة",
            accessibilityServiceEnabled = true,
            connectionState = AccessibilityConnectionState.CONNECTED,
            snapshot = snapshot(now),
            nowEpochMillis = now
        )

        assertTrue(ready.canSubmit)
        assertFalse(ready.copy(recognizedCommand = "").canSubmit)
        assertFalse(ready.copy(accessibilityServiceEnabled = false).canSubmit)
        assertFalse(
            ready.copy(connectionState = AccessibilityConnectionState.DISCONNECTED).canSubmit
        )
        assertFalse(
            ready.copy(nowEpochMillis = now + DemoConfig.SNAPSHOT_FRESHNESS_MS + 1).canSubmit
        )
        assertFalse(ready.copy(requestState = RequestUiState.Sending).canSubmit)
        assertTrue(ready.canStartAgent)
        assertFalse(ready.copy(snapshot = null).canSubmit)
        assertTrue(ready.copy(snapshot = null).canStartAgent)
        assertFalse(
            ready.copy(
                agentState = AgentRuntimeState.Running(
                    runId = "test",
                    step = 1,
                    phase = AgentPhase.OBSERVING
                )
            ).canStartAgent
        )
        assertFalse(ready.copy(requestState = RequestUiState.Sending).canStartAgent)
        assertFalse(
            ready.copy(
                agentState = AgentRuntimeState.Completed(
                    step = 1,
                    decision = PlannerDecision(
                        reason = "done",
                        status = TaskStatus.TASK_COMPLETED,
                        action = PlannerAction.NONE,
                        target = null,
                        targetId = null,
                        value = null,
                        message = "done"
                    ),
                    message = "done"
                )
            ).canStartAgent
        )
    }

    private fun snapshot(capturedAt: Long): AccessibilitySnapshot {
        val node = AccessibilityNodeSnapshot(
            nodeId = "node_0",
            parentId = null,
            depth = 0,
            index = 0,
            className = "android.widget.FrameLayout",
            text = null,
            contentDescription = null,
            viewId = null,
            clickable = false,
            longClickable = false,
            enabled = true,
            focusable = false,
            focused = false,
            scrollable = false,
            editable = false,
            checkable = false,
            checked = false,
            selected = false,
            password = false,
            bounds = NodeBounds(0, 0, 100, 100),
            supportedActions = emptyList()
        )
        return AccessibilitySnapshot(
            packageName = "com.example.target",
            windowTitle = null,
            capturedAtEpochMillis = capturedAt,
            serializedTree = "node_id=\"node_0\"",
            nodes = listOf(node),
            treeTruncated = false
        )
    }
}
