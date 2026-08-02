package com.example.testandroidenv

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.testandroidenv.agent.contract.PlannerAction as AgentPlannerAction
import com.example.testandroidenv.agent.contract.ConversationContextEntry
import com.example.testandroidenv.agent.execution.AccessibilityUiObservationSource
import com.example.testandroidenv.agent.execution.AndroidActionExecutor
import com.example.testandroidenv.agent.execution.AndroidTargetInspector
import com.example.testandroidenv.agent.loop.AgentLoop
import com.example.testandroidenv.agent.loop.AgentRuntimeState
import com.example.testandroidenv.agent.latency.AgentLatencyRepository
import com.example.testandroidenv.agent.latency.GoalLatencyTracker
import com.example.testandroidenv.agent.perception.AgentSnapshotStore
import com.example.testandroidenv.agent.validation.DecisionValidator
import com.example.testandroidenv.ui.theme.TestAndroidEnvTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var speechRecognizer: GoogleSpeechRecognizerController
    private lateinit var textToSpeech: ArabicTextToSpeechController
    private lateinit var feedbackCoordinator: GoalFeedbackCoordinator
    private lateinit var agentLoop: AgentLoop
    private val plannerClient = PlannerClient()
    private var uiState by mutableStateOf(DemoUiState())
    private var agentJob: Job? = null
    private var lastSpokenAgentEvent: String? = null
    private var awaitingFollowUp = false
    private var listenAfterTts = false
    private var autoStartRecognizedGoal = false
    private var currentRunUserInput: String? = null
    private var currentRunActiveGoal: String? = null
    private var clarificationGoal: String? = null
    private var lastRecordedConversationEvent: String? = null
    private var activeFeedbackGoalId: String? = null
    private var activeLatencyTracker: GoalLatencyTracker? = null
    private var speechStartedElapsedMs: Long? = null
    private var pendingSttDurationMs: Long? = null
    private var completionTransitionGoalId: String? = null
    private val sessionContext = ArrayDeque<ConversationContextEntry>()

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && canStartSpeechRecognition()) {
            speechRecognizer.startListening()
        } else {
            uiState = uiState.copy(
                speechState = SpeechRecognitionState.Error(
                    "Microphone permission is required."
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speechRecognizer = GoogleSpeechRecognizerController(this, ::onSpeechStateChanged)
        textToSpeech = ArabicTextToSpeechController(
            context = this,
            onStateChanged = { state -> uiState = uiState.copy(ttsState = state) },
            onUtteranceStarted = ::onTtsUtteranceStarted,
            onUtteranceCompleted = ::onTtsUtteranceCompleted
        )
        feedbackCoordinator = GoalFeedbackCoordinator(
            output = textToSpeech,
            scheduler = CoroutineFeedbackScheduler(lifecycleScope),
            onUtteranceRequested = ::onFeedbackUtteranceRequested,
            onFinalUtteranceCompleted = ::onFinalUtteranceCompleted
        )
        agentLoop = AgentLoop(
            planner = plannerClient,
            observationSource = AccessibilityUiObservationSource(),
            validator = DecisionValidator(applicationContext.packageName),
            targetInspector = AndroidTargetInspector(applicationContext.packageName),
            executor = AndroidActionExecutor(applicationContext),
            registryProvider = AgentSnapshotStore::registryViewFor
        )

        setContent {
            TestAndroidEnvTheme {
                DemoScreen(
                    state = uiState,
                    onStartListening = ::requestOrStartListening,
                    onStopListening = speechRecognizer::stopListening,
                    onCancelListening = speechRecognizer::cancel,
                    onCommandChanged = { command ->
                        uiState = uiState.copy(
                            recognizedCommand = command,
                            requestState = RequestUiState.Idle
                        )
                    },
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onStartAgent = ::startAgent,
                    onCancelAgent = ::cancelAgent,
                    onClearSession = ::clearSession
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AccessibilitySnapshotRepository.snapshot.collect { snapshot ->
                        uiState = uiState.copy(
                            snapshot = snapshot,
                            nowEpochMillis = System.currentTimeMillis()
                        )
                    }
                }
                launch {
                    AccessibilitySnapshotRepository.connectionState.collect { connection ->
                        uiState = uiState.copy(connectionState = connection)
                    }
                }
                launch {
                    AgentSnapshotStore.observations.collect { observation ->
                        uiState = uiState.copy(agentObservation = observation)
                    }
                }
                launch {
                    AgentLatencyRepository.latest.collect { latency ->
                        uiState = uiState.copy(latencySummary = latency)
                    }
                }
                launch {
                    while (true) {
                        uiState = uiState.copy(nowEpochMillis = System.currentTimeMillis())
                        delay(1_000)
                    }
                }
            }
        }
        lifecycleScope.launch {
            agentLoop.state.collect(::onAgentStateChanged)
        }
    }

    override fun onResume() {
        super.onResume()
        uiState = uiState.copy(
            accessibilityServiceEnabled = AccessibilityServiceAvailability.isEnabled(this),
            nowEpochMillis = System.currentTimeMillis()
        )
    }

    private fun requestOrStartListening() {
        if (!canStartSpeechRecognition()) return
        autoStartRecognizedGoal = !awaitingFollowUp
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            speechRecognizer.startListening()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun canStartSpeechRecognition(): Boolean =
        AudioCoordinationPolicy.canStartRecognition(
            uiState.ttsState,
            textToSpeech.hasPendingOrActiveSpeech()
        )

    private fun onSpeechStateChanged(state: SpeechRecognitionState) {
        when (state) {
            SpeechRecognitionState.Listening -> {
                if (speechStartedElapsedMs == null) {
                    speechStartedElapsedMs = SystemClock.elapsedRealtime()
                }
            }
            is SpeechRecognitionState.Result -> {
                pendingSttDurationMs = speechStartedElapsedMs?.let { started ->
                    (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                }
                speechStartedElapsedMs = null
            }
            is SpeechRecognitionState.Error -> speechStartedElapsedMs = null
            else -> Unit
        }
        uiState = if (state is SpeechRecognitionState.Result) {
            uiState.copy(
                speechState = state,
                recognizedCommand = state.text,
                requestState = RequestUiState.Idle
            )
        } else {
            uiState.copy(speechState = state)
        }
        if (state is SpeechRecognitionState.Result) {
            if (awaitingFollowUp) {
                handleFollowUp(state.text)
            } else if (autoStartRecognizedGoal) {
                autoStartRecognizedGoal = false
                startAgent()
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startAgent() {
        startAgentWithGoal(uiState.recognizedCommand)
    }

    private fun startAgentWithGoal(activeGoal: String) {
        if (!uiState.canStartAgent) return
        val command = uiState.recognizedCommand
        if (command.isBlank() || activeGoal.isBlank()) return
        awaitingFollowUp = false
        listenAfterTts = false
        autoStartRecognizedGoal = false
        currentRunUserInput = command
        currentRunActiveGoal = activeGoal
        clarificationGoal = null
        lastRecordedConversationEvent = null
        val goalId = "goal-${UUID.randomUUID().toString().take(12)}"
        val latencyTracker = GoalLatencyTracker(
            goalId = goalId,
            sttDurationMs = pendingSttDurationMs
        )
        pendingSttDurationMs = null
        activeFeedbackGoalId = goalId
        activeLatencyTracker = latencyTracker
        speechRecognizer.cancel()
        feedbackCoordinator.startGoal(goalId)
        lastSpokenAgentEvent = null
        agentLoop.resetToIdle()
        if (!moveTaskToBack(true)) {
            agentLoop.failBeforeStart(
                "The controller task could not be moved to the background; " +
                    "no Planner observation was sent."
            )
            return
        }
        val freshExternalSnapshotRequiredAfter = System.currentTimeMillis()
        agentJob = lifecycleScope.launch {
            try {
                agentLoop.run(
                    initialUserInput = command,
                    activeGoal = activeGoal,
                    conversationContext = sessionContext.toList(),
                    requireFreshExternalObservationAfterEpochMillis =
                        freshExternalSnapshotRequiredAfter,
                    providedGoalId = goalId,
                    latencyTracker = latencyTracker
                )
            } catch (_: CancellationException) {
                // AgentLoop publishes the observable Cancelled state before propagating.
            }
        }
    }

    private fun cancelAgent() {
        agentJob?.cancel()
        activeFeedbackGoalId?.let { goalId ->
            awaitingFollowUp = false
            listenAfterTts = false
            feedbackCoordinator.interruptWithCriticalCancellation(
                goalId,
                ARABIC_CANCELLED
            )
            activeLatencyTracker?.finish("cancelled", "cancelled")
        }
        if (uiState.agentState is AgentRuntimeState.Completed) {
            agentLoop.endSessionLocally(ARABIC_CANCELLED)
        }
    }

    private fun onAgentStateChanged(state: AgentRuntimeState) {
        uiState = uiState.copy(agentState = state)
        val goalId = activeFeedbackGoalId ?: return
        when (state) {
            is AgentRuntimeState.Running -> {
                val decision = state.decision
                if (
                    decision?.action == AgentPlannerAction.READ_ALOUD &&
                    !decision.message.isNullOrBlank()
                ) {
                    val eventId = "${state.runId}:${state.step}:read_aloud"
                    if (lastSpokenAgentEvent != eventId) {
                        lastSpokenAgentEvent = eventId
                        feedbackCoordinator.speakInteraction(
                            goalId,
                            requireNotNull(decision.message)
                        )
                    }
                }
            }
            is AgentRuntimeState.Completed -> {
                prepareInteractivePlannerSpeech(
                    "completed",
                    state.step,
                    state.message,
                    preservesActiveGoal = false
                )
                finishSpokenGoal(goalId, SpokenFeedbackKind.COMPLETION, state.message, state)
            }
            is AgentRuntimeState.WaitingForFollowUp -> {
                if (completionTransitionGoalId == goalId) {
                    completionTransitionGoalId = null
                } else {
                    prepareInteractivePlannerSpeech(
                        "follow-up",
                        state.step,
                        state.message,
                        preservesActiveGoal = false
                    )
                    finishSpokenGoal(
                        goalId,
                        SpokenFeedbackKind.COMPLETION,
                        state.message,
                        state
                    )
                }
            }
            is AgentRuntimeState.WaitingForUserInput -> {
                prepareInteractivePlannerSpeech(
                    "question",
                    state.step,
                    state.message,
                    preservesActiveGoal = true
                )
                finishSpokenGoal(goalId, SpokenFeedbackKind.INTERACTION, state.message, state)
            }
            is AgentRuntimeState.WaitingForConfirmation -> {
                prepareInteractivePlannerSpeech(
                    "confirmation",
                    state.step,
                    state.message,
                    preservesActiveGoal = true
                )
                finishSpokenGoal(goalId, SpokenFeedbackKind.INTERACTION, state.message, state)
            }
            is AgentRuntimeState.SessionEnded -> {
                awaitingFollowUp = false
                listenAfterTts = false
                clarificationGoal = null
                recordConversationOnce("ended:${state.step}", state.message)
                finishSpokenGoal(goalId, SpokenFeedbackKind.COMPLETION, state.message, state)
            }
            is AgentRuntimeState.Failed -> {
                awaitingFollowUp = false
                listenAfterTts = false
                clarificationGoal = null
                finishSpokenGoal(
                    goalId,
                    SpokenFeedbackKind.FINAL_ERROR,
                    ArabicAgentErrorMessages.forFailure(state),
                    state
                )
            }
            is AgentRuntimeState.Cancelled -> {
                awaitingFollowUp = false
                listenAfterTts = false
                clarificationGoal = null
                feedbackCoordinator.interruptWithCriticalCancellation(goalId, ARABIC_CANCELLED)
                activeLatencyTracker?.finish("cancelled", "cancelled")
            }
            AgentRuntimeState.Idle -> Unit
        }
    }

    private fun prepareInteractivePlannerSpeech(
        kind: String,
        step: Int,
        plannerMessage: String,
        preservesActiveGoal: Boolean
    ) {
        awaitingFollowUp = true
        listenAfterTts = true
        clarificationGoal = if (preservesActiveGoal) currentRunActiveGoal else null
        val eventId = "$kind:$step"
        recordConversationOnce(eventId, plannerMessage)
    }

    private fun finishSpokenGoal(
        goalId: String,
        kind: SpokenFeedbackKind,
        message: String,
        state: AgentRuntimeState
    ) {
        feedbackCoordinator.finishGoal(goalId, kind, message)
        activeLatencyTracker?.finish(
            state = state.javaClass.simpleName,
            status = statePlannerStatus(state)
        )
    }

    private fun onFeedbackUtteranceRequested(utterance: SpokenUtterance) {
        speechRecognizer.cancel()
        activeLatencyTracker
            ?.takeIf { it.goalId == utterance.goalId }
            ?.markTtsRequested(utterance.kind, utterance.isFinal)
    }

    private fun onTtsUtteranceStarted(utterance: SpokenUtterance) {
        speechRecognizer.cancel()
        activeLatencyTracker
            ?.takeIf { it.goalId == utterance.goalId }
            ?.recordTtsStarted(utterance.kind, utterance.isFinal)
    }

    private fun onTtsUtteranceCompleted(utterance: SpokenUtterance) {
        feedbackCoordinator.onUtteranceCompleted(utterance)
    }

    private fun onFinalUtteranceCompleted(
        goalId: String,
        kind: SpokenFeedbackKind
    ) {
        if (goalId != activeFeedbackGoalId) return
        if (
            kind !in setOf(SpokenFeedbackKind.COMPLETION, SpokenFeedbackKind.INTERACTION) ||
            !listenAfterTts ||
            !awaitingFollowUp
        ) {
            return
        }
        listenAfterTts = false
        if (kind == SpokenFeedbackKind.COMPLETION) {
            completionTransitionGoalId = goalId
            agentLoop.onCompletionSpeechFinished()
        }
        requestOrStartListening()
    }

    private fun statePlannerStatus(state: AgentRuntimeState): String? = when (state) {
        is AgentRuntimeState.Running -> state.decision?.status?.wireValue
        is AgentRuntimeState.Completed -> state.decision.status.wireValue
        is AgentRuntimeState.WaitingForFollowUp -> state.decision.status.wireValue
        is AgentRuntimeState.WaitingForUserInput -> state.decision.status.wireValue
        is AgentRuntimeState.WaitingForConfirmation -> state.decision.status.wireValue
        is AgentRuntimeState.SessionEnded -> state.decision.status.wireValue
        is AgentRuntimeState.Failed -> state.decision?.status?.wireValue
        AgentRuntimeState.Idle,
        is AgentRuntimeState.Cancelled -> null
    }

    private fun handleFollowUp(utterance: String) {
        if (EmergencyStopSafety.isExplicitStop(utterance)) {
            awaitingFollowUp = false
            listenAfterTts = false
            autoStartRecognizedGoal = false
            speechRecognizer.cancel()
            activeFeedbackGoalId?.let { goalId ->
                feedbackCoordinator.interruptWithCriticalCancellation(
                    goalId,
                    EmergencyStopSafety.SPOKEN_CONFIRMATION
                )
                activeLatencyTracker?.finish("SessionEnded", "end_session")
            }
            agentLoop.endSessionLocally(EmergencyStopSafety.SPOKEN_CONFIRMATION)
            return
        }
        // No local intent parsing: the complete follow-up is a new Planner input.
        awaitingFollowUp = false
        uiState = uiState.copy(recognizedCommand = utterance)
        val activeGoal = clarificationGoal?.let { previous ->
            "$previous\n$utterance"
        } ?: utterance
        startAgentWithGoal(activeGoal)
    }

    private fun recordConversationOnce(eventId: String, plannerMessage: String) {
        if (lastRecordedConversationEvent == eventId) return
        currentRunUserInput?.let {
            appendConversation(ConversationContextEntry(role = "user", message = it))
        }
        appendConversation(
            ConversationContextEntry(role = "assistant", message = plannerMessage)
        )
        lastRecordedConversationEvent = eventId
    }

    private fun appendConversation(entry: ConversationContextEntry) {
        sessionContext.addLast(entry)
        while (sessionContext.size > MAX_CONVERSATION_CONTEXT) {
            sessionContext.removeFirst()
        }
    }

    private fun speakCurrentReason() {
        val decision = when (val state = uiState.agentState) {
            is AgentRuntimeState.Running -> state.decision
            is AgentRuntimeState.Completed -> state.decision
            is AgentRuntimeState.WaitingForFollowUp -> state.decision
            is AgentRuntimeState.WaitingForUserInput -> state.decision
            is AgentRuntimeState.WaitingForConfirmation -> state.decision
            is AgentRuntimeState.SessionEnded -> state.decision
            is AgentRuntimeState.Failed -> state.decision
            AgentRuntimeState.Idle,
            is AgentRuntimeState.Cancelled -> null
        }
        (decision?.message ?: decision?.reason)?.let(textToSpeech::speak)
    }

    private fun clearSession() {
        agentJob?.cancel()
        activeFeedbackGoalId?.let(feedbackCoordinator::cancelWithoutSpeech)
        textToSpeech.stopAll()
        awaitingFollowUp = false
        listenAfterTts = false
        autoStartRecognizedGoal = false
        currentRunUserInput = null
        currentRunActiveGoal = null
        clarificationGoal = null
        lastRecordedConversationEvent = null
        activeFeedbackGoalId = null
        activeLatencyTracker = null
        completionTransitionGoalId = null
        pendingSttDurationMs = null
        speechStartedElapsedMs = null
        sessionContext.clear()
        lastSpokenAgentEvent = null
        agentLoop.resetToIdle()
        AgentLatencyRepository.clear()
        uiState = uiState.copy(
            recognizedCommand = "",
            speechState = SpeechRecognitionState.Ready,
            requestState = RequestUiState.Idle,
            decision = null,
            history = emptyList()
        )
    }

    override fun onDestroy() {
        agentJob?.cancel()
        plannerClient.cancelActiveRequest()
        speechRecognizer.destroy()
        activeFeedbackGoalId?.let(feedbackCoordinator::cancelWithoutSpeech)
        textToSpeech.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MAX_CONVERSATION_CONTEXT = 20
        private const val ARABIC_CANCELLED = "تم إيقاف المهمة."
    }
}
