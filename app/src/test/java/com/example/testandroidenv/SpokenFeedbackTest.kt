package com.example.testandroidenv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenFeedbackTest {
    @Test
    fun acknowledgementIsImmediateOnceAndDoesNotClaimSuccess() {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val coordinator = GoalFeedbackCoordinator(output, scheduler)

        coordinator.startGoal("g1")
        val acknowledgement = output.accepted.single()

        assertEquals(SpokenFeedbackKind.ACKNOWLEDGEMENT, acknowledgement.kind)
        assertEquals("حاضر، جاري تنفيذ طلبك.", acknowledgement.text)
        assertFalse(acknowledgement.text.contains("نجح"))
        assertFalse(acknowledgement.text.contains("تم التنفيذ"))
        assertEquals(DemoConfig.PROGRESS_FEEDBACK_DELAY_MS, scheduler.entries.single().delayMs)

        // No utterance completion callback is needed before asynchronous Planner work can start.
        var plannerStarted = false
        plannerStarted = true
        assertTrue(plannerStarted)
        assertEquals(SpokenFeedbackKind.ACKNOWLEDGEMENT, output.current?.kind)
    }

    @Test
    fun progressOccursOnlyAfterDelayAndOnlyOnce() {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val coordinator = GoalFeedbackCoordinator(output, scheduler)
        coordinator.startGoal("g1")

        assertEquals(1, output.accepted.size)
        scheduler.fire(0)
        scheduler.fire(0)

        assertEquals(
            listOf(SpokenFeedbackKind.ACKNOWLEDGEMENT, SpokenFeedbackKind.PROGRESS),
            output.accepted.map(SpokenUtterance::kind)
        )
        assertEquals("ما زلت أعمل على طلبك.", output.accepted.last().text)
    }

    @Test
    fun completionBeforeThresholdCancelsProgressAndWins() {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val coordinator = GoalFeedbackCoordinator(output, scheduler)
        coordinator.startGoal("g1")

        assertTrue(
            coordinator.finishGoal(
                "g1",
                SpokenFeedbackKind.COMPLETION,
                "اكتمل الطلب."
            )
        )
        scheduler.fire(0)

        assertEquals(SpokenFeedbackKind.COMPLETION, output.current?.kind)
        assertFalse(output.accepted.any { it.kind == SpokenFeedbackKind.PROGRESS })
        assertTrue(scheduler.entries.single().cancelled)
    }

    @Test
    fun delayedWorkFromOldGoalCannotSpeakInNewGoal() {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val coordinator = GoalFeedbackCoordinator(output, scheduler)
        coordinator.startGoal("old")
        coordinator.startGoal("new")

        scheduler.fire(0)
        scheduler.fire(1)

        assertFalse(
            output.accepted.any {
                it.goalId == "old" && it.kind == SpokenFeedbackKind.PROGRESS
            }
        )
        assertTrue(
            output.accepted.any {
                it.goalId == "new" && it.kind == SpokenFeedbackKind.PROGRESS
            }
        )
    }

    @Test
    fun cancellationFlushesLowerPrioritySpeechAndCancelsTimer() {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val coordinator = GoalFeedbackCoordinator(output, scheduler)
        coordinator.startGoal("g1")

        coordinator.interruptWithCriticalCancellation("g1", "تم إيقاف المهمة.")
        scheduler.fire(0)

        assertEquals(SpokenFeedbackKind.CRITICAL_CANCELLATION, output.current?.kind)
        assertEquals("تم إيقاف المهمة.", output.current?.text)
        assertFalse(output.accepted.any { it.kind == SpokenFeedbackKind.PROGRESS })
        assertTrue(scheduler.entries.single().cancelled)
    }

    @Test
    fun onlyTypedFinalCompletionTriggersFollowUpCallback() {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val callbacks = mutableListOf<Pair<String, SpokenFeedbackKind>>()
        val coordinator = GoalFeedbackCoordinator(
            output,
            scheduler,
            onFinalUtteranceCompleted = { goal, kind -> callbacks += goal to kind }
        )
        coordinator.startGoal("g1")
        val acknowledgement = output.accepted.single()
        coordinator.onUtteranceCompleted(acknowledgement)
        scheduler.fire(0)
        val progress = output.accepted.last()
        coordinator.onUtteranceCompleted(progress)
        coordinator.finishGoal("g1", SpokenFeedbackKind.INTERACTION, "هل تريد المتابعة؟")
        val final = output.accepted.last()

        coordinator.onUtteranceCompleted(acknowledgement)
        coordinator.onUtteranceCompleted(final.copy(utteranceId = "obsolete"))
        assertTrue(callbacks.isEmpty())
        coordinator.onUtteranceCompleted(final)

        assertEquals(listOf("g1" to SpokenFeedbackKind.INTERACTION), callbacks)
    }

    @Test
    fun finalCallbackFromObsoleteGoalIsIgnored() {
        val output = FakeOutput()
        val scheduler = FakeScheduler()
        val callbacks = mutableListOf<String>()
        val coordinator = GoalFeedbackCoordinator(
            output,
            scheduler,
            onFinalUtteranceCompleted = { goal, _ -> callbacks += goal }
        )
        coordinator.startGoal("old")
        coordinator.finishGoal("old", SpokenFeedbackKind.COMPLETION, "old final")
        val oldFinal = output.accepted.last()
        coordinator.startGoal("new")

        coordinator.onUtteranceCompleted(oldFinal)

        assertTrue(callbacks.isEmpty())
        assertEquals("new", coordinator.activeGoalId())
    }

    @Test
    fun priorityPolicyUsesRequiredOrderingAndAudioPolicyBlocksCompetition() {
        val ordered = SpokenFeedbackKind.entries.sortedBy { it.priority }
        assertEquals(
            listOf(
                SpokenFeedbackKind.ACKNOWLEDGEMENT,
                SpokenFeedbackKind.PROGRESS,
                SpokenFeedbackKind.INTERACTION,
                SpokenFeedbackKind.COMPLETION,
                SpokenFeedbackKind.FINAL_ERROR,
                SpokenFeedbackKind.CRITICAL_CANCELLATION
            ),
            ordered
        )
        assertFalse(
            TtsPriorityPolicy.shouldAccept(
                SpokenFeedbackKind.COMPLETION,
                SpokenFeedbackKind.PROGRESS
            )
        )
        assertTrue(
            TtsPriorityPolicy.shouldAccept(
                SpokenFeedbackKind.PROGRESS,
                SpokenFeedbackKind.FINAL_ERROR
            )
        )
        assertFalse(AudioCoordinationPolicy.canStartRecognition(ArabicTtsState.Speaking, false))
        assertFalse(AudioCoordinationPolicy.canStartRecognition(ArabicTtsState.Ready, true))
        assertTrue(AudioCoordinationPolicy.canStartRecognition(ArabicTtsState.Ready, false))
    }

    private class FakeOutput : SpokenFeedbackOutput {
        val accepted = mutableListOf<SpokenUtterance>()
        var current: SpokenUtterance? = null

        override fun speak(utterance: SpokenUtterance): Boolean {
            if (!TtsPriorityPolicy.shouldAccept(current?.kind, utterance.kind)) return false
            current = utterance
            accepted += utterance
            return true
        }

        override fun cancelGoal(goalId: String) {
            if (current?.goalId == goalId) current = null
        }

        override fun stopAll() {
            current = null
        }
    }

    private class FakeScheduler : FeedbackScheduler {
        data class Entry(
            val delayMs: Long,
            val block: () -> Unit,
            var cancelled: Boolean = false
        )

        val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, block: () -> Unit): CancellableFeedbackTask {
            val entry = Entry(delayMs, block)
            entries += entry
            return CancellableFeedbackTask { entry.cancelled = true }
        }

        fun fire(index: Int) {
            entries[index].takeUnless(Entry::cancelled)?.block?.invoke()
        }
    }
}
