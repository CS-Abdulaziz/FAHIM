package com.example.testandroidenv.agent.loop

import android.util.Log

data class AgentLogEvent(
    val runId: String,
    val step: Int,
    val requestId: String? = null,
    val packageName: String?,
    val screenVersion: Long?,
    val fingerprintPrefix: String?,
    val elementCount: Int?,
    val plannerStatus: String? = null,
    val plannerAction: String? = null,
    val targetId: String? = null,
    val validation: String? = null,
    val dispatch: String? = null,
    val observation: String? = null,
    val stopReason: String? = null,
    val requestedScreenVersion: Long? = null,
    val requestedFingerprint: String? = null,
    val currentScreenVersion: Long? = null,
    val currentFingerprint: String? = null,
    val staleReason: String? = null,
    val requestPackageName: String? = null,
    val livePackageName: String? = null,
    val requestWindowId: Int? = null,
    val liveWindowId: Int? = null,
    val observationFreshness: String? = null,
    val resolvedHomePackageName: String? = null,
    val normalizedAppId: String? = null,
    val allowlistDecision: String? = null,
    val allowlistReason: String? = null
)

fun interface AgentDiagnosticLogger {
    fun log(event: AgentLogEvent)
}

object AndroidAgentDiagnosticLogger : AgentDiagnosticLogger {
    private const val TAG = "AgentRuntime"

    override fun log(event: AgentLogEvent) {
        Log.i(
            TAG,
            listOfNotNull(
                "run=${event.runId}",
                "step=${event.step}",
                event.requestId?.let { "requestId=$it" },
                event.packageName?.let { "package=$it" },
                event.screenVersion?.let { "version=$it" },
                event.fingerprintPrefix?.let { "fp=$it" },
                event.elementCount?.let { "elements=$it" },
                event.plannerStatus?.let { "status=$it" },
                event.plannerAction?.let { "action=$it" },
                event.targetId?.let { "targetId=$it" },
                event.validation?.let { "validation=$it" },
                event.dispatch?.let { "dispatch=$it" },
                event.observation?.let { "observation=$it" },
                event.stopReason?.let { "stop=$it" },
                event.requestedScreenVersion?.let { "requestedVersion=$it" },
                event.requestedFingerprint?.let { "requestedFp=$it" },
                event.currentScreenVersion?.let { "currentVersion=$it" },
                event.currentFingerprint?.let { "currentFp=$it" },
                event.staleReason?.let { "staleReason=$it" },
                event.requestPackageName?.let { "requestPackage=$it" },
                event.livePackageName?.let { "livePackage=$it" },
                event.requestWindowId?.let { "requestWindow=$it" },
                event.liveWindowId?.let { "liveWindow=$it" },
                event.observationFreshness?.let { "observationFreshness=$it" },
                event.resolvedHomePackageName?.let { "resolvedHome=$it" },
                event.normalizedAppId?.let { "normalizedAppId=$it" },
                event.allowlistDecision?.let { "allowlist=$it" },
                event.allowlistReason?.let { "allowlistReason=$it" }
            ).joinToString(" ")
        )
    }
}
