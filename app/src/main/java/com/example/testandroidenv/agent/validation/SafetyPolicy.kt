package com.example.testandroidenv.agent.validation

import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.PlannerDecision
import com.example.testandroidenv.agent.perception.CompactUiStateBuilder
import com.example.testandroidenv.agent.perception.TargetMetadata

data class SafetyApproval(
    val action: PlannerAction,
    val targetId: String?,
    val sourceFingerprint: String,
    val expiresAtEpochMillis: Long
)

object SafetyPolicy {
    private val sensitiveTerms = listOf(
        "send",
        "message",
        "call",
        "delete",
        "pay",
        "purchase",
        "order",
        "confirm",
        "allow",
        "permission",
        "share",
        "ارسال",
        "رسالة",
        "رساله",
        "اتصال",
        "مكالمه",
        "حذف",
        "دفع",
        "شراء",
        "طلب",
        "تاكيد",
        "سماح",
        "اذن",
        "مشاركه"
    )

    fun requiresConfirmation(
        decision: PlannerDecision,
        target: TargetMetadata?
    ): Boolean {
        if (decision.action != PlannerAction.TAP && decision.action != PlannerAction.TYPE) {
            return false
        }
        val evidence = buildString {
            append(target?.label.orEmpty()).append(' ')
            if (decision.action == PlannerAction.TAP) append(decision.target.orEmpty())
        }
        val normalized = CompactUiStateBuilder.normalizeForComparison(evidence)
        return sensitiveTerms.any { normalized.contains(it) }
    }

    fun hasMatchingApproval(
        decision: PlannerDecision,
        sourceFingerprint: String,
        approval: SafetyApproval?,
        nowEpochMillis: Long
    ): Boolean {
        return approval != null &&
            approval.action == decision.action &&
            approval.targetId == decision.targetId &&
            approval.sourceFingerprint == sourceFingerprint &&
            approval.expiresAtEpochMillis >= nowEpochMillis
    }
}
