package com.example.testandroidenv.agent.execution

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.testandroidenv.TreeDumperService
import com.example.testandroidenv.agent.contract.ActionResultCode
import com.example.testandroidenv.agent.contract.BoundPlannerDecision
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.perception.AgentSnapshotStore
import com.example.testandroidenv.agent.safety.ForegroundApplicationPolicy
import com.example.testandroidenv.agent.validation.AllowedAppRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidActionExecutor(context: Context) : ActionExecutor {
    private val applicationContext = context.applicationContext

    override suspend fun execute(
        boundDecision: BoundPlannerDecision
    ): ExecutionDispatchResult = withContext(Dispatchers.Main.immediate) {
        when (boundDecision.decision.action) {
            PlannerAction.OPEN_APP -> openApp(boundDecision)
            PlannerAction.TAP,
            PlannerAction.TYPE,
            PlannerAction.SCROLL -> executeTargetAction(boundDecision)
            PlannerAction.BACK -> performBack()
            else -> ExecutionDispatchResult(
                action = boundDecision.decision.action,
                dispatched = false,
                resultCode = ActionResultCode.ACTION_NOT_SUPPORTED,
                details = "This decision is a runtime outcome, not an Android executor action."
            )
        }
    }

    private fun openApp(bound: BoundPlannerDecision): ExecutionDispatchResult {
        val action = bound.decision.action
        val packageName = resolveControlledAppPackage(bound.decision.target)
            ?: return ExecutionDispatchResult(
                action,
                false,
                ActionResultCode.APP_NOT_FOUND,
                "The requested app is not in the controlled MVP app map."
            )
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: return ExecutionDispatchResult(
                action,
                false,
                ActionResultCode.APP_NOT_FOUND,
                "WhatsApp is not installed or has no launchable activity."
            )
        return try {
            applicationContext.startActivity(launchIntent)
            ExecutionDispatchResult(
                action,
                true,
                null,
                "Android accepted the WhatsApp launch request."
            )
        } catch (_: ActivityNotFoundException) {
            ExecutionDispatchResult(
                action,
                false,
                ActionResultCode.APP_NOT_FOUND,
                "WhatsApp has no launchable activity."
            )
        } catch (error: SecurityException) {
            ExecutionDispatchResult(
                action,
                false,
                ActionResultCode.EXECUTOR_ERROR,
                "Android denied the app launch: ${error.javaClass.simpleName}."
            )
        }
    }

    private fun executeTargetAction(
        bound: BoundPlannerDecision
    ): ExecutionDispatchResult {
        val decision = bound.decision
        val advertisedRegistry = AgentSnapshotStore.currentRegistryView()
        if (
            advertisedRegistry == null ||
            advertisedRegistry.screenVersion != bound.sourceScreenVersion ||
            advertisedRegistry.fingerprint != bound.sourceFingerprint
        ) {
            return failure(decision.action, ActionResultCode.STALE_TARGET, "Registry changed.")
        }
        val fresh = TreeDumperService.currentInstance
            ?.captureFreshAgentSnapshotForResolution()
            ?: return failure(
                decision.action,
                ActionResultCode.TARGET_NOT_FOUND,
                "No fresh active-window tree is available."
            )
        try {
        val registry = fresh.registry
        val liveObservation = fresh.observation
        val foregroundDecision = ForegroundApplicationPolicy.evaluateNodeAction(
            requestPackageName = bound.sourcePackageName,
            requestAppId = bound.sourceAppId,
            requestResolvedHomePackageName = bound.sourceResolvedHomePackageName,
            livePackageName = liveObservation.currentApp.packageName,
            liveAppId = liveObservation.currentApp.appId,
            liveResolvedHomePackageName = liveObservation.resolvedHomePackageName,
            controllerPackageName = applicationContext.packageName
        )
        Log.i(
            TAG,
            "Node foreground policy foregroundPackage=" +
                "${liveObservation.currentApp.packageName} " +
                "resolvedHomePackage=${liveObservation.resolvedHomePackageName} " +
                "normalizedAppId=${foregroundDecision.normalizedAppId} " +
                "allowed=${foregroundDecision.allowed} reason=${foregroundDecision.reason}"
        )
        if (
            liveObservation.currentApp.packageName != bound.sourcePackageName ||
            (
                bound.sourceWindowId != null &&
                    liveObservation.activeWindowId != bound.sourceWindowId
                )
        ) {
            return failure(
                decision.action,
                ActionResultCode.STALE_TARGET,
                "FOREGROUND_SNAPSHOT_MISMATCH: request package/window " +
                    "${bound.sourcePackageName}/${bound.sourceWindowId}, live " +
                    "${liveObservation.currentApp.packageName}/" +
                "${liveObservation.activeWindowId}."
            )
        }
        if (!foregroundDecision.allowed) {
            return failure(
                decision.action,
                ActionResultCode.ACTION_NOT_SUPPORTED,
                "DISALLOWED_FOREGROUND_APP: foreground package=" +
                    "${liveObservation.currentApp.packageName}, resolved HOME=" +
                    "${liveObservation.resolvedHomePackageName}, " +
                    "app_id=${foregroundDecision.normalizedAppId}, " +
                    "reason=${foregroundDecision.reason}."
            )
        }
        if (
            registry.screenVersion != bound.sourceScreenVersion ||
            registry.fingerprint != bound.sourceFingerprint
        ) {
            return failure(
                decision.action,
                ActionResultCode.STALE_TARGET,
                "The active UI changed before execution."
            )
        }
        val entry = decision.targetId?.let(registry.entries::get)
            ?: return failure(
                decision.action,
                ActionResultCode.TARGET_NOT_FOUND,
                "Target is absent from the active registry."
            )
        if (decision.action !in entry.metadata.advertisedActions) {
            return failure(
                decision.action,
                ActionResultCode.ACTION_NOT_SUPPORTED,
                "Target did not advertise this action."
            )
        }
        val advertised = decision.targetId?.let(advertisedRegistry.targets::get)
        if (
            advertised == null ||
            advertised.role != entry.metadata.role ||
            advertised.label != entry.metadata.label
        ) {
            return failure(
                decision.action,
                ActionResultCode.STALE_TARGET,
                "Fresh target semantics do not match the advertised target."
            )
        }
        val node = entry.node
            ?: return failure(
                decision.action,
                ActionResultCode.TARGET_NOT_FOUND,
                "Target has no executable live node."
            )
        if (!safe(false) { node.refresh() }) {
            return failure(
                decision.action,
                ActionResultCode.TARGET_NOT_FOUND,
                "Target disappeared before execution."
            )
        }
        if (!safe(false) { node.isVisibleToUser }) {
            return failure(
                decision.action,
                ActionResultCode.TARGET_NOT_VISIBLE,
                "Target is no longer visible."
            )
        }
        if (!safe(false) { node.isEnabled }) {
            return failure(
                decision.action,
                ActionResultCode.TARGET_NOT_ENABLED,
                "Target is no longer enabled."
            )
        }
        if (decision.action !in AndroidNodeCapabilities.actions(node)) {
            return failure(
                decision.action,
                ActionResultCode.ACTION_NOT_SUPPORTED,
                "Live node no longer supports the requested action."
            )
        }

        val dispatched = try {
            when (decision.action) {
                PlannerAction.TAP ->
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                PlannerAction.TYPE -> {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                decision.value
                            )
                        }
                    )
                }
                PlannerAction.SCROLL -> node.performAction(
                    if (decision.value.equals("down", ignoreCase = true)) {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                )
                else -> false
            }
        } catch (_: RuntimeException) {
            false
        }
        return if (dispatched) {
            ExecutionDispatchResult(
                decision.action,
                true,
                null,
                "Android accepted one ${decision.action.wireValue} dispatch."
            )
        } else {
            failure(
                decision.action,
                if (decision.action == PlannerAction.TYPE) {
                    ActionResultCode.TEXT_INPUT_FAILED
                } else {
                    ActionResultCode.EXECUTOR_ERROR
                },
                "Android returned false while dispatching ${decision.action.wireValue}."
            )
        }
        } finally {
            fresh.registry.release()
        }
    }

    private fun performBack(): ExecutionDispatchResult {
        val dispatched = TreeDumperService.currentInstance?.performGlobalBackForAgent() == true
        return if (dispatched) {
            ExecutionDispatchResult(
                PlannerAction.BACK,
                true,
                null,
                "Android accepted one global Back action."
            )
        } else {
            failure(
                PlannerAction.BACK,
                ActionResultCode.EXECUTOR_ERROR,
                "Accessibility service could not dispatch Back."
            )
        }
    }

    private fun failure(
        action: PlannerAction,
        code: ActionResultCode,
        details: String
    ) = ExecutionDispatchResult(action, false, code, details)

    private inline fun <T> safe(default: T, block: () -> T): T {
        return try {
            block()
        } catch (_: RuntimeException) {
            default
        }
    }

    companion object {
        private const val TAG = "AgentExecutor"
    }
}

internal fun resolveControlledAppPackage(target: String?): String? {
    return AllowedAppRegistry.resolvePackage(target)
}
