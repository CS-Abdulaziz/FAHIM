package com.example.testandroidenv

import com.example.testandroidenv.agent.loop.AgentRuntimeState
import com.example.testandroidenv.agent.loop.AgentStopCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArabicAgentErrorMessagesTest {
    @Test
    fun infrastructureFailuresAreSpokenInArabicWithoutTechnicalDetails() {
        val planner = ArabicAgentErrorMessages.forFailure(
            AgentRuntimeState.Failed(1, AgentStopCode.PLANNER_ERROR, "HTTP 500 token=secret")
        )
        val snapshot = ArabicAgentErrorMessages.forFailure(
            AgentRuntimeState.Failed(1, AgentStopCode.OBSERVATION_ERROR, "TimeoutException")
        )
        val stale = ArabicAgentErrorMessages.forFailure(
            AgentRuntimeState.Failed(1, AgentStopCode.INVALID_DECISION, "STALE_SNAPSHOT")
        )

        assertEquals(ArabicAgentErrorMessages.PLANNER_UNAVAILABLE, planner)
        assertEquals(ArabicAgentErrorMessages.SCREEN_UNAVAILABLE, snapshot)
        assertEquals(ArabicAgentErrorMessages.SCREEN_CHANGED, stale)
        assertFalse(planner.contains("500"))
        assertFalse(snapshot.contains("Exception"))
    }
}
