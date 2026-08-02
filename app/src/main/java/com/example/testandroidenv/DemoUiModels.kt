package com.example.testandroidenv

import com.example.testandroidenv.agent.loop.AgentRuntimeState
import com.example.testandroidenv.agent.latency.GoalLatencySummary
import com.example.testandroidenv.agent.perception.AgentObservation

data class PlannerDecision(
    val reason: String,
    val action: String,
    val target: String,
    val isMock: Boolean = false
)

internal fun appendDecisionHistory(
    history: List<PlannerDecision>,
    decision: PlannerDecision
): List<PlannerDecision> {
    return (history + decision).takeLast(DemoConfig.MAX_ACTION_HISTORY)
}

sealed interface RequestUiState {
    data object Idle : RequestUiState
    data object Sending : RequestUiState
    data class Success(val isMock: Boolean) : RequestUiState
    data class Error(val message: String) : RequestUiState
}

data class DemoUiState(
    val speechState: SpeechRecognitionState = SpeechRecognitionState.Ready,
    val recognizedCommand: String = "",
    val accessibilityServiceEnabled: Boolean = false,
    val connectionState: AccessibilityConnectionState =
        AccessibilityConnectionState.DISCONNECTED,
    val snapshot: AccessibilitySnapshot? = null,
    val nowEpochMillis: Long = System.currentTimeMillis(),
    val requestState: RequestUiState = RequestUiState.Idle,
    val decision: PlannerDecision? = null,
    val history: List<PlannerDecision> = emptyList(),
    val ttsState: ArabicTtsState = ArabicTtsState.Initializing,
    val agentState: AgentRuntimeState = AgentRuntimeState.Idle,
    val agentObservation: AgentObservation? = null,
    val latencySummary: GoalLatencySummary? = null
) {
    val hasValidTree: Boolean
        get() = snapshot?.let {
            it.serializedTree.isNotBlank() && it.nodeCount > 0 && it.isFresh(nowEpochMillis)
        } == true

    val canSubmit: Boolean
        get() = recognizedCommand.isNotBlank() &&
            accessibilityServiceEnabled &&
            connectionState == AccessibilityConnectionState.CONNECTED &&
            hasValidTree &&
            requestState !is RequestUiState.Sending &&
            !isAgentRunning

    val isAgentRunning: Boolean
        get() = agentState is AgentRuntimeState.Running ||
            agentState is AgentRuntimeState.Completed

    val canStartAgent: Boolean
        get() = recognizedCommand.isNotBlank() &&
            accessibilityServiceEnabled &&
            connectionState == AccessibilityConnectionState.CONNECTED &&
            requestState !is RequestUiState.Sending &&
            !isAgentRunning

    val pipelineStatus: String
        get() = when {
            agentState is AgentRuntimeState.Running -> "AgentRunning"
            speechState is SpeechRecognitionState.Listening -> "Listening"
            speechState is SpeechRecognitionState.Recognizing -> "Recognizing"
            requestState is RequestUiState.Sending -> "Sending"
            requestState is RequestUiState.Success -> "Success"
            requestState is RequestUiState.Error -> "Error"
            !accessibilityServiceEnabled || !hasValidTree -> "WaitingForAccessibilityTree"
            canSubmit -> "ReadyToSend"
            else -> "Idle"
        }
}
