package com.example.testandroidenv.agent.latency

import android.os.SystemClock
import android.util.Log
import com.example.testandroidenv.SpokenFeedbackKind
import com.example.testandroidenv.agent.perception.AgentObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

interface LatencyClock {
    fun elapsedRealtimeMillis(): Long
    fun epochTimeMillis(): Long
}

object AndroidLatencyClock : LatencyClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    override fun epochTimeMillis(): Long = System.currentTimeMillis()
}

data class PlannerTransportTiming(
    val serializationMs: Long,
    val networkRoundTripMs: Long,
    val serverDurationMs: Long?,
    val responseParsingMs: Long
)

class PlannerLatencyContext(
    val goalId: String,
    val loopStep: Int,
    val report: (PlannerTransportTiming) -> Unit
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PlannerLatencyContext>
}

data class PlannerIterationLatency(
    val loopStep: Int,
    val requestedScreenVersion: Long,
    val requestStartedOffsetMs: Long,
    val responseReceivedOffsetMs: Long,
    val requestSerializationMs: Long,
    val networkRoundTripMs: Long,
    val serverDurationMs: Long?,
    val responseParsingMs: Long,
    val totalPlannerCallMs: Long,
    val status: String?,
    val action: String?,
    val failed: Boolean
)

data class GoalLatencySummary(
    val goalId: String,
    val goalStartedAtEpochMillis: Long,
    val sttDurationMs: Long?,
    val snapshotCaptureMs: Long,
    val normalizationMs: Long,
    val requestSerializationMs: Long,
    val networkRoundTripMs: Long,
    val serverDurationMs: Long?,
    val responseParsingMs: Long,
    val validationMs: Long,
    val actionExecutionMs: Long,
    val uiStabilityWaitMs: Long,
    val acknowledgementStartDelayMs: Long?,
    val progressStartDelayMs: Long?,
    val completionStartDelayMs: Long?,
    val plannerCallCount: Int,
    val plannerCalls: List<PlannerIterationLatency>,
    val totalGoalDurationMs: Long,
    val finalState: String,
    val finalStatus: String?
) {
    /** Intentionally excludes commands, UI labels, Planner reasons, and spoken text. */
    fun privacySafeLogLine(): String = listOf(
        "goalId=$goalId",
        "goalStartedAt=$goalStartedAtEpochMillis",
        "sttMs=${sttDurationMs ?: "unavailable"}",
        "plannerCalls=$plannerCallCount",
        "snapshotMs=$snapshotCaptureMs",
        "normalizationMs=$normalizationMs",
        "serializationMs=$requestSerializationMs",
        "networkMs=$networkRoundTripMs",
        "serverMs=${serverDurationMs ?: "unavailable"}",
        "parsingMs=$responseParsingMs",
        "validationMs=$validationMs",
        "executionMs=$actionExecutionMs",
        "uiWaitMs=$uiStabilityWaitMs",
        "ackStartDelayMs=${acknowledgementStartDelayMs ?: "unavailable"}",
        "progressStartDelayMs=${progressStartDelayMs ?: "unavailable"}",
        "completionStartDelayMs=${completionStartDelayMs ?: "unavailable"}",
        "totalMs=$totalGoalDurationMs",
        "finalState=$finalState",
        "finalStatus=${finalStatus ?: "unavailable"}",
        "plannerSteps=${plannerCalls.joinToString(",") { call ->
            listOf(
                call.loopStep,
                call.requestedScreenVersion,
                call.requestStartedOffsetMs,
                call.responseReceivedOffsetMs,
                call.totalPlannerCallMs,
                call.networkRoundTripMs,
                call.serverDurationMs ?: "unavailable",
                call.status ?: "unavailable",
                call.action ?: "unavailable",
                call.failed
            ).joinToString(":")
        }}"
    ).joinToString(prefix = "AgentLatency ", separator = " ")
}

fun interface LatencyDiagnosticLogger {
    fun log(summary: GoalLatencySummary)
}

object AndroidLatencyDiagnosticLogger : LatencyDiagnosticLogger {
    override fun log(summary: GoalLatencySummary) {
        Log.i("AgentLatency", summary.privacySafeLogLine())
    }
}

