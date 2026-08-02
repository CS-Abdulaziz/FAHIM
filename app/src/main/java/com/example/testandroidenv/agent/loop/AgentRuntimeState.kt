package com.example.testandroidenv.agent.loop

import com.example.testandroidenv.agent.contract.ActionResult
import com.example.testandroidenv.agent.contract.PlannerDecision

enum class AgentPhase {
    OBSERVING,
    PLANNING,
    VALIDATING,
    EXECUTING,
    WAITING_FOR_UI
}

enum class AgentStopCode {
    ACCESSIBILITY_UNAVAILABLE,
    EMPTY_UI_STATE,
    MAX_STEPS_REACHED,
    REPEATED_ACTION_LOOP,
    INVALID_DECISION,
    PLANNER_ERROR,
    EXECUTOR_ERROR,
    OBSERVATION_ERROR,
    CANCELLED
}

sealed interface AgentRuntimeState {
    val step: Int

    data object Idle : AgentRuntimeState {
        override val step: Int = 0
    }

    data class Running(
        val runId: String,
        override val step: Int,
        val phase: AgentPhase,
        val decision: PlannerDecision? = null,
        val lastActionResult: ActionResult? = null,
        val detail: String? = null
    ) : AgentRuntimeState

    data class Completed(
        override val step: Int,
        val decision: PlannerDecision,
        val message: String,
        val lastActionResult: ActionResult? = null
    ) : AgentRuntimeState

    data class WaitingForFollowUp(
        override val step: Int,
        val decision: PlannerDecision,
        val message: String,
        val lastActionResult: ActionResult? = null
    ) : AgentRuntimeState

    data class WaitingForUserInput(
        override val step: Int,
        val decision: PlannerDecision,
        val message: String,
        val lastActionResult: ActionResult? = null
    ) : AgentRuntimeState

    data class WaitingForConfirmation(
        override val step: Int,
        val decision: PlannerDecision,
        val message: String,
        val lastActionResult: ActionResult? = null
    ) : AgentRuntimeState

    data class SessionEnded(
        override val step: Int,
        val decision: PlannerDecision,
        val message: String,
        val lastActionResult: ActionResult? = null
    ) : AgentRuntimeState

    data class Failed(
        override val step: Int,
        val code: AgentStopCode,
        val message: String,
        val decision: PlannerDecision? = null,
        val lastActionResult: ActionResult? = null
    ) : AgentRuntimeState

    data class Cancelled(
        override val step: Int,
        val message: String = "Agent run cancelled."
    ) : AgentRuntimeState
}
