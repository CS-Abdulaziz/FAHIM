package com.example.testandroidenv.agent.execution

import com.example.testandroidenv.agent.contract.ActionResultCode
import com.example.testandroidenv.agent.contract.BoundPlannerDecision
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.validation.LiveTargetState

data class ExecutionDispatchResult(
    val action: PlannerAction,
    val dispatched: Boolean,
    val resultCode: ActionResultCode?,
    val details: String
)

data class UiObservationResult(
    val observation: AgentObservation?,
    val changed: Boolean,
    val resultCode: ActionResultCode,
    val details: String
)

interface ActionExecutor {
    suspend fun execute(boundDecision: BoundPlannerDecision): ExecutionDispatchResult
}

interface TargetRuntimeInspector {
    suspend fun inspect(boundDecision: BoundPlannerDecision): LiveTargetState
}

interface UiObservationSource {
    suspend fun currentObservation(): AgentObservation?

    suspend fun awaitFreshExternalObservation(
        capturedAfterEpochMillis: Long
    ): AgentObservation? = currentObservation()

    suspend fun awaitPostActionObservation(
        source: AgentObservation,
        action: PlannerAction
    ): UiObservationResult
}

object AgentExecutionConfig {
    const val NORMAL_OBSERVATION_TIMEOUT_MS = 3_000L
    const val APP_LAUNCH_OBSERVATION_TIMEOUT_MS = 5_500L
    const val POST_EVENT_SETTLE_MS = 200L
    const val STABLE_CAPTURE_TIMEOUT_MS = 2_000L
}