object AgentLatencyRepository {
    private val mutableLatest = MutableStateFlow<GoalLatencySummary?>(null)
    val latest: StateFlow<GoalLatencySummary?> = mutableLatest.asStateFlow()

    internal fun publish(summary: GoalLatencySummary) {
        mutableLatest.value = summary
    }

    fun clear() {
        mutableLatest.value = null
    }
}

class GoalLatencyTracker(
    val goalId: String,
    sttDurationMs: Long? = null,
    private val clock: LatencyClock = AndroidLatencyClock,
    private val publish: (GoalLatencySummary) -> Unit = AgentLatencyRepository::publish,
    private val logger: LatencyDiagnosticLogger = AndroidLatencyDiagnosticLogger
) {
    private data class MutablePlannerCall(
        val loopStep: Int,
        val requestedScreenVersion: Long,
        val requestStartedOffsetMs: Long,
        var responseReceivedOffsetMs: Long = 0,
        var serializationMs: Long = 0,
        var networkRoundTripMs: Long = 0,
        var serverDurationMs: Long? = null,
        var parsingMs: Long = 0,
        var totalMs: Long = 0,
        var status: String? = null,
        var action: String? = null,
        var failed: Boolean = false
    )

    private val lock = Any()
    private val startedElapsedMs = clock.elapsedRealtimeMillis()
    private val startedEpochMs = clock.epochTimeMillis()
    private var sttMs: Long? = sttDurationMs
    private var snapshotMs = 0L
    private var normalizationMs = 0L
    private var validationMs = 0L
    private var executionMs = 0L
    private var uiWaitMs = 0L
    private val requestedTtsAt = mutableMapOf<SpokenFeedbackKind, Long>()
    private val ttsStartDelays = mutableMapOf<SpokenFeedbackKind, Long>()
    private var finalTtsRequestedAt: Long? = null
    private var finalTtsStartDelayMs: Long? = null
    private val calls = linkedMapOf<Int, MutablePlannerCall>()
    private var finalState: String? = null
    private var finalStatus: String? = null
    private var finalElapsedMs: Long? = null

    fun recordStt(durationMs: Long?) = synchronized(lock) {
        if (durationMs != null && durationMs >= 0) sttMs = durationMs
    }

    fun recordObservation(totalWaitMs: Long, observation: AgentObservation?) = synchronized(lock) {
        val capture = observation?.captureDurationMs ?: 0L
        val normalization = observation?.normalizationDurationMs ?: 0L
        snapshotMs += capture
        normalizationMs += normalization
        uiWaitMs += (totalWaitMs - capture - normalization).coerceAtLeast(0L)
    }

    fun recordUiStabilityWait(durationMs: Long) = synchronized(lock) {
        uiWaitMs += durationMs.coerceAtLeast(0L)
    }

    fun plannerCallStarted(loopStep: Int, requestedScreenVersion: Long) = synchronized(lock) {
        calls[loopStep] = MutablePlannerCall(
            loopStep,
            requestedScreenVersion,
            requestStartedOffsetMs =
                (clock.elapsedRealtimeMillis() - startedElapsedMs).coerceAtLeast(0L)
        )
    }

    fun recordPlannerTransport(loopStep: Int, timing: PlannerTransportTiming) =
        synchronized(lock) {
            val call = calls[loopStep] ?: return@synchronized
            call.serializationMs = timing.serializationMs
            call.networkRoundTripMs = timing.networkRoundTripMs
            call.serverDurationMs = timing.serverDurationMs
            call.parsingMs = timing.responseParsingMs
        }

    fun plannerCallFinished(
        loopStep: Int,
        totalMs: Long,
        status: String?,
        action: String?,
        failed: Boolean
    ) = synchronized(lock) {
        val call = calls[loopStep] ?: return@synchronized
        call.responseReceivedOffsetMs =
            (clock.elapsedRealtimeMillis() - startedElapsedMs).coerceAtLeast(0L)
        call.totalMs = totalMs.coerceAtLeast(0L)
        call.status = status
        call.action = action
        call.failed = failed
    }

    fun recordValidation(durationMs: Long) = synchronized(lock) {
        validationMs += durationMs.coerceAtLeast(0L)
    }

    fun recordExecution(durationMs: Long) = synchronized(lock) {
        executionMs += durationMs.coerceAtLeast(0L)
    }

    fun markTtsRequested(kind: SpokenFeedbackKind, isFinal: Boolean = false) = synchronized(lock) {
        if (isFinal) {
            if (finalTtsRequestedAt == null) finalTtsRequestedAt = clock.elapsedRealtimeMillis()
        } else {
            requestedTtsAt.putIfAbsent(kind, clock.elapsedRealtimeMillis())
        }
    }

    fun recordTtsStarted(kind: SpokenFeedbackKind, isFinal: Boolean = false) {
        val summary = synchronized(lock) {
            if (isFinal && finalTtsStartDelayMs == null) {
                finalTtsRequestedAt?.let { requested ->
                    finalTtsStartDelayMs =
                        (clock.elapsedRealtimeMillis() - requested).coerceAtLeast(0L)
                }
            } else if (!isFinal && kind !in ttsStartDelays) {
                requestedTtsAt[kind]?.let { requested ->
                    ttsStartDelays[kind] =
                        (clock.elapsedRealtimeMillis() - requested).coerceAtLeast(0L)
                }
            }
            if (finalState != null) buildSummaryLocked() else null
        }
        summary?.let { updated ->
            publish(updated)
            if (isFinal) {
                logger.log(updated)
            }
        }
    }

    fun finish(state: String, status: String? = null): GoalLatencySummary {
        var newlyFinalized = false
        val summary = synchronized(lock) {
            if (finalState == null) {
                newlyFinalized = true
                finalState = state
                finalStatus = status
                finalElapsedMs = clock.elapsedRealtimeMillis()
            }
            buildSummaryLocked()
        }
        publish(summary)
        if (newlyFinalized) logger.log(summary)
        return summary
    }

    fun snapshot(finalStateFallback: String = "running"): GoalLatencySummary = synchronized(lock) {
        if (finalState == null) {
            val previousState = finalState
            finalState = finalStateFallback
            val result = buildSummaryLocked()
            finalState = previousState
            result
        } else {
            buildSummaryLocked()
        }
    }

    private fun buildSummaryLocked(): GoalLatencySummary {
        val immutableCalls = calls.values.map { call ->
            PlannerIterationLatency(
                loopStep = call.loopStep,
                requestedScreenVersion = call.requestedScreenVersion,
                requestStartedOffsetMs = call.requestStartedOffsetMs,
                responseReceivedOffsetMs = call.responseReceivedOffsetMs,
                requestSerializationMs = call.serializationMs,
                networkRoundTripMs = call.networkRoundTripMs,
                serverDurationMs = call.serverDurationMs,
                responseParsingMs = call.parsingMs,
                totalPlannerCallMs = call.totalMs,
                status = call.status,
                action = call.action,
                failed = call.failed
            )
        }
        val knownServer = immutableCalls.mapNotNull(PlannerIterationLatency::serverDurationMs)
        return GoalLatencySummary(
            goalId = goalId,
            goalStartedAtEpochMillis = startedEpochMs,
            sttDurationMs = sttMs,
            snapshotCaptureMs = snapshotMs,
            normalizationMs = normalizationMs,
            requestSerializationMs = immutableCalls.sumOf { it.requestSerializationMs },
            networkRoundTripMs = immutableCalls.sumOf { it.networkRoundTripMs },
            serverDurationMs = knownServer.takeIf { it.isNotEmpty() }?.sum(),
            responseParsingMs = immutableCalls.sumOf { it.responseParsingMs },
            validationMs = validationMs,
            actionExecutionMs = executionMs,
            uiStabilityWaitMs = uiWaitMs,
            acknowledgementStartDelayMs =
                ttsStartDelays[SpokenFeedbackKind.ACKNOWLEDGEMENT],
            progressStartDelayMs = ttsStartDelays[SpokenFeedbackKind.PROGRESS],
            completionStartDelayMs = finalTtsStartDelayMs,
            plannerCallCount = immutableCalls.size,
            plannerCalls = immutableCalls,
            totalGoalDurationMs = (
                (finalElapsedMs ?: clock.elapsedRealtimeMillis()) - startedElapsedMs
            ).coerceAtLeast(0L),
            finalState = requireNotNull(finalState),
            finalStatus = finalStatus
        )
    }
}
