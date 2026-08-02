package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.loop.PlannerRequestSnapshotBinding
import com.example.testandroidenv.agent.loop.PlannerResponseOwnershipGuard
import com.example.testandroidenv.agent.loop.ResponseOwnership
import com.example.testandroidenv.agent.loop.ResponseOwnershipReason
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.TargetRegistryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerResponseOwnershipGuardTest {
    private val binding = PlannerRequestSnapshotBinding(
        requestId = "request-1",
        observation = AgentObservation(
            currentApp = CurrentApp("com.whatsapp", "WhatsApp"),
            uiState = CompactUiState(1, emptyList()),
            fingerprint = "fingerprint",
            capturedAtEpochMillis = 1
        ),
        registry = TargetRegistryView(1, "fingerprint", emptyMap()),
        runId = "run-1",
        activeGoalId = "goal-1",
        loopStep = 2
    )

    @Test
    fun matchingRequestGoalRunAndStepOwnTheResponse() {
        assertEquals(
            ResponseOwnership.Owned,
            evaluate()
        )
    }

    @Test
    fun obsoleteRequestOrGoalIsRejected() {
        assertObsolete(
            ResponseOwnershipReason.REQUEST_SUPERSEDED,
            evaluate(activeRequestId = "request-2")
        )
        assertObsolete(
            ResponseOwnershipReason.GOAL_SUPERSEDED,
            evaluate(activeGoalId = "goal-2")
        )
    }

    @Test
    fun obsoleteStepAndCancellationAreRejected() {
        assertObsolete(
            ResponseOwnershipReason.LOOP_STEP_SUPERSEDED,
            evaluate(activeLoopStep = 3)
        )
        assertObsolete(
            ResponseOwnershipReason.RUN_CANCELLED,
            evaluate(runIsActive = false)
        )
    }

    private fun evaluate(
        activeRequestId: String? = "request-1",
        activeRunId: String? = "run-1",
        activeGoalId: String? = "goal-1",
        activeLoopStep: Int = 2,
        runIsActive: Boolean = true
    ) = PlannerResponseOwnershipGuard.evaluate(
        binding = binding,
        activeRequestId = activeRequestId,
        activeRunId = activeRunId,
        activeGoalId = activeGoalId,
        activeLoopStep = activeLoopStep,
        runIsActive = runIsActive
    )

    private fun assertObsolete(
        reason: ResponseOwnershipReason,
        ownership: ResponseOwnership
    ) {
        assertTrue(ownership is ResponseOwnership.Obsolete)
        assertEquals(reason, (ownership as ResponseOwnership.Obsolete).reason)
    }
}
