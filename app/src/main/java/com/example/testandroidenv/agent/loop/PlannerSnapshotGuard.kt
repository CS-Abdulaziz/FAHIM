package com.example.testandroidenv.agent.loop

import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.TargetRegistryView

data class PlannerRequestSnapshotBinding(
    val requestId: String,
    val observation: AgentObservation,
    val registry: TargetRegistryView,
    val observationWasFresh: Boolean = false,
    val runId: String? = null,
    val activeGoalId: String? = null,
    val loopStep: Int? = null
) {
    val screenVersion: Long = observation.uiState.screenVersion
    val materialFingerprint: String = observation.fingerprint
    val packageName: String? = observation.currentApp.packageName
    val appId: String = observation.currentApp.appId
    val activeWindowId: Int? = observation.activeWindowId
    val resolvedHomePackageName: String? = observation.resolvedHomePackageName
}

enum class ResponseOwnershipReason {
    REQUEST_SUPERSEDED,
    RUN_SUPERSEDED,
    GOAL_SUPERSEDED,
    LOOP_STEP_SUPERSEDED,
    RUN_CANCELLED
}

sealed interface ResponseOwnership {
    data object Owned : ResponseOwnership

    data class Obsolete(
        val reason: ResponseOwnershipReason,
        val message: String
    ) : ResponseOwnership
}

object PlannerResponseOwnershipGuard {
    fun evaluate(
        binding: PlannerRequestSnapshotBinding,
        activeRequestId: String?,
        activeRunId: String?,
        activeGoalId: String?,
        activeLoopStep: Int,
        runIsActive: Boolean
    ): ResponseOwnership {
        if (!runIsActive) {
            return obsolete(ResponseOwnershipReason.RUN_CANCELLED, "Run was cancelled.")
        }
        if (binding.requestId != activeRequestId) {
            return obsolete(
                ResponseOwnershipReason.REQUEST_SUPERSEDED,
                "Planner request ${binding.requestId} is no longer active."
            )
        }
        if (binding.runId != activeRunId) {
            return obsolete(
                ResponseOwnershipReason.RUN_SUPERSEDED,
                "Planner response belongs to a different run."
            )
        }
        if (binding.activeGoalId != activeGoalId) {
            return obsolete(
                ResponseOwnershipReason.GOAL_SUPERSEDED,
                "Planner response belongs to an obsolete active goal."
            )
        }
        if (binding.loopStep != activeLoopStep) {
            return obsolete(
                ResponseOwnershipReason.LOOP_STEP_SUPERSEDED,
                "Planner response belongs to loop step ${binding.loopStep}, " +
                    "not $activeLoopStep."
            )
        }
        return ResponseOwnership.Owned
    }

    private fun obsolete(
        reason: ResponseOwnershipReason,
        message: String
    ) = ResponseOwnership.Obsolete(reason, message)
}

enum class StaleSnapshotReason {
    REQUEST_SUPERSEDED,
    CURRENT_OBSERVATION_MISSING,
    CURRENT_REGISTRY_MISSING,
    PACKAGE_CHANGED,
    NORMALIZED_APP_CHANGED,
    RESOLVED_HOME_CHANGED,
    ACTIVE_WINDOW_CHANGED,
    MATERIAL_FINGERPRINT_CHANGED,
    SCREEN_VERSION_CHANGED,
    REGISTRY_CHANGED,
    REQUESTED_TARGET_CHANGED
}

sealed interface SnapshotFreshness {
    data object Fresh : SnapshotFreshness

    data class Stale(
        val reason: StaleSnapshotReason,
        val message: String
    ) : SnapshotFreshness
}

object PlannerSnapshotGuard {
    fun evaluate(
        binding: PlannerRequestSnapshotBinding,
        activeRequestId: String?,
        currentObservation: AgentObservation?,
        currentRegistry: TargetRegistryView?,
        requestedTargetId: String?
    ): SnapshotFreshness {
        if (activeRequestId != binding.requestId) {
            return stale(
                StaleSnapshotReason.REQUEST_SUPERSEDED,
                "Response request ${binding.requestId} is no longer the active Planner request."
            )
        }
        if (currentObservation == null) {
            return stale(
                StaleSnapshotReason.CURRENT_OBSERVATION_MISSING,
                "No current stable active-window observation is available."
            )
        }
        if (currentRegistry == null) {
            return stale(
                StaleSnapshotReason.CURRENT_REGISTRY_MISSING,
                "No current TargetRegistry is available."
            )
        }
        if (binding.packageName != currentObservation.currentApp.packageName) {
            return stale(
                StaleSnapshotReason.PACKAGE_CHANGED,
                "Foreground package changed from ${binding.packageName} " +
                    "to ${currentObservation.currentApp.packageName}."
            )
        }
        if (binding.appId != currentObservation.currentApp.appId) {
            return stale(
                StaleSnapshotReason.NORMALIZED_APP_CHANGED,
                "Normalized app changed from ${binding.appId} " +
                    "to ${currentObservation.currentApp.appId}."
            )
        }
        if (binding.resolvedHomePackageName != currentObservation.resolvedHomePackageName) {
            return stale(
                StaleSnapshotReason.RESOLVED_HOME_CHANGED,
                "Resolved HOME changed from ${binding.resolvedHomePackageName} " +
                    "to ${currentObservation.resolvedHomePackageName}."
            )
        }
        if (
            binding.activeWindowId != null &&
            currentObservation.activeWindowId != null &&
            binding.activeWindowId != currentObservation.activeWindowId
        ) {
            return stale(
                StaleSnapshotReason.ACTIVE_WINDOW_CHANGED,
                "Active window changed from ${binding.activeWindowId} " +
                    "to ${currentObservation.activeWindowId}."
            )
        }
        if (binding.materialFingerprint != currentObservation.fingerprint) {
            return stale(
                StaleSnapshotReason.MATERIAL_FINGERPRINT_CHANGED,
                "Material UI fingerprint changed."
            )
        }
        if (binding.screenVersion != currentObservation.uiState.screenVersion) {
            return stale(
                StaleSnapshotReason.SCREEN_VERSION_CHANGED,
                "Screen version changed from ${binding.screenVersion} " +
                    "to ${currentObservation.uiState.screenVersion}."
            )
        }
        if (
            currentRegistry.screenVersion != binding.registry.screenVersion ||
            currentRegistry.fingerprint != binding.registry.fingerprint ||
            currentRegistry.packageName != binding.registry.packageName ||
            reliableWindowChanged(
                binding.registry.activeWindowId,
                currentRegistry.activeWindowId
            )
        ) {
            return stale(
                StaleSnapshotReason.REGISTRY_CHANGED,
                "The active TargetRegistry no longer matches the request registry."
            )
        }
        if (requestedTargetId != null) {
            val requestedTarget = binding.registry.targets[requestedTargetId]
            val currentTarget = currentRegistry.targets[requestedTargetId]
            if (requestedTarget != null && currentTarget != requestedTarget) {
                return stale(
                    StaleSnapshotReason.REQUESTED_TARGET_CHANGED,
                    "Requested target $requestedTargetId is absent or materially different."
                )
            }
        }
        return SnapshotFreshness.Fresh
    }

    private fun reliableWindowChanged(requested: Int?, current: Int?): Boolean {
        return requested != null && current != null && requested != current
    }

    private fun stale(
        reason: StaleSnapshotReason,
        message: String
    ) = SnapshotFreshness.Stale(reason, message)
}
