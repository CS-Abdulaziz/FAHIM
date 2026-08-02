package com.example.testandroidenv.agent.validation

import com.example.testandroidenv.agent.contract.BoundPlannerDecision
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.TaskStatus
import com.example.testandroidenv.agent.perception.TargetRegistryView
import com.example.testandroidenv.agent.safety.ForegroundApplicationPolicy

enum class ValidationErrorCode {
    EMPTY_REASON,
    INCOMPATIBLE_STATUS_ACTION,
    REQUIRED_FIELD_MISSING,
    FIELD_NOT_ALLOWED,
    INVALID_SCROLL_DIRECTION,
    STALE_SNAPSHOT,
    TARGET_NOT_FOUND,
    ACTION_NOT_ADVERTISED,
    TARGET_NOT_VISIBLE,
    TARGET_NOT_ENABLED,
    DISALLOWED_FOREGROUND_APP,
    FOREGROUND_SNAPSHOT_MISMATCH,
    SENSITIVE_ACTION_REQUIRES_CONFIRMATION
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(
        val code: ValidationErrorCode,
        val message: String
    ) : ValidationResult
}

data class LiveTargetState(
    val exists: Boolean,
    val screenVersion: Long?,
    val fingerprint: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val supportedActions: Set<PlannerAction>,
    val packageName: String? = null,
    val activeWindowId: Int? = null,
    val observationWasFresh: Boolean = false,
    val appId: String? = null,
    val resolvedHomePackageName: String? = null,
    val allowlistAllowed: Boolean? = null,
    val allowlistReason: String? = null
)

interface DecisionValidation {
    fun validate(
        bound: BoundPlannerDecision,
        registry: TargetRegistryView?,
        liveTarget: LiveTargetState? = null,
        approval: SafetyApproval? = null,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): ValidationResult
}

