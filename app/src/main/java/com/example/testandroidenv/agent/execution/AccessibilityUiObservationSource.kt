package com.example.testandroidenv.agent.execution

import com.example.testandroidenv.TreeDumperService
import com.example.testandroidenv.agent.contract.ActionResultCode
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.AgentSnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AccessibilityUiObservationSource : UiObservationSource {
    override suspend fun currentObservation(): AgentObservation? {
        val pendingCapture = withContext(Dispatchers.Main.immediate) {
            TreeDumperService.currentInstance?.requestStableAgentObservation()
        }
        if (pendingCapture == null) return null
        val stable = withTimeoutOrNull(AgentExecutionConfig.STABLE_CAPTURE_TIMEOUT_MS) {
            pendingCapture.await()
        }
        if (!pendingCapture.isCompleted) pendingCapture.cancel()
        return stable
    }

    override suspend fun awaitFreshExternalObservation(
        capturedAfterEpochMillis: Long
    ): AgentObservation? {
        val pendingCapture = withContext(Dispatchers.Main.immediate) {
            TreeDumperService.currentInstance?.requestStableAgentObservation(
                capturedAfterEpochMillis = capturedAfterEpochMillis
            )
        } ?: return null
        val fresh = withTimeoutOrNull(AgentExecutionConfig.STABLE_CAPTURE_TIMEOUT_MS) {
            pendingCapture.await()
        }
        if (!pendingCapture.isCompleted) pendingCapture.cancel()
        return fresh?.takeIf {
            it.capturedAtEpochMillis >= capturedAfterEpochMillis
        }
    }

    override suspend fun awaitPostActionObservation(
        source: AgentObservation,
        action: PlannerAction
    ): UiObservationResult {
        val timeout = if (action == PlannerAction.OPEN_APP) {
            AgentExecutionConfig.APP_LAUNCH_OBSERVATION_TIMEOUT_MS
        } else {
            AgentExecutionConfig.NORMAL_OBSERVATION_TIMEOUT_MS
        }
        val changedEvent = withTimeoutOrNull(timeout) {
            AgentSnapshotStore.observations
                .filterNotNull()
                .first { candidate ->
                    candidate.uiState.screenVersion > source.uiState.screenVersion &&
                        hasMeaningfulUiChange(source, candidate)
                }
        }
        if (changedEvent != null) delay(AgentExecutionConfig.POST_EVENT_SETTLE_MS)

        val finalObservation = currentObservation() ?: changedEvent
            ?: return UiObservationResult(
                observation = null,
                changed = false,
                resultCode = ActionResultCode.TIMEOUT,
                details = "No usable accessibility root appeared before timeout."
            )
        val changed = hasMeaningfulUiChange(source, finalObservation)
        return UiObservationResult(
            observation = finalObservation,
            changed = changed,
            resultCode = if (changed) {
                ActionResultCode.ACTION_SUCCEEDED
            } else {
                ActionResultCode.SCREEN_UNCHANGED
            },
            details = if (changed) {
                "Foreground package or compact content fingerprint changed."
            } else {
                "Action was dispatched, but the final compact UI was unchanged."
            }
        )
    }
}

internal fun hasMeaningfulUiChange(
    before: AgentObservation,
    after: AgentObservation
): Boolean {
    return before.currentApp.packageName != after.currentApp.packageName ||
        before.fingerprint != after.fingerprint
}
