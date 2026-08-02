package com.example.testandroidenv.agent.perception

import com.example.testandroidenv.agent.contract.CompactUiElement

data class SnapshotConsistencyIssue(
    val reason: String,
    val details: String
)

object SnapshotConsistency {
    fun validate(
        observation: AgentObservation,
        registry: TargetRegistryView
    ): SnapshotConsistencyIssue? {
        val uiState = observation.uiState
        if (
            uiState.sourcePackageName != null &&
            uiState.sourcePackageName != observation.currentApp.packageName
        ) {
            return issue(
                "SNAPSHOT_PACKAGE_ELEMENT_INCONSISTENCY",
                "Observation package=${observation.currentApp.packageName}, " +
                    "element source package=${uiState.sourcePackageName}."
            )
        }
        if (
            uiState.sourceWindowId != null &&
            observation.activeWindowId != null &&
            uiState.sourceWindowId != observation.activeWindowId
        ) {
            return issue(
                "SNAPSHOT_WINDOW_ELEMENT_INCONSISTENCY",
                "Observation window=${observation.activeWindowId}, " +
                    "element source window=${uiState.sourceWindowId}."
            )
        }
        if (
            registry.packageName != null &&
            registry.packageName != observation.currentApp.packageName
        ) {
            return issue(
                "SNAPSHOT_REGISTRY_PACKAGE_INCONSISTENCY",
                "Observation package=${observation.currentApp.packageName}, " +
                    "registry package=${registry.packageName}."
            )
        }
        if (
            registry.activeWindowId != null &&
            observation.activeWindowId != null &&
            registry.activeWindowId != observation.activeWindowId
        ) {
            return issue(
                "SNAPSHOT_REGISTRY_WINDOW_INCONSISTENCY",
                "Observation window=${observation.activeWindowId}, " +
                    "registry window=${registry.activeWindowId}."
            )
        }
        if (registry.screenVersion != uiState.screenVersion) {
            return issue(
                "SNAPSHOT_VERSION_INCONSISTENCY",
                "Observation version=${uiState.screenVersion}, " +
                    "registry version=${registry.screenVersion}."
            )
        }
        if (registry.fingerprint != observation.fingerprint) {
            return issue(
                "SNAPSHOT_FINGERPRINT_INCONSISTENCY",
                "Observation and registry fingerprints differ."
            )
        }
        uiState.elements.forEach { element ->
            val target = registry.targets[element.targetId]
                ?: return issue(
                    "SNAPSHOT_ELEMENT_REGISTRY_INCONSISTENCY",
                    "Element ${element.targetId} is absent from its TargetRegistry."
                )
            if (!target.matches(element)) {
                return issue(
                    "SNAPSHOT_ELEMENT_REGISTRY_INCONSISTENCY",
                    "Element ${element.targetId} semantics differ from its TargetRegistry."
                )
            }
        }
        if (registry.targets.keys != uiState.elements.mapTo(mutableSetOf()) { it.targetId }) {
            return issue(
                "SNAPSHOT_ELEMENT_REGISTRY_INCONSISTENCY",
                "TargetRegistry and Compact UI element identities differ."
            )
        }
        return null
    }

    internal fun validate(snapshot: ActiveAgentSnapshot): SnapshotConsistencyIssue? {
        return validate(snapshot.observation, snapshot.registry.asView())
    }

    private fun TargetMetadata.matches(element: CompactUiElement): Boolean {
        return role == element.role &&
            label == element.label &&
            advertisedActions == element.actions.toSet() &&
            enabledAtCapture == element.enabled
    }

    private fun issue(reason: String, details: String) =
        SnapshotConsistencyIssue(reason, details)
}