class DecisionValidator(
    private val controllerPackageName: String = "com.example.testandroidenv"
) : DecisionValidation {
    override fun validate(
        bound: BoundPlannerDecision,
        registry: TargetRegistryView?,
        liveTarget: LiveTargetState?,
        approval: SafetyApproval?,
        nowEpochMillis: Long
    ): ValidationResult {
        val decision = bound.decision
        if (decision.reason.isNullOrBlank()) {
            return invalid(ValidationErrorCode.EMPTY_REASON, "Planner reason is required.")
        }
        if (decision.action !in allowedActions(decision.status)) {
            return invalid(
                ValidationErrorCode.INCOMPATIBLE_STATUS_ACTION,
                "${decision.action.wireValue} is incompatible with ${decision.status.wireValue}."
            )
        }
        validateFields(bound)?.let { return it }

        if (
            decision.status == TaskStatus.CONTINUE &&
            decision.action in EXECUTABLE_ACTIONS &&
            (
                registry == null ||
                    registry.screenVersion != bound.sourceScreenVersion ||
                    registry.fingerprint != bound.sourceFingerprint
                )
        ) {
            return invalid(
                ValidationErrorCode.STALE_SNAPSHOT,
                "The Planner response belongs to an inactive UI snapshot."
            )
        }
        if (decision.action !in TARGET_ACTIONS) return ValidationResult.Valid
        if (
            registry == null ||
            registry.screenVersion != bound.sourceScreenVersion ||
            registry.fingerprint != bound.sourceFingerprint
        ) {
            return invalid(
                ValidationErrorCode.STALE_SNAPSHOT,
                "The decision is bound to an inactive UI snapshot."
            )
        }

        val targetId = requireNotNull(decision.targetId)
        val target = registry.targets[targetId]
            ?: return invalid(
                ValidationErrorCode.TARGET_NOT_FOUND,
                "Target $targetId does not exist in the current registry."
            )
        if (decision.action !in target.advertisedActions) {
            return invalid(
                ValidationErrorCode.ACTION_NOT_ADVERTISED,
                "Target $targetId does not advertise ${decision.action.wireValue}."
            )
        }
        if (liveTarget == null) {
            return invalid(
                ValidationErrorCode.TARGET_NOT_FOUND,
                "The live target is no longer available."
            )
        }
        if (
            !liveTarget.observationWasFresh ||
            bound.sourcePackageName != liveTarget.packageName ||
            (
                bound.sourceWindowId != null &&
                    bound.sourceWindowId != liveTarget.activeWindowId
                )
        ) {
            return invalid(
                ValidationErrorCode.FOREGROUND_SNAPSHOT_MISMATCH,
                "Request foreground package/window " +
                    "${bound.sourcePackageName}/${bound.sourceWindowId} does not match " +
                    "fresh live ${liveTarget.packageName}/${liveTarget.activeWindowId}."
            )
        }
        val foregroundDecision = ForegroundApplicationPolicy.evaluateNodeAction(
            requestPackageName = bound.sourcePackageName,
            requestAppId = bound.sourceAppId,
            requestResolvedHomePackageName = bound.sourceResolvedHomePackageName,
            livePackageName = liveTarget.packageName,
            liveAppId = liveTarget.appId,
            liveResolvedHomePackageName = liveTarget.resolvedHomePackageName,
            controllerPackageName = controllerPackageName
        )
        if (!foregroundDecision.allowed) {
            return invalid(
                ValidationErrorCode.DISALLOWED_FOREGROUND_APP,
                "Foreground package=${liveTarget.packageName}, " +
                    "resolved HOME=${liveTarget.resolvedHomePackageName}, " +
                    "app_id=${foregroundDecision.normalizedAppId}; " +
                "allowlist rejected: ${foregroundDecision.reason}."
            )
        }
        if (!liveTarget.exists) {
            return invalid(
                ValidationErrorCode.TARGET_NOT_FOUND,
                "The live target is no longer available."
            )
        }
        if (
            liveTarget.screenVersion != bound.sourceScreenVersion ||
            liveTarget.fingerprint != bound.sourceFingerprint
        ) {
            return invalid(
                ValidationErrorCode.STALE_SNAPSHOT,
                "The live target belongs to a different snapshot."
            )
        }
        if (!liveTarget.visible) {
            return invalid(ValidationErrorCode.TARGET_NOT_VISIBLE, "The target is not visible.")
        }
        if (!liveTarget.enabled) {
            return invalid(ValidationErrorCode.TARGET_NOT_ENABLED, "The target is disabled.")
        }
        if (decision.action !in liveTarget.supportedActions) {
            return invalid(
                ValidationErrorCode.ACTION_NOT_ADVERTISED,
                "The live node no longer supports ${decision.action.wireValue}."
            )
        }
        if (
            SafetyPolicy.requiresConfirmation(decision, target) &&
            !SafetyPolicy.hasMatchingApproval(
                decision,
                bound.sourceFingerprint,
                approval,
                nowEpochMillis
            )
        ) {
            return invalid(
                ValidationErrorCode.SENSITIVE_ACTION_REQUIRES_CONFIRMATION,
                "The target appears sensitive and has no current matching approval."
            )
        }
        return ValidationResult.Valid
    }

    private fun validateFields(bound: BoundPlannerDecision): ValidationResult.Invalid? {
        val decision = bound.decision
        return when (decision.action) {
            PlannerAction.OPEN_APP -> when {
                decision.target.isNullOrBlank() ->
                    invalid(ValidationErrorCode.REQUIRED_FIELD_MISSING, "open_app needs target.")
                AllowedAppRegistry.resolvePackage(decision.target) == null ->
                    invalid(
                        ValidationErrorCode.FIELD_NOT_ALLOWED,
                        "open_app target is not in the local application allowlist."
                    )
                decision.targetId != null || decision.value != null ->
                    invalid(
                        ValidationErrorCode.FIELD_NOT_ALLOWED,
                        "open_app cannot have target_id or value."
                    )
                else -> null
            }

            PlannerAction.TAP -> validateTargetActionFields(
                target = decision.target,
                targetId = decision.targetId,
                value = decision.value,
                valueRequired = false
            )

            PlannerAction.TYPE -> validateTargetActionFields(
                target = decision.target,
                targetId = decision.targetId,
                value = decision.value,
                valueRequired = true
            )

            PlannerAction.SCROLL -> {
                validateTargetActionFields(
                    target = decision.target,
                    targetId = decision.targetId,
                    value = decision.value,
                    valueRequired = true
                ) ?: if (decision.value?.lowercase() !in setOf("up", "down")) {
                    invalid(
                        ValidationErrorCode.INVALID_SCROLL_DIRECTION,
                        "This MVP supports only up or down scrolling."
                    )
                } else {
                    null
                }
            }

            PlannerAction.BACK -> if (
                decision.target != null || decision.targetId != null || decision.value != null
            ) {
                invalid(
                    ValidationErrorCode.FIELD_NOT_ALLOWED,
                    "back cannot have target, target_id, or value."
                )
            } else {
                null
            }

            PlannerAction.READ_ALOUD,
            PlannerAction.ASK_USER,
            PlannerAction.CONFIRM_WITH_USER -> when {
                decision.message.isNullOrBlank() ->
                    invalid(
                        ValidationErrorCode.REQUIRED_FIELD_MISSING,
                        "${decision.action.wireValue} needs message."
                    )
                decision.target != null || decision.targetId != null || decision.value != null ->
                    invalid(
                        ValidationErrorCode.FIELD_NOT_ALLOWED,
                        "${decision.action.wireValue} cannot have target fields."
                    )
                else -> null
            }

            PlannerAction.NONE -> if (
                decision.target != null || decision.targetId != null || decision.value != null
            ) {
                invalid(
                    ValidationErrorCode.FIELD_NOT_ALLOWED,
                    "none cannot have target, target_id, or value."
                )
            } else {
                null
            }
        }
    }

    private fun validateTargetActionFields(
        target: String?,
        targetId: String?,
        value: String?,
        valueRequired: Boolean
    ): ValidationResult.Invalid? {
        return when {
            target != null ->
                invalid(
                    ValidationErrorCode.FIELD_NOT_ALLOWED,
                    "UI actions cannot use semantic target."
                )
            targetId.isNullOrBlank() ->
                invalid(
                    ValidationErrorCode.REQUIRED_FIELD_MISSING,
                    "UI action needs target_id."
                )
            valueRequired && value.isNullOrBlank() ->
                invalid(
                    ValidationErrorCode.REQUIRED_FIELD_MISSING,
                    "This UI action needs value."
                )
            !valueRequired && value != null ->
                invalid(
                    ValidationErrorCode.FIELD_NOT_ALLOWED,
                    "tap cannot have value."
                )
            else -> null
        }
    }

    private fun allowedActions(status: TaskStatus): Set<PlannerAction> = when (status) {
        TaskStatus.CONTINUE -> setOf(
            PlannerAction.OPEN_APP,
            PlannerAction.TAP,
            PlannerAction.TYPE,
            PlannerAction.SCROLL,
            PlannerAction.BACK,
            PlannerAction.READ_ALOUD
        )
        TaskStatus.TASK_COMPLETED -> setOf(PlannerAction.NONE, PlannerAction.READ_ALOUD)
        TaskStatus.NEEDS_USER_INPUT -> setOf(PlannerAction.ASK_USER)
        TaskStatus.NEEDS_CONFIRMATION -> setOf(PlannerAction.CONFIRM_WITH_USER)
        TaskStatus.FAILED -> setOf(PlannerAction.NONE, PlannerAction.READ_ALOUD)
        TaskStatus.END_SESSION -> setOf(PlannerAction.NONE, PlannerAction.READ_ALOUD)
    }

    private fun invalid(
        code: ValidationErrorCode,
        message: String
    ) = ValidationResult.Invalid(code, message)

    companion object {
        private val TARGET_ACTIONS = setOf(
            PlannerAction.TAP,
            PlannerAction.TYPE,
            PlannerAction.SCROLL
        )
        private val EXECUTABLE_ACTIONS = setOf(
            PlannerAction.OPEN_APP,
            PlannerAction.TAP,
            PlannerAction.TYPE,
            PlannerAction.SCROLL,
            PlannerAction.BACK
        )
    }
}
