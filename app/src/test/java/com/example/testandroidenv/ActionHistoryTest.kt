package com.example.testandroidenv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionHistoryTest {
    @Test
    fun appendsStructuredDecisionsAndCapsSessionGrowth() {
        var history = emptyList<PlannerDecision>()
        repeat(DemoConfig.MAX_ACTION_HISTORY + 3) { index ->
            history = appendDecisionHistory(
                history,
                PlannerDecision(
                    reason = "Reason $index",
                    action = "tap",
                    target = "node_$index"
                )
            )
        }

        assertEquals(DemoConfig.MAX_ACTION_HISTORY, history.size)
        assertEquals("node_3", history.first().target)
        assertEquals(
            "node_${DemoConfig.MAX_ACTION_HISTORY + 2}",
            history.last().target
        )
        assertTrue(history.all { it.reason.isNotBlank() && it.action.isNotBlank() })
    }
}
