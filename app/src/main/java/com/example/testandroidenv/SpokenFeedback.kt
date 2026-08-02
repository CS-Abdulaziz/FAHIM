package com.example.testandroidenv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class SpokenFeedbackKind(val priority: Int) {
    ACKNOWLEDGEMENT(1),
    PROGRESS(2),
    INTERACTION(3),
    COMPLETION(4),
    FINAL_ERROR(5),
    CRITICAL_CANCELLATION(6)
}

data class SpokenUtterance(
    val goalId: String,
    val kind: SpokenFeedbackKind,
    val text: String,
    val utteranceId: String,
    val isFinal: Boolean = false
)

fun interface CancellableFeedbackTask {
    fun cancel()
}

fun interface FeedbackScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): CancellableFeedbackTask
}

class CoroutineFeedbackScheduler(
    private val scope: CoroutineScope
) : FeedbackScheduler {
    override fun schedule(
        delayMs: Long,
        block: () -> Unit
    ): CancellableFeedbackTask {
        val job: Job = scope.launch {
            delay(delayMs)
            block()
        }
        return CancellableFeedbackTask(job::cancel)
    }
}

interface SpokenFeedbackOutput {
    fun speak(utterance: SpokenUtterance): Boolean
    fun cancelGoal(goalId: String)
    fun stopAll()
}

internal object TtsPriorityPolicy {
    fun shouldAccept(
        current: SpokenFeedbackKind?,
        incoming: SpokenFeedbackKind
    ): Boolean = current == null || incoming.priority >= current.priority
}

/** Owns one acknowledgement and at most one delayed progress utterance per goal. */
class GoalFeedbackCoordinator(
    private val output: SpokenFeedbackOutput,
    private val scheduler: FeedbackScheduler,
    private val progressDelayMs: Long = DemoConfig.PROGRESS_FEEDBACK_DELAY_MS,
    private val onUtteranceRequested: (SpokenUtterance) -> Unit = {},
    private val onFinalUtteranceCompleted: (String, SpokenFeedbackKind) -> Unit = { _, _ -> }
) {
    private data class ActiveGoal(
        val goalId: String,
        val generation: Long,
        var terminal: Boolean = false,
        var progressRequested: Boolean = false,
        var finalUtteranceId: String? = null,
        var progressTask: CancellableFeedbackTask? = null
    )

    private val lock = Any()
    private val generationCounter = AtomicLong()
    private val utteranceCounter = AtomicLong()
    private var active: ActiveGoal? = null

    fun startGoal(goalId: String) {
        require(goalId.isNotBlank())
        val goal = synchronized(lock) {
            active?.let { previous ->
                previous.progressTask?.cancel()
                output.cancelGoal(previous.goalId)
            }
            ActiveGoal(goalId, generationCounter.incrementAndGet()).also { active = it }
        }
        dispatch(utterance(goal, SpokenFeedbackKind.ACKNOWLEDGEMENT, ACKNOWLEDGEMENT))
        val task = scheduler.schedule(progressDelayMs) {
            requestProgress(goal.goalId, goal.generation)
        }
        synchronized(lock) {
            if (active === goal && !goal.terminal) {
                goal.progressTask = task
            } else {
                task.cancel()
            }
        }
    }

    fun finishGoal(
        goalId: String,
        kind: SpokenFeedbackKind,
        text: String
    ): Boolean {
        require(kind.priority >= SpokenFeedbackKind.INTERACTION.priority)
        val utterance = synchronized(lock) {
            val goal = active?.takeIf { it.goalId == goalId && !it.terminal }
                ?: return false
            goal.terminal = true
            goal.progressTask?.cancel()
            goal.progressTask = null
            utterance(goal, kind, text).copy(isFinal = true).also {
                goal.finalUtteranceId = it.utteranceId
            }
        }
        return dispatch(utterance)
    }

    fun speakInteraction(goalId: String, text: String): Boolean {
        val utterance = synchronized(lock) {
            val goal = active?.takeIf { it.goalId == goalId && !it.terminal }
                ?: return false
            utterance(goal, SpokenFeedbackKind.INTERACTION, text)
        }
        return dispatch(utterance)
    }

    fun cancelWithoutSpeech(goalId: String) {
        synchronized(lock) {
            val goal = active?.takeIf { it.goalId == goalId } ?: return
            goal.terminal = true
            goal.progressTask?.cancel()
            goal.progressTask = null
            output.cancelGoal(goalId)
        }
    }

    fun interruptWithCriticalCancellation(goalId: String, text: String): Boolean {
        val utterance = synchronized(lock) {
            val goal = active?.takeIf { it.goalId == goalId } ?: return false
            goal.terminal = true
            goal.progressTask?.cancel()
            goal.progressTask = null
            utterance(goal, SpokenFeedbackKind.CRITICAL_CANCELLATION, text)
                .copy(isFinal = true).also {
                goal.finalUtteranceId = it.utteranceId
            }
        }
        return dispatch(utterance)
    }

    fun onUtteranceCompleted(utterance: SpokenUtterance) {
        val shouldNotify = synchronized(lock) {
            val goal = active ?: return
            goal.goalId == utterance.goalId &&
                goal.finalUtteranceId == utterance.utteranceId
        }
        if (shouldNotify) onFinalUtteranceCompleted(utterance.goalId, utterance.kind)
    }

    fun activeGoalId(): String? = synchronized(lock) { active?.goalId }

    private fun requestProgress(goalId: String, generation: Long) {
        val utterance = synchronized(lock) {
            val goal = active?.takeIf {
                it.goalId == goalId && it.generation == generation && !it.terminal
            } ?: return
            if (goal.progressRequested) return
            goal.progressRequested = true
            utterance(goal, SpokenFeedbackKind.PROGRESS, PROGRESS)
        }
        dispatch(utterance)
    }

    private fun dispatch(utterance: SpokenUtterance): Boolean {
        onUtteranceRequested(utterance)
        return output.speak(utterance)
    }

    private fun utterance(
        goal: ActiveGoal,
        kind: SpokenFeedbackKind,
        text: String
    ) = SpokenUtterance(
        goalId = goal.goalId,
        kind = kind,
        text = text,
        utteranceId = "goal-${goal.generation}-${kind.name.lowercase()}-" +
            utteranceCounter.incrementAndGet()
    )

    companion object {
        const val ACKNOWLEDGEMENT = "حاضر، جاري تنفيذ طلبك."
        const val PROGRESS = "ما زلت أعمل على طلبك."
    }
}
