package com.example.testandroidenv.agent.latency

import com.example.testandroidenv.SpokenFeedbackKind
import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.perception.AgentObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalLatencyTrackerTest {
    @Test
    fun recordsIndependentPlannerCallsAndPhaseBreakdown() {
        val clock = FakeClock(elapsed = 100, epoch = 5_000)
        val published = mutableListOf<GoalLatencySummary>()
        val logged = mutableListOf<GoalLatencySummary>()
        val tracker = GoalLatencyTracker(
            goalId = "goal-1",
            sttDurationMs = 80,
            clock = clock,
            publish = published::add,
            logger = LatencyDiagnosticLogger(logged::add)
        )
        tracker.recordObservation(50, observation(capture = 20, normalization = 7))
        tracker.plannerCallStarted(1, 10)
        tracker.recordPlannerTransport(1, PlannerTransportTiming(3, 40, null, 2))
        tracker.plannerCallFinished(1, 46, "continue", "tap", false)
        tracker.plannerCallStarted(2, 11)
        tracker.recordPlannerTransport(2, PlannerTransportTiming(4, 60, 35, 3))
        tracker.plannerCallFinished(2, 69, "task_completed", "none", false)
        tracker.recordValidation(8)
        tracker.recordExecution(12)
        tracker.markTtsRequested(SpokenFeedbackKind.ACKNOWLEDGEMENT)
        clock.elapsed += 15
        tracker.recordTtsStarted(SpokenFeedbackKind.ACKNOWLEDGEMENT)
        tracker.markTtsRequested(SpokenFeedbackKind.COMPLETION, isFinal = true)
        clock.elapsed += 25
        tracker.recordTtsStarted(SpokenFeedbackKind.COMPLETION, isFinal = true)
        clock.elapsed += 100

        val summary = tracker.finish("WaitingForFollowUp", "task_completed")

        assertEquals(2, summary.plannerCallCount)
        assertEquals(listOf(1, 2), summary.plannerCalls.map { it.loopStep })
        assertEquals(7L, summary.requestSerializationMs)
        assertEquals(100L, summary.networkRoundTripMs)
        assertEquals(35L, summary.serverDurationMs)
        assertEquals(5L, summary.responseParsingMs)
        assertEquals(20L, summary.snapshotCaptureMs)
        assertEquals(7L, summary.normalizationMs)
        assertEquals(23L, summary.uiStabilityWaitMs)
        assertEquals(15L, summary.acknowledgementStartDelayMs)
        assertEquals(25L, summary.completionStartDelayMs)
        assertEquals(140L, summary.totalGoalDurationMs)
        assertEquals(1, logged.size)
        assertTrue(published.isNotEmpty())
    }

    @Test
    fun finalizationIsIdempotentAndUnavailableServerTimingIsNotFabricated() {
        val clock = FakeClock(0, 1_000)
        val logged = mutableListOf<GoalLatencySummary>()
        val tracker = GoalLatencyTracker(
            goalId = "goal-safe",
            clock = clock,
            publish = {},
            logger = LatencyDiagnosticLogger(logged::add)
        )
        tracker.plannerCallStarted(1, 1)
        tracker.recordPlannerTransport(1, PlannerTransportTiming(1, 2, null, 1))
        tracker.plannerCallFinished(1, 4, null, null, true)
        clock.elapsed = 10

        val first = tracker.finish("failed", "planner_error")
        clock.elapsed = 50
        val second = tracker.finish("cancelled", "cancelled")

        assertEquals("failed", second.finalState)
        assertEquals(first.totalGoalDurationMs, second.totalGoalDurationMs)
        assertNull(second.serverDurationMs)
        assertEquals(1, logged.size)
        val logLine = second.privacySafeLogLine()
        assertFalse(logLine.contains("secret command"))
        assertFalse(logLine.contains("UI label"))
        assertTrue(logLine.contains("goalId=goal-safe"))
    }

    private fun observation(capture: Long, normalization: Long) = AgentObservation(
        currentApp = CurrentApp("pkg", "App", "app", "Screen"),
        uiState = CompactUiState(1, emptyList()),
        fingerprint = "fingerprint",
        capturedAtEpochMillis = 1,
        captureDurationMs = capture,
        normalizationDurationMs = normalization
    )

    private data class FakeClock(
        var elapsed: Long,
        var epoch: Long
    ) : LatencyClock {
        override fun elapsedRealtimeMillis(): Long = elapsed
        override fun epochTimeMillis(): Long = epoch
    }
}
