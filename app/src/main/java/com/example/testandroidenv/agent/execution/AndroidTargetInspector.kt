package com.example.testandroidenv.agent.execution

import android.view.accessibility.AccessibilityNodeInfo
import com.example.testandroidenv.TreeDumperService
import com.example.testandroidenv.agent.contract.BoundPlannerDecision
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.perception.AgentSnapshotStore
import com.example.testandroidenv.agent.safety.ForegroundApplicationPolicy
import com.example.testandroidenv.agent.validation.LiveTargetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidTargetInspector(
    private val controllerPackageName: String = "com.example.testandroidenv"
) : TargetRuntimeInspector {
    override suspend fun inspect(
        boundDecision: BoundPlannerDecision
    ): LiveTargetState = withContext(Dispatchers.Main.immediate) {
        val advertisedRegistry = AgentSnapshotStore.currentRegistryView()
        val targetId = boundDecision.decision.targetId
        if (
            advertisedRegistry == null ||
            targetId == null ||
            advertisedRegistry.screenVersion != boundDecision.sourceScreenVersion ||
            advertisedRegistry.fingerprint != boundDecision.sourceFingerprint
        ) {
            return@withContext LiveTargetState(
                exists = false,
                screenVersion = advertisedRegistry?.screenVersion,
                fingerprint = advertisedRegistry?.fingerprint,
                visible = false,
                enabled = false,
                supportedActions = emptySet(),
                packageName = advertisedRegistry?.packageName,
                activeWindowId = advertisedRegistry?.activeWindowId,
                allowlistAllowed = false,
                allowlistReason = "ADVERTISED_REGISTRY_MISMATCH"
            )
        }
        val fresh = TreeDumperService.currentInstance
            ?.captureFreshAgentSnapshotForResolution()
            ?: return@withContext missing(null, null)
        try {
            val registry = fresh.registry
            val observation = fresh.observation
            val foregroundDecision = ForegroundApplicationPolicy.evaluateNodeAction(
                requestPackageName = boundDecision.sourcePackageName,
                requestAppId = boundDecision.sourceAppId,
                requestResolvedHomePackageName =
                    boundDecision.sourceResolvedHomePackageName,
                livePackageName = observation.currentApp.packageName,
                liveAppId = observation.currentApp.appId,
                liveResolvedHomePackageName = observation.resolvedHomePackageName,
                controllerPackageName = controllerPackageName
            )
            val entry = registry.entries[targetId]
            val node = entry?.node
            val advertised = advertisedRegistry.targets[targetId]
            if (
                boundDecision.sourcePackageName != observation.currentApp.packageName ||
                (
                    boundDecision.sourceWindowId != null &&
                        boundDecision.sourceWindowId != observation.activeWindowId
                    ) ||
                node == null ||
                advertised == null ||
                entry.metadata.role != advertised.role ||
                entry.metadata.label != advertised.label ||
                !safe(false) { node.refresh() }
            ) {
                return@withContext missing(
                    version = registry.screenVersion,
                    fingerprint = registry.fingerprint,
                    packageName = observation.currentApp.packageName,
                    activeWindowId = observation.activeWindowId,
                    observationWasFresh = true,
                    appId = observation.currentApp.appId,
                    resolvedHomePackageName = observation.resolvedHomePackageName,
                    allowlistAllowed = foregroundDecision.allowed,
                    allowlistReason = foregroundDecision.reason
                )
            }
            LiveTargetState(
                exists = true,
                screenVersion = registry.screenVersion,
                fingerprint = registry.fingerprint,
                visible = safe(false) { node.isVisibleToUser },
                enabled = safe(false) { node.isEnabled },
                supportedActions = AndroidNodeCapabilities.actions(node),
                packageName = observation.currentApp.packageName,
                activeWindowId = observation.activeWindowId,
                observationWasFresh = true,
                appId = observation.currentApp.appId,
                resolvedHomePackageName = observation.resolvedHomePackageName,
                allowlistAllowed = foregroundDecision.allowed,
                allowlistReason = foregroundDecision.reason
            )
        } finally {
            fresh.registry.release()
        }
    }

    private fun missing(
        version: Long?,
        fingerprint: String?,
        packageName: String? = null,
        activeWindowId: Int? = null,
        observationWasFresh: Boolean = false,
        appId: String? = null,
        resolvedHomePackageName: String? = null,
        allowlistAllowed: Boolean? = null,
        allowlistReason: String? = null
    ) = LiveTargetState(
        exists = false,
        screenVersion = version,
        fingerprint = fingerprint,
        visible = false,
        enabled = false,
        supportedActions = emptySet(),
        packageName = packageName,
        activeWindowId = activeWindowId,
        observationWasFresh = observationWasFresh,
        appId = appId,
        resolvedHomePackageName = resolvedHomePackageName,
        allowlistAllowed = allowlistAllowed,
        allowlistReason = allowlistReason
    )

    private inline fun <T> safe(default: T, block: () -> T): T {
        return try {
            block()
        } catch (_: RuntimeException) {
            default
        }
    }
}

internal object AndroidNodeCapabilities {
    fun actions(node: AccessibilityNodeInfo): Set<PlannerAction> {
        return try {
            val ids = node.actionList.map { it.id }.toSet()
            buildSet {
                if (node.isClickable && AccessibilityNodeInfo.ACTION_CLICK in ids) {
                    add(PlannerAction.TAP)
                }
                if (node.isEditable && AccessibilityNodeInfo.ACTION_SET_TEXT in ids) {
                    add(PlannerAction.TYPE)
                }
                if (
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids ||
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids
                ) {
                    add(PlannerAction.SCROLL)
                }
            }
        } catch (_: RuntimeException) {
            emptySet()
        }
    }
}
