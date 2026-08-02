package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.BoundPlannerDecision
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.PlannerDecision
import com.example.testandroidenv.agent.contract.TaskStatus
import com.example.testandroidenv.agent.contract.UiRole
import com.example.testandroidenv.agent.perception.TargetMetadata
import com.example.testandroidenv.agent.perception.TargetRegistryView
import com.example.testandroidenv.agent.validation.DecisionValidator
import com.example.testandroidenv.agent.validation.LiveTargetState
import com.example.testandroidenv.agent.validation.ValidationErrorCode
import com.example.testandroidenv.agent.validation.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionValidatorTest {
    private val validator = DecisionValidator()

    @Test
    fun acceptsCurrentAdvertisedVisibleTarget() {
        assertEquals(
            ValidationResult.Valid,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "e001")),
                registry(actions = setOf(PlannerAction.TAP)),
                live(actions = setOf(PlannerAction.TAP))
            )
        )
    }

    @Test
    fun rejectsStatusActionMismatchAndMalformedOpenApp() {
        assertInvalid(
            ValidationErrorCode.INCOMPATIBLE_STATUS_ACTION,
            validator.validate(
                bound(
                    decision(
                        status = TaskStatus.TASK_COMPLETED,
                        action = PlannerAction.TAP,
                        targetId = "e001"
                    )
                ),
                registry(),
                live()
            )
        )
        assertInvalid(
            ValidationErrorCode.REQUIRED_FIELD_MISSING,
            validator.validate(bound(decision(action = PlannerAction.OPEN_APP)), registry())
        )
        assertInvalid(
            ValidationErrorCode.FIELD_NOT_ALLOWED,
            validator.validate(
                bound(
                    decision(
                        action = PlannerAction.OPEN_APP,
                        target = "WhatsApp",
                        targetId = "e001"
                    )
                ),
                registry()
            )
        )
    }

    @Test
    fun rejectsMissingInventedAndUnsupportedTargetIds() {
        assertInvalid(
            ValidationErrorCode.REQUIRED_FIELD_MISSING,
            validator.validate(bound(decision(action = PlannerAction.TAP)), registry())
        )
        assertInvalid(
            ValidationErrorCode.TARGET_NOT_FOUND,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "invented")),
                registry(),
                live()
            )
        )
        assertInvalid(
            ValidationErrorCode.ACTION_NOT_ADVERTISED,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "e001")),
                registry(actions = setOf(PlannerAction.SCROLL)),
                live(actions = setOf(PlannerAction.SCROLL))
            )
        )
    }

    @Test
    fun rejectsMalformedTypeAndScroll() {
        assertInvalid(
            ValidationErrorCode.REQUIRED_FIELD_MISSING,
            validator.validate(
                bound(decision(action = PlannerAction.TYPE, targetId = "e001")),
                registry(actions = setOf(PlannerAction.TYPE)),
                live(actions = setOf(PlannerAction.TYPE))
            )
        )
        assertInvalid(
            ValidationErrorCode.ACTION_NOT_ADVERTISED,
            validator.validate(
                bound(
                    decision(
                        action = PlannerAction.TYPE,
                        targetId = "e001",
                        value = "query"
                    )
                ),
                registry(actions = setOf(PlannerAction.TAP)),
                live(actions = setOf(PlannerAction.TAP))
            )
        )
        assertInvalid(
            ValidationErrorCode.INVALID_SCROLL_DIRECTION,
            validator.validate(
                bound(
                    decision(
                        action = PlannerAction.SCROLL,
                        targetId = "e001",
                        value = "diagonal"
                    )
                ),
                registry(actions = setOf(PlannerAction.SCROLL)),
                live(actions = setOf(PlannerAction.SCROLL))
            )
        )
    }

    @Test
    fun rejectsStaleSnapshotAndMissingInteractionMessage() {
        assertInvalid(
            ValidationErrorCode.STALE_SNAPSHOT,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "e001")),
                registry(version = 2),
                live()
            )
        )
        assertInvalid(
            ValidationErrorCode.REQUIRED_FIELD_MISSING,
            validator.validate(
                bound(
                    decision(
                        status = TaskStatus.NEEDS_USER_INPUT,
                        action = PlannerAction.ASK_USER
                    )
                ),
                registry()
            )
        )
    }

    @Test
    fun sensitiveTargetFailsClosedWithoutMatchingApproval() {
        assertInvalid(
            ValidationErrorCode.SENSITIVE_ACTION_REQUIRES_CONFIRMATION,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "e001")),
                registry(actions = setOf(PlannerAction.TAP), label = "Send message"),
                live(actions = setOf(PlannerAction.TAP))
            )
        )
    }

    @Test
    fun rejectsNodeActionInAnUncontrolledForegroundApplication() {
        assertInvalid(
            ValidationErrorCode.DISALLOWED_FOREGROUND_APP,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "e001"))
                    .copy(
                        sourceAppId = "unknown",
                        sourcePackageName = "com.example.uncontrolled"
                    ),
                registry(),
                live().copy(
                    packageName = "com.example.uncontrolled",
                    appId = "unknown"
                )
            )
        )
    }

    @Test
    fun acceptsOpenAppWhatsAppFromAnUnsupportedForegroundApplication() {
        assertEquals(
            ValidationResult.Valid,
            validator.validate(
                bound(decision(action = PlannerAction.OPEN_APP, target = "WhatsApp"))
                    .copy(
                        sourceAppId = "unknown",
                        sourcePackageName = "com.example.unsupported"
                    ),
                registry()
            )
        )
    }

    @Test
    fun rejectsNodeActionWhenLiveForegroundDoesNotMatchRequestSnapshot() {
        assertInvalid(
            ValidationErrorCode.FOREGROUND_SNAPSHOT_MISMATCH,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "e001"))
                    .copy(
                        sourceAppId = "android_launcher",
                        sourcePackageName = "com.transsion.XOSLauncher.upgrade",
                        sourceWindowId = 42,
                        sourceResolvedHomePackageName =
                            "com.transsion.XOSLauncher.upgrade"
                    ),
                registry(),
                live().copy(
                    packageName = "com.example.testandroidenv",
                    activeWindowId = 99,
                    appId = "unknown",
                    resolvedHomePackageName = "com.transsion.XOSLauncher.upgrade"
                )
            )
        )
    }

    @Test
    fun acceptsDynamicallyResolvedOemLauncherTarget() {
        val launcher = "com.transsion.XOSLauncher.upgrade"
        assertEquals(
            ValidationResult.Valid,
            validator.validate(
                bound(decision(action = PlannerAction.TAP, targetId = "e001")).copy(
                    sourceAppId = "android_launcher",
                    sourcePackageName = launcher,
                    sourceWindowId = 42,
                    sourceResolvedHomePackageName = launcher
                ),
                registry(),
                live().copy(
                    packageName = launcher,
                    activeWindowId = 42,
                    appId = "android_launcher",
                    resolvedHomePackageName = launcher
                )
            )
        )
    }

    @Test
    fun rejectsLateOpenAppResponseAfterScreenChanges() {
        assertInvalid(
            ValidationErrorCode.STALE_SNAPSHOT,
            validator.validate(
                bound(decision(action = PlannerAction.OPEN_APP, target = "WhatsApp")),
                registry(version = 2)
            )
        )
    }

    private fun decision(
        status: TaskStatus = TaskStatus.CONTINUE,
        action: PlannerAction = PlannerAction.NONE,
        target: String? = null,
        targetId: String? = null,
        value: String? = null,
        message: String? = null
    ) = PlannerDecision(
        reason = "Short operational reason.",
        status = status,
        action = action,
        target = target,
        targetId = targetId,
        value = value,
        message = message
    )

    private fun bound(decision: PlannerDecision) = BoundPlannerDecision(
        sourceScreenVersion = 1,
        sourceFingerprint = "fingerprint",
        decision = decision,
        sourceAppId = "whatsapp",
        sourcePackageName = "com.whatsapp"
    )

    private fun registry(
        version: Long = 1,
        actions: Set<PlannerAction> = setOf(PlannerAction.TAP),
        label: String = "Safe target"
    ) = TargetRegistryView(
        screenVersion = version,
        fingerprint = "fingerprint",
        targets = mapOf(
            "e001" to TargetMetadata(
                targetId = "e001",
                role = UiRole.BUTTON,
                label = label,
                advertisedActions = actions,
                visibleAtCapture = true,
                enabledAtCapture = true
            )
        )
    )

    private fun live(
        actions: Set<PlannerAction> = setOf(PlannerAction.TAP)
    ) = LiveTargetState(
        exists = true,
        screenVersion = 1,
        fingerprint = "fingerprint",
        visible = true,
        enabled = true,
        supportedActions = actions,
        packageName = "com.whatsapp",
        observationWasFresh = true,
        appId = "whatsapp"
    )

    private fun assertInvalid(
        expected: ValidationErrorCode,
        result: ValidationResult
    ) {
        assertTrue(result is ValidationResult.Invalid)
        assertEquals(expected, (result as ValidationResult.Invalid).code)
    }
}
