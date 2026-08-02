package com.example.testandroidenv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.testandroidenv.agent.contract.ActionResult
import com.example.testandroidenv.agent.contract.PlannerDecision as AgentPlannerDecision
import com.example.testandroidenv.agent.loop.AgentPhase
import com.example.testandroidenv.agent.loop.AgentRuntimeState
import com.example.testandroidenv.agent.latency.GoalLatencySummary
import java.text.DateFormat
import java.util.Date

@Composable
fun DemoScreen(
    state: DemoUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelListening: () -> Unit,
    onCommandChanged: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartAgent: () -> Unit,
    onCancelAgent: () -> Unit,
    onClearSession: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Arabic Accessibility Planner",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Pipeline: ${state.pipelineStatus}",
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Live Mode sends the v0.2 goal and a compact normalized active-screen " +
                    "snapshot to the remote Planner, then safely executes one action per step.",
                style = MaterialTheme.typography.bodySmall
            )

            SpeechSection(
                state = state,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onCancelListening = onCancelListening,
                onCommandChanged = onCommandChanged
            )
            AccessibilitySection(state, onOpenAccessibilitySettings)
            AgentRuntimeSection(state, onStartAgent, onCancelAgent, onClearSession)
            SectionCard(title = "4. AI Planner") {
                Text("Live Planner API v0.2 — all task reasoning is performed remotely.")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AgentRuntimeSection(
    state: DemoUiState,
    onStartAgent: () -> Unit,
    onCancelAgent: () -> Unit,
    onClearSession: () -> Unit
) {
    var compactPreviewExpanded by rememberSaveable { mutableStateOf(false) }
    val runtime = state.agentState
    val observation = state.agentObservation

    SectionCard(title = "3. AI-Driven Agent Runtime") {
        Text("State: ${agentStateText(runtime)}")
        Text("Current step: ${runtime.step}")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStartAgent,
                enabled = state.canStartAgent,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Agent")
            }
            OutlinedButton(
                onClick = onCancelAgent,
                enabled = state.isAgentRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop task")
            }
        }
        if (!state.accessibilityServiceEnabled) {
            Text(
                "Enable the Accessibility Service before starting the agent.",
                color = MaterialTheme.colorScheme.error
            )
        } else if (state.connectionState != AccessibilityConnectionState.CONNECTED) {
            Text(
                "Waiting for the Accessibility Service connection.",
                color = MaterialTheme.colorScheme.error
            )
        }
        OutlinedButton(
            onClick = onClearSession,
            enabled = !state.isAgentRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("End and clear session")
        }

        latestAgentDecision(runtime)?.let { decision ->
            Text(
                "Latest decision: ${decision.status.wireValue} / " +
                    decision.action.wireValue
            )
            Text("Target ID: ${decision.targetId ?: "—"}")
            Text("Reason: ${decision.reason}")
        } ?: Text("Latest decision: —")
        Text("Validation/execution: ${agentProgressText(runtime)}")
        latestAgentResult(runtime)?.let { result ->
            Text(
                "Latest action result: ${result.resultCode.wireValue} " +
                    "(success=${result.success})"
            )
            Text(result.details, style = MaterialTheme.typography.bodySmall)
        } ?: Text("Latest action result: —")
        agentMessage(runtime)?.let { message ->
            Text(
                message,
                color = if (runtime is AgentRuntimeState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontWeight = FontWeight.SemiBold
            )
        }
        LatencyDiagnostics(state.latencySummary)

        if (observation == null) {
            Text("Compact UI State: waiting for an active-window capture.")
        } else {
            Text(
                "Compact UI State: ${observation.currentApp.packageName ?: "unknown"} · " +
                    "version ${observation.uiState.screenVersion} · " +
                    "${observation.uiState.elements.size} elements"
            )
            Text(
                "Fingerprint: ${observation.fingerprint.take(12)}…",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            OutlinedButton(
                onClick = { compactPreviewExpanded = !compactPreviewExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (compactPreviewExpanded) {
                        "Hide Compact UI State"
                    } else {
                        "Show Compact UI State"
                    }
                )
            }
            if (compactPreviewExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = observation.uiState.elements.joinToString("\n") { element ->
                            "${element.targetId} [${element.role.wireValue}] " +
                                "${element.label} " +
                                element.actions.joinToString(
                                    prefix = "{",
                                    postfix = "}"
                                ) { it.wireValue }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyDiagnostics(summary: GoalLatencySummary?) {
    HorizontalDivider()
    Text("Latency diagnostics", fontWeight = FontWeight.SemiBold)
    if (summary == null) {
        Text("No completed goal timing is available yet.")
        return
    }
    Text("Goal: ${summary.goalId} · final=${summary.finalState}")
    Text(
        "Total ${summary.totalGoalDurationMs} ms · STT ${latency(summary.sttDurationMs)} · " +
            "Planner calls ${summary.plannerCallCount}"
    )
    Text(
        "Snapshot ${summary.snapshotCaptureMs} ms · normalization " +
            "${summary.normalizationMs} ms · UI stability wait " +
            "${summary.uiStabilityWaitMs} ms"
    )
    Text(
        "Serialization ${summary.requestSerializationMs} ms · network " +
            "${summary.networkRoundTripMs} ms · server " +
            "${latency(summary.serverDurationMs)} · parsing ${summary.responseParsingMs} ms"
    )
    Text(
        "Validation ${summary.validationMs} ms · execution " +
            "${summary.actionExecutionMs} ms"
    )
    Text(
        "TTS start delay: ack ${latency(summary.acknowledgementStartDelayMs)}, " +
            "progress ${latency(summary.progressStartDelayMs)}, " +
            "final ${latency(summary.completionStartDelayMs)}"
    )
    summary.plannerCalls.forEach { call ->
        Text(
            "Planner step ${call.loopStep}: ${call.totalPlannerCallMs} ms " +
                "(network ${call.networkRoundTripMs} ms, " +
                "server ${latency(call.serverDurationMs)})",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun latency(value: Long?): String = value?.let { "$it ms" } ?: "unavailable"

@Composable
private fun SpeechSection(
    state: DemoUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onCancelListening: () -> Unit,
    onCommandChanged: (String) -> Unit
) {
    SectionCard(title = "1. Arabic voice goal") {
        Text("Speech: ${speechStatusText(state.speechState)}")
        OutlinedTextField(
            value = state.recognizedCommand,
            onValueChange = onCommandChanged,
            label = { Text("Final Arabic command") },
            placeholder = { Text("اضغط للتحدث، ثم صحح النص إذا لزم") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Button(
            onClick = onStartListening,
            enabled = state.speechState !is SpeechRecognitionState.Listening &&
                state.speechState !is SpeechRecognitionState.Recognizing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Google STT (ar-SA)")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onStopListening,
                enabled = state.speechState is SpeechRecognitionState.Listening,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop")
            }
            OutlinedButton(
                onClick = onCancelListening,
                enabled = state.speechState is SpeechRecognitionState.Listening ||
                    state.speechState is SpeechRecognitionState.Recognizing,
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun AccessibilitySection(
    state: DemoUiState,
    onOpenAccessibilitySettings: () -> Unit
) {
    val snapshot = state.snapshot
    var previewExpanded by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = "2. Real accessibility tree") {
        Text("Status: ${accessibilityStatusText(state)}")
        OutlinedButton(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Accessibility Settings")
        }

        if (snapshot == null) {
            Text("Open and interact with a target application to capture its screen.")
        } else {
            Text("Target package: ${snapshot.packageName}")
            snapshot.windowTitle?.let { Text("Window: $it") }
            Text(
                "Captured: ${
                    DateFormat.getTimeInstance(DateFormat.MEDIUM)
                        .format(Date(snapshot.capturedAtEpochMillis))
                } (${snapshotAgeText(snapshot, state.nowEpochMillis)})"
            )
            Text("Node count: ${snapshot.nodeCount}")
            Text("Tree truncated: ${snapshot.treeTruncated}")
            OutlinedButton(
                onClick = { previewExpanded = !previewExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (previewExpanded) "Hide local tree preview" else "Show local tree preview")
            }
            if (previewExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = snapshot.serializedTree,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestSection(
    state: DemoUiState,
    onRetry: () -> Unit,
    onSpeakAgain: () -> Unit
) {
    SectionCard(title = "5. Planner response") {
        Text("Request: ${requestStatusText(state.requestState)}")
        Text("Arabic TTS: ${ttsStatusText(state.ttsState)}")
        (state.requestState as? RequestUiState.Error)?.let { error ->
            Text(error.message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry, enabled = state.canSubmit) {
                Text("Retry")
            }
        }
        state.decision?.let { decision ->
            if (decision.isMock) {
                Text(
                    "MOCK DECISION",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text("Reason: ${decision.reason}")
            Text("Action: ${decision.action}")
            Text("Target: ${decision.target}")
            OutlinedButton(onClick = onSpeakAgain) {
                Text("Speak reason again")
            }
        } ?: Text("No Planner decision yet.")
    }
}

@Composable
private fun HistorySection(
    history: List<PlannerDecision>,
    onClearSession: () -> Unit
) {
    SectionCard(title = "6. Session") {
        if (history.isEmpty()) {
            Text("No successful decisions in this session.")
        } else {
            history.forEachIndexed { index, decision ->
                if (index > 0) HorizontalDivider()
                Text("${index + 1}. ${decision.action} → ${decision.target}")
                Text(decision.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(
            onClick = onClearSession,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear session")
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

private fun speechStatusText(state: SpeechRecognitionState): String = when (state) {
    SpeechRecognitionState.Ready -> "Ready"
    SpeechRecognitionState.Listening -> "Listening"
    SpeechRecognitionState.Recognizing -> "Recognizing"
    is SpeechRecognitionState.Result -> "Speech recognized"
    SpeechRecognitionState.NoSpeech -> "No speech detected"
    SpeechRecognitionState.Unavailable -> "Recognition service unavailable"
    SpeechRecognitionState.NetworkError -> "Network error"
    SpeechRecognitionState.Cancelled -> "Recognition cancelled"
    is SpeechRecognitionState.Error -> state.message
}

private fun accessibilityStatusText(state: DemoUiState): String {
    if (!state.accessibilityServiceEnabled) return "Accessibility Service disabled"
    if (state.connectionState == AccessibilityConnectionState.INTERRUPTED) {
        return "Accessibility Service interrupted"
    }
    if (state.connectionState != AccessibilityConnectionState.CONNECTED) {
        return "Accessibility Service enabled; waiting for connection"
    }
    val snapshot = state.snapshot ?: return "Waiting for a target application"
    return if (snapshot.isFresh(state.nowEpochMillis)) {
        "Target screen captured"
    } else {
        "Captured tree is stale — interact with the target application again"
    }
}

private fun snapshotAgeText(
    snapshot: AccessibilitySnapshot,
    nowEpochMillis: Long
): String {
    val ageSeconds = ((nowEpochMillis - snapshot.capturedAtEpochMillis).coerceAtLeast(0)) / 1_000
    return if (ageSeconds == 0L) "just now" else "${ageSeconds}s ago"
}

private fun requestStatusText(state: RequestUiState): String = when (state) {
    RequestUiState.Idle -> "Idle"
    RequestUiState.Sending -> "Sending"
    is RequestUiState.Success -> if (state.isMock) "Success (Mock)" else "Success (Live)"
    is RequestUiState.Error -> "Error"
}

private fun ttsStatusText(state: ArabicTtsState): String = when (state) {
    ArabicTtsState.Initializing -> "Initializing"
    ArabicTtsState.Ready -> "Ready"
    ArabicTtsState.Speaking -> "Speaking"
    ArabicTtsState.ArabicUnavailable -> "Arabic voice data unavailable"
    is ArabicTtsState.Error -> state.message
}

private fun agentStateText(state: AgentRuntimeState): String = when (state) {
    AgentRuntimeState.Idle -> "Idle"
    is AgentRuntimeState.Running -> "Running — ${state.phase.name.lowercase()}"
    is AgentRuntimeState.Completed -> "Completed"
    is AgentRuntimeState.WaitingForFollowUp -> "Waiting for follow-up"
    is AgentRuntimeState.WaitingForUserInput -> "Needs user input"
    is AgentRuntimeState.WaitingForConfirmation -> "Needs confirmation"
    is AgentRuntimeState.SessionEnded -> "Session ended"
    is AgentRuntimeState.Failed -> "Failed — ${state.code.name}"
    is AgentRuntimeState.Cancelled -> "Cancelled"
}

private fun latestAgentDecision(state: AgentRuntimeState): AgentPlannerDecision? = when (state) {
    AgentRuntimeState.Idle,
    is AgentRuntimeState.Cancelled -> null
    is AgentRuntimeState.Running -> state.decision
    is AgentRuntimeState.Completed -> state.decision
    is AgentRuntimeState.WaitingForFollowUp -> state.decision
    is AgentRuntimeState.WaitingForUserInput -> state.decision
    is AgentRuntimeState.WaitingForConfirmation -> state.decision
    is AgentRuntimeState.SessionEnded -> state.decision
    is AgentRuntimeState.Failed -> state.decision
}

private fun latestAgentResult(state: AgentRuntimeState): ActionResult? = when (state) {
    AgentRuntimeState.Idle,
    is AgentRuntimeState.Cancelled -> null
    is AgentRuntimeState.Running -> state.lastActionResult
    is AgentRuntimeState.Completed -> state.lastActionResult
    is AgentRuntimeState.WaitingForFollowUp -> state.lastActionResult
    is AgentRuntimeState.WaitingForUserInput -> state.lastActionResult
    is AgentRuntimeState.WaitingForConfirmation -> state.lastActionResult
    is AgentRuntimeState.SessionEnded -> state.lastActionResult
    is AgentRuntimeState.Failed -> state.lastActionResult
}

private fun agentProgressText(state: AgentRuntimeState): String = when (state) {
    AgentRuntimeState.Idle -> "not started"
    is AgentRuntimeState.Running -> when (state.phase) {
        AgentPhase.OBSERVING -> "capturing a fresh Compact UI State"
        AgentPhase.PLANNING -> "waiting for one Planner decision"
        AgentPhase.VALIDATING -> "validating the bound decision"
        AgentPhase.EXECUTING -> "validation passed; executing one action"
        AgentPhase.WAITING_FOR_UI -> "action dispatched; observing UI change"
    }
    is AgentRuntimeState.Completed,
    is AgentRuntimeState.WaitingForFollowUp,
    is AgentRuntimeState.WaitingForUserInput,
    is AgentRuntimeState.WaitingForConfirmation,
    is AgentRuntimeState.SessionEnded -> "terminal decision validated; no action dispatched"
    is AgentRuntimeState.Failed -> state.message
    is AgentRuntimeState.Cancelled -> state.message
}

private fun agentMessage(state: AgentRuntimeState): String? = when (state) {
    AgentRuntimeState.Idle,
    is AgentRuntimeState.Running -> null
    is AgentRuntimeState.Completed -> state.message
    is AgentRuntimeState.WaitingForFollowUp -> state.message
    is AgentRuntimeState.WaitingForUserInput -> state.message
    is AgentRuntimeState.WaitingForConfirmation -> state.message
    is AgentRuntimeState.SessionEnded -> state.message
    is AgentRuntimeState.Failed -> state.message
    is AgentRuntimeState.Cancelled -> state.message
}
