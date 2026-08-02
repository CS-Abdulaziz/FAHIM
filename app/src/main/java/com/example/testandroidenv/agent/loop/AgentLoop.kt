package com.example.testandroidenv.agent.loop

import com.example.testandroidenv.agent.contract.ActionHistoryRecord
import com.example.testandroidenv.agent.contract.ActionResult
import com.example.testandroidenv.agent.contract.ActionResultCode
import com.example.testandroidenv.agent.contract.BoundPlannerDecision
import com.example.testandroidenv.agent.contract.ConversationContextEntry
import com.example.testandroidenv.agent.contract.Planner
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.PlannerRequest
import com.example.testandroidenv.agent.contract.TaskStatus
import com.example.testandroidenv.agent.execution.ActionExecutor
import com.example.testandroidenv.agent.execution.TargetRuntimeInspector
import com.example.testandroidenv.agent.execution.UiObservationSource
import com.example.testandroidenv.agent.latency.GoalLatencyTracker
import com.example.testandroidenv.agent.latency.PlannerLatencyContext
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.TargetRegistryView
import com.example.testandroidenv.agent.perception.SnapshotConsistency
import com.example.testandroidenv.agent.validation.DecisionValidation
import com.example.testandroidenv.agent.validation.ValidationErrorCode
import com.example.testandroidenv.agent.validation.ValidationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AgentLoop(
    private val planner: Planner,
    private val observationSource: UiObservationSource,
    private val validator: DecisionValidation,
    private val targetInspector: TargetRuntimeInspector,
    private val executor: ActionExecutor,
    private val registryProvider: (AgentObservation) -> TargetRegistryView?,
    private val logger: AgentDiagnosticLogger = AndroidAgentDiagnosticLogger,
    private val maxSteps: Int = DEFAULT_MAX_STEPS
) {
    private val running = AtomicBoolean(false)
    private val activePlannerRequestId = AtomicReference<String?>(null)
    private val activeRunId = AtomicReference<String?>(null)
    private val activeGoalId = AtomicReference<String?>(null)
    private val activeLoopStep = AtomicInteger(0)
    private val mutableState = MutableStateFlow<AgentRuntimeState>(AgentRuntimeState.Idle)
    val state: StateFlow<AgentRuntimeState> = mutableState.asStateFlow()

    suspend fun run(
        initialUserInput: String,
        activeGoal: String = initialUserInput,
        conversationContext: List<ConversationContextEntry> = emptyList(),
        requireFreshExternalObservationAfterEpochMillis: Long? = null,
        providedGoalId: String? = null,
        latencyTracker: GoalLatencyTracker? = null
    ) {
        require(initialUserInput.isNotBlank()) { "Agent input cannot be blank." }
        require(activeGoal.isNotBlank()) { "Agent active goal cannot be blank." }
        if (!running.compareAndSet(false, true)) {
            return
        }

        val runId = UUID.randomUUID().toString().take(8)
        val goal = activeGoal
        val goalId = providedGoalId ?: "$runId-${UUID.randomUUID().toString().take(8)}"
        activeRunId.set(runId)
        activeGoalId.set(goalId)
        var userInput: String? = initialUserInput
        var step = 0
        var lastResult: ActionResult? = null
        val history = ArrayDeque<ActionHistoryRecord>()
        val loopProtection = LoopProtection()

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (step >= maxSteps) {
                    stopFailed(
                        step,
                        AgentStopCode.MAX_STEPS_REACHED,
                        "Agent stopped after $maxSteps Planner iterations."
                    )
                    logStop(runId, step, "MAX_STEPS_REACHED")
                    return
                }

                mutableState.value = AgentRuntimeState.Running(
                    runId,
                    step + 1,
                    AgentPhase.OBSERVING,
                    lastActionResult = lastResult
                )
                val requiresFreshExternalObservation =
                    step == 0 && requireFreshExternalObservationAfterEpochMillis != null
                val observationStartedMs = monotonicMillis()
                val observation = try {
                    if (requiresFreshExternalObservation) {
                        observationSource.awaitFreshExternalObservation(
                            requireNotNull(requireFreshExternalObservationAfterEpochMillis)
                        )
                    } else {
                        observationSource.currentObservation()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    stopFailed(
                        step,
                        AgentStopCode.OBSERVATION_ERROR,
                        "UI observation failed: ${error.javaClass.simpleName}."
                    )
                    return
                } ?: run {
                    stopFailed(
                        step,
                        AgentStopCode.ACCESSIBILITY_UNAVAILABLE,
                        if (requiresFreshExternalObservation) {
                            "No fresh external Accessibility snapshot appeared after the " +
                                "controller was backgrounded; the cached snapshot was not used."
                        } else {
                            "Accessibility service has no active window observation."
                        }
                    )
                    logStop(
                        runId,
                        step,
                        if (requiresFreshExternalObservation) {
                            "FRESH_EXTERNAL_SNAPSHOT_UNAVAILABLE"
                        } else {
                            "ACCESSIBILITY_UNAVAILABLE"
                        }
                    )
                    return
                }
                latencyTracker?.recordObservation(
                    monotonicMillis() - observationStartedMs,
                    observation
                )
                val requestRegistry = registryProvider(observation)
                val consistencyIssue = requestRegistry?.let {
                    SnapshotConsistency.validate(observation, it)
                }
                if (requestRegistry == null || consistencyIssue != null) {
                    stopFailed(
                        step,
                        AgentStopCode.OBSERVATION_ERROR,
                        consistencyIssue?.let { "${it.reason}: ${it.details}" }
                            ?: "Stable observation has no TargetRegistry."
                    )
                    logStop(
                        runId,
                        step,
                        consistencyIssue?.reason ?: "OBSERVATION_REGISTRY_MISSING"
                    )
                    return
                }
                step++
                activeLoopStep.set(step)
                val requestId = "$runId-${step.toString().padStart(2, '0')}-${
                    UUID.randomUUID().toString().take(6)
                }"
                val requestBinding = PlannerRequestSnapshotBinding(
                    requestId = requestId,
                    observation = observation,
                    registry = requestRegistry,
                    observationWasFresh = true,
                    runId = runId,
                    activeGoalId = goalId,
                    loopStep = step
                )
                val request = PlannerRequest(
                    userInput = userInput,
                    activeGoal = goal,
                    currentApp = observation.currentApp,
                    uiState = observation.uiState,
                    conversationContext = conversationContext,
                    lastActionResult = lastResult,
                    actionHistory = history.toList()
                )
                mutableState.value = AgentRuntimeState.Running(
                    runId,
                    step,
                    AgentPhase.PLANNING,
                    lastActionResult = lastResult
                )
                activePlannerRequestId.set(requestId)
                latencyTracker?.plannerCallStarted(step, observation.uiState.screenVersion)
                logger.log(
                    event(
                        runId = runId,
                        step = step,
                        observation = observation,
                        requestId = requestId,
                        validation = "REQUEST_SENT",
                        requestedScreenVersion = requestBinding.screenVersion,
                        requestedFingerprint = requestBinding.materialFingerprint,
                        requestPackageName = requestBinding.packageName,
                        requestWindowId = requestBinding.activeWindowId,
                        observationFreshness = "FRESH_STABLE_EXTERNAL"
                    )
                )
                val plannerStartedMs = monotonicMillis()
                val decision = try {
                    withContext(
                        PlannerLatencyContext(goalId, step) { timing ->
                            latencyTracker?.recordPlannerTransport(step, timing)
                        }
                    ) {
                        planner.plan(request)
                    }
                } catch (error: CancellationException) {
                    latencyTracker?.plannerCallFinished(
                        step,
                        monotonicMillis() - plannerStartedMs,
                        status = null,
                        action = null,
                        failed = true
                    )
                    throw error
                } catch (error: RuntimeException) {
                    latencyTracker?.plannerCallFinished(
                        step,
                        monotonicMillis() - plannerStartedMs,
                        status = null,
                        action = null,
                        failed = true
                    )
                    stopFailed(
                        step,
                        AgentStopCode.PLANNER_ERROR,
                        error.message?.take(500)
                            ?: "Planner failed: ${error.javaClass.simpleName}."
                    )
                    logStop(runId, step, "PLANNER_ERROR")
                    return
                }
                latencyTracker?.plannerCallFinished(
                    step,
                    monotonicMillis() - plannerStartedMs,
                    status = decision.status.wireValue,
                    action = decision.action.wireValue,
                    failed = false
                )
                currentCoroutineContext().ensureActive()
                val ownership = PlannerResponseOwnershipGuard.evaluate(
                    binding = requestBinding,
                    activeRequestId = activePlannerRequestId.get(),
                    activeRunId = activeRunId.get(),
                    activeGoalId = activeGoalId.get(),
                    activeLoopStep = activeLoopStep.get(),
                    runIsActive = currentCoroutineContext().isActive
                )
                if (ownership is ResponseOwnership.Obsolete) {
                    activePlannerRequestId.compareAndSet(requestId, null)
                    stopFailed(
                        step = step,
                        code = AgentStopCode.INVALID_DECISION,
                        message = "OBSOLETE_PLANNER_RESPONSE [${ownership.reason}]: " +
                            ownership.message,
                        decision = decision,
                        result = lastResult
                    )
                    logStop(runId, step, "OBSOLETE_RESPONSE_${ownership.reason}")
                    return
                }

                val terminalCompletionError = terminalCompletionError(decision)
                val isNoActionTaskCompletion =
                    decision.status == TaskStatus.TASK_COMPLETED &&
                        decision.action == PlannerAction.NONE
                if (isNoActionTaskCompletion && terminalCompletionError != null) {
                    activePlannerRequestId.compareAndSet(requestId, null)
                    stopFailed(
                        step = step,
                        code = AgentStopCode.INVALID_DECISION,
                        message = "INVALID_TASK_COMPLETION: $terminalCompletionError",
                        decision = decision,
                        result = lastResult
                    )
                    logStop(runId, step, "INVALID_TASK_COMPLETION")
                    return
                }
                val responseObservationStartedMs = monotonicMillis()
                val responseObservation = try {
                    observationSource.currentObservation()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    stopFailed(
                        step,
                        AgentStopCode.OBSERVATION_ERROR,
                        "Response-time UI observation failed: ${error.javaClass.simpleName}."
                    )
                    logStop(runId, step, "RESPONSE_OBSERVATION_ERROR")
                    return
                }
                latencyTracker?.recordObservation(
                    monotonicMillis() - responseObservationStartedMs,
                    responseObservation
                )
                val responseRegistry = responseObservation?.let(registryProvider)
                val freshness = PlannerSnapshotGuard.evaluate(
                    binding = requestBinding,
                    activeRequestId = activePlannerRequestId.get(),
                    currentObservation = responseObservation,
                    currentRegistry = responseRegistry,
                    requestedTargetId = decision.targetId
                )
                logger.log(
                    event(
                        runId = runId,
                        step = step,
                        observation = responseObservation ?: observation,
                        requestId = requestId,
                        plannerStatus = decision.status.wireValue,
                        plannerAction = decision.action.wireValue,
                        targetId = decision.targetId,
                        validation = when {
                            freshness is SnapshotFreshness.Fresh ->
                                "RESPONSE_SNAPSHOT_MATCHED"
                            isNoActionTaskCompletion ->
                                "STALE_TERMINAL_DECISION"
                            decision.action in EXECUTABLE_ACTIONS ->
                                "STALE_EXECUTABLE_DECISION"
                            else -> "STALE_NON_EXECUTABLE_DECISION"
                        },
                        requestedScreenVersion = requestBinding.screenVersion,
                        requestedFingerprint = requestBinding.materialFingerprint,
                        currentScreenVersion = responseObservation?.uiState?.screenVersion,
                        currentFingerprint = responseObservation?.fingerprint,
                        staleReason = (freshness as? SnapshotFreshness.Stale)?.reason?.name,
                        requestPackageName = requestBinding.packageName,
                        livePackageName = responseObservation?.currentApp?.packageName,
                        requestWindowId = requestBinding.activeWindowId,
                        liveWindowId = responseObservation?.activeWindowId,
                        observationFreshness = "FRESH_STABLE_EXTERNAL"
                    )
                )
                if (isNoActionTaskCompletion) {
                    if (freshness is SnapshotFreshness.Stale) {
                        activePlannerRequestId.compareAndSet(requestId, null)
                        logger.log(
                            event(
                                runId = runId,
                                step = step,
                                observation = responseObservation ?: observation,
                                requestId = requestId,
                                plannerStatus = decision.status.wireValue,
                                plannerAction = decision.action.wireValue,
                                validation = "STALE_TERMINAL_REPLAN_REQUIRED",
                                staleReason = freshness.reason.name,
                                stopReason = "TARGET_VALIDATION_SKIPPED_ACTION_NONE"
                            )
                        )
                        userInput = null
                        continue
                    }
                    activePlannerRequestId.compareAndSet(requestId, null)
                    logger.log(
                        event(
                            runId = runId,
                            step = step,
                            observation = observation,
                            requestId = requestId,
                            plannerStatus = decision.status.wireValue,
                            plannerAction = decision.action.wireValue,
                            validation = "VALID_TASK_COMPLETION",
                            stopReason = "TARGET_VALIDATION_SKIPPED_ACTION_NONE"
                        )
                    )
                    mutableState.value = AgentRuntimeState.Completed(
                        step = step,
                        decision = decision,
                        message = decision.message
                            ?: decision.reason
                            ?: "Task completed.",
                        lastActionResult = lastResult
                    )
                    logStop(runId, step, "VALID_TASK_COMPLETION")
                    return
                }
                if (freshness is SnapshotFreshness.Stale) {
                    val result = ActionResult(
                        action = decision.action,
                        success = false,
                        resultCode = ActionResultCode.STALE_TARGET,
                        details = "${freshness.reason}: ${freshness.message}",
                        target = decision.target,
                        targetId = decision.targetId
                    )
                    stopFailed(
                        step = step,
                        code = AgentStopCode.INVALID_DECISION,
                        message = "STALE_SNAPSHOT [${freshness.reason}]: ${freshness.message}",
                        decision = decision,
                        result = result
                    )
                    activePlannerRequestId.compareAndSet(requestId, null)
                    logStop(
                        runId,
                        step,
                        if (decision.action in EXECUTABLE_ACTIONS) {
                            "STALE_EXECUTABLE_DECISION_${freshness.reason.name}"
                        } else {
                            "STALE_NON_EXECUTABLE_DECISION_${freshness.reason.name}"
                        }
                    )
                    return
                }
                activePlannerRequestId.compareAndSet(requestId, null)
                val bound = BoundPlannerDecision(
                    sourceScreenVersion = observation.uiState.screenVersion,
                    sourceFingerprint = observation.fingerprint,
                    decision = decision,
                    sourceAppId = observation.currentApp.appId,
                    sourcePackageName = observation.currentApp.packageName,
                    sourceWindowId = observation.activeWindowId,
                    sourceResolvedHomePackageName = observation.resolvedHomePackageName,
                    requestId = requestId
                )

                mutableState.value = AgentRuntimeState.Running(
                    runId,
                    step,
                    AgentPhase.VALIDATING,
                    decision,
                    lastResult
                )
                val validationStartedMs = monotonicMillis()
                val sourceRegistry = requestBinding.registry
                val sourceTargetMetadata =
                    decision.targetId?.let { sourceRegistry?.targets?.get(it) }
                val liveTarget = try {
                    if (decision.action in TARGET_ACTIONS) {
                        targetInspector.inspect(bound)
                    } else {
                        null
                    }
                } catch (error: CancellationException) {
                    latencyTracker?.recordValidation(monotonicMillis() - validationStartedMs)
                    throw error
                } catch (error: RuntimeException) {
                    latencyTracker?.recordValidation(monotonicMillis() - validationStartedMs)
                    stopFailed(
                        step,
                        AgentStopCode.OBSERVATION_ERROR,
                        "Live target inspection failed: ${error.javaClass.simpleName}.",
                        decision,
                        lastResult
                    )
                    return
                }
                if (decision.action in TARGET_ACTIONS) {
                    logger.log(
                        event(
                            runId = runId,
                            step = step,
                            observation = observation,
                            requestId = requestId,
                            plannerStatus = decision.status.wireValue,
                            plannerAction = decision.action.wireValue,
                            targetId = decision.targetId,
                            validation = "EXECUTION_PREFLIGHT",
                            requestPackageName = bound.sourcePackageName,
                            livePackageName = liveTarget?.packageName,
                            requestWindowId = bound.sourceWindowId,
                            liveWindowId = liveTarget?.activeWindowId,
                            observationFreshness = if (
                                liveTarget?.observationWasFresh == true
                            ) {
                                "FRESH_LIVE_TREE"
                            } else {
                                "UNAVAILABLE_OR_CACHED"
                            },
                            resolvedHomePackageName =
                                liveTarget?.resolvedHomePackageName,
                            normalizedAppId = liveTarget?.appId,
                            allowlistDecision = liveTarget?.allowlistAllowed?.let {
                                if (it) "ALLOWED" else "REJECTED"
                            },
                            allowlistReason = liveTarget?.allowlistReason
                        )
                    )
                }
                val validation = try {
                    validator.validate(
                        bound = bound,
                        registry = sourceRegistry,
                        liveTarget = liveTarget
                    )
                } catch (error: CancellationException) {
                    latencyTracker?.recordValidation(monotonicMillis() - validationStartedMs)
                    throw error
                } catch (error: RuntimeException) {
                    latencyTracker?.recordValidation(monotonicMillis() - validationStartedMs)
                    stopFailed(
                        step,
                        AgentStopCode.INVALID_DECISION,
                        "Decision validation failed: ${error.javaClass.simpleName}.",
                        decision,
                        lastResult
                    )
                    return
                }
                latencyTracker?.recordValidation(monotonicMillis() - validationStartedMs)
                if (validation is ValidationResult.Invalid) {
                    val validationResult = ActionResult(
                        action = decision.action,
                        success = false,
                        resultCode = when (validation.code) {
                            ValidationErrorCode.STALE_SNAPSHOT ->
                                ActionResultCode.STALE_TARGET
                            ValidationErrorCode.FOREGROUND_SNAPSHOT_MISMATCH ->
                                ActionResultCode.STALE_TARGET
                            ValidationErrorCode.TARGET_NOT_FOUND ->
                                ActionResultCode.TARGET_NOT_FOUND
                            ValidationErrorCode.TARGET_NOT_VISIBLE ->
                                ActionResultCode.TARGET_NOT_VISIBLE
                            ValidationErrorCode.TARGET_NOT_ENABLED ->
                                ActionResultCode.TARGET_NOT_ENABLED
                            else -> ActionResultCode.ACTION_NOT_SUPPORTED
                        },
                        details = "${validation.code}: ${validation.message}",
                        target = decision.target,
                        targetId = decision.targetId
                    )
                    stopFailed(
                        step,
                        AgentStopCode.INVALID_DECISION,
                        "${validation.code}: ${validation.message}",
                        decision,
                        validationResult
                    )
                    logger.log(
                        event(
                            runId,
                            step,
                            observation,
                            plannerStatus = decision.status.wireValue,
                            plannerAction = decision.action.wireValue,
                            targetId = decision.targetId,
                            validation = validation.code.name,
                            stopReason = "INVALID_DECISION"
                        )
                    )
                    return
                }

                when (decision.status) {
                    TaskStatus.TASK_COMPLETED -> {
                        mutableState.value = AgentRuntimeState.Completed(
                            step,
                            decision,
                            decision.message ?: "Task completed.",
                            lastResult
                        )
                        logStop(runId, step, "TASK_COMPLETED")
                        return
                    }
                    TaskStatus.NEEDS_USER_INPUT -> {
                        mutableState.value = AgentRuntimeState.WaitingForUserInput(
                            step,
                            decision,
                            requireNotNull(decision.message),
                            lastResult
                        )
                        logStop(runId, step, "NEEDS_USER_INPUT")
                        return
                    }
                    TaskStatus.NEEDS_CONFIRMATION -> {
                        mutableState.value = AgentRuntimeState.WaitingForConfirmation(
                            step,
                            decision,
                            requireNotNull(decision.message),
                            lastResult
                        )
                        logStop(runId, step, "NEEDS_CONFIRMATION")
                        return
                    }
                    TaskStatus.FAILED -> {
                        stopFailed(
                            step,
                            AgentStopCode.PLANNER_ERROR,
                            decision.message ?: decision.reason.orEmpty(),
                            decision,
                            lastResult
                        )
                        logStop(runId, step, "PLANNER_FAILED")
                        return
                    }
                    TaskStatus.END_SESSION -> {
                        mutableState.value = AgentRuntimeState.SessionEnded(
                            step,
                            decision,
                            decision.message ?: decision.reason.orEmpty(),
                            lastResult
                        )
                        logStop(runId, step, "END_SESSION")
                        return
                    }
                    TaskStatus.CONTINUE -> Unit
                }

                if (decision.action == PlannerAction.READ_ALOUD) {
                    lastResult = ActionResult(
                        decision.action,
                        true,
                        ActionResultCode.ACTION_SUCCEEDED,
                        "Message exposed to the UI/TTS layer.",
                        target = decision.target,
                        targetId = decision.targetId
                    )
                    userInput = null
                    continue
                }

                mutableState.value = AgentRuntimeState.Running(
                    runId,
                    step,
                    AgentPhase.EXECUTING,
                    decision,
                    lastResult
                )
                val executionStartedMs = monotonicMillis()
                val dispatch = try {
                    executor.execute(bound)
                } catch (error: CancellationException) {
                    latencyTracker?.recordExecution(monotonicMillis() - executionStartedMs)
                    throw error
                } catch (error: RuntimeException) {
                    latencyTracker?.recordExecution(monotonicMillis() - executionStartedMs)
                    stopFailed(
                        step,
                        AgentStopCode.EXECUTOR_ERROR,
                        "Executor failed: ${error.javaClass.simpleName}.",
                        decision,
                        lastResult
                    )
                    logStop(runId, step, "EXECUTOR_ERROR")
                    return
                }
                latencyTracker?.recordExecution(monotonicMillis() - executionStartedMs)
                val result = if (!dispatch.dispatched) {
                    ActionResult(
                        action = decision.action,
                        success = false,
                        resultCode = dispatch.resultCode ?: ActionResultCode.EXECUTOR_ERROR,
                        details = dispatch.details,
                        target = decision.target,
                        targetId = decision.targetId
                    )
                } else {
                    mutableState.value = AgentRuntimeState.Running(
                        runId,
                        step,
                        AgentPhase.WAITING_FOR_UI,
                        decision,
                        lastResult
                    )
                    val uiWaitStartedMs = monotonicMillis()
                    val observed = try {
                        observationSource.awaitPostActionObservation(
                            observation,
                            decision.action
                        )
                    } catch (error: CancellationException) {
                        latencyTracker?.recordUiStabilityWait(
                            monotonicMillis() - uiWaitStartedMs
                        )
                        throw error
                    } catch (error: RuntimeException) {
                        latencyTracker?.recordUiStabilityWait(
                            monotonicMillis() - uiWaitStartedMs
                        )
                        stopFailed(
                            step,
                            AgentStopCode.OBSERVATION_ERROR,
                            "Post-action observation failed: ${error.javaClass.simpleName}.",
                            decision,
                            lastResult
                        )
                        logStop(runId, step, "OBSERVATION_ERROR")
                        return
                    }
                    latencyTracker?.recordObservation(
                        monotonicMillis() - uiWaitStartedMs,
                        observed.observation
                    )
                    ActionResult(
                        action = decision.action,
                        success = observed.resultCode == ActionResultCode.ACTION_SUCCEEDED,
                        resultCode = observed.resultCode,
                        details = observed.details,
                        target = decision.target,
                        targetId = decision.targetId
                    )
                }

                val historyRecord = ActionHistoryRecord(
                    action = decision.action,
                    target = decision.target,
                    targetId = decision.targetId,
                    value = decision.value,
                    success = result.success,
                    resultCode = result.resultCode,
                    screenVersion = observation.uiState.screenVersion,
                    screenFingerprint = observation.fingerprint
                )
                history.addLast(historyRecord)
                while (history.size > MAX_HISTORY) history.removeFirst()
                lastResult = result
                userInput = null

                if (!result.success) {
                    val signature = ActionSignature(
                        sourceFingerprint = observation.fingerprint,
                        action = decision.action,
                        semanticTarget = decision.target
                            ?: sourceTargetMetadata?.let { "${it.role.wireValue}:${it.label}" }
                            ?: decision.targetId.orEmpty(),
                        redactedValueMarker =
                            LoopProtection.redactedValueMarker(decision.value),
                        resultCode = result.resultCode
                    )
                    if (loopProtection.record(signature)) {
                        stopFailed(
                            step,
                            AgentStopCode.REPEATED_ACTION_LOOP,
                            "The same failed action repeated on unchanged UI.",
                            decision,
                            result
                        )
                        logStop(runId, step, "REPEATED_ACTION_LOOP")
                        return
                    }
                }
                logger.log(
                    event(
                        runId,
                        step,
                        observation,
                        plannerStatus = decision.status.wireValue,
                        plannerAction = decision.action.wireValue,
                        targetId = decision.targetId,
                        validation = "VALID",
                        dispatch = if (dispatch.dispatched) "DISPATCHED" else "REJECTED",
                        observationResult = result.resultCode.wireValue
                    )
                )
            }
        } catch (error: CancellationException) {
            mutableState.value = AgentRuntimeState.Cancelled(step)
            logStop(runId, step, "CANCELLED")
            throw error
        } finally {
            activePlannerRequestId.set(null)
            activeRunId.compareAndSet(runId, null)
            activeGoalId.compareAndSet(goalId, null)
            activeLoopStep.set(0)
            running.set(false)
        }
    }

    fun resetToIdle() {
        if (!running.get()) mutableState.value = AgentRuntimeState.Idle
    }

    /** Called only after the typed final-completion TTS callback for the active goal. */
    fun onCompletionSpeechFinished(): Boolean {
        val completed = mutableState.value as? AgentRuntimeState.Completed ?: return false
        mutableState.value = AgentRuntimeState.WaitingForFollowUp(
            step = completed.step,
            decision = completed.decision,
            message = completed.message,
            lastActionResult = completed.lastActionResult
        )
        return true
    }

    fun failBeforeStart(message: String) {
        if (!running.get()) {
            mutableState.value = AgentRuntimeState.Failed(
                step = 0,
                code = AgentStopCode.ACCESSIBILITY_UNAVAILABLE,
                message = message
            )
        }
    }

    fun endSessionLocally(message: String) {
        if (running.get()) return
        val current = mutableState.value
        val decision = when (current) {
            is AgentRuntimeState.WaitingForFollowUp -> current.decision
            is AgentRuntimeState.Completed -> current.decision
            is AgentRuntimeState.WaitingForUserInput -> current.decision
            is AgentRuntimeState.WaitingForConfirmation -> current.decision
            else -> return
        }
        mutableState.value = AgentRuntimeState.SessionEnded(
            step = current.step,
            decision = decision,
            message = message,
            lastActionResult = when (current) {
                is AgentRuntimeState.WaitingForFollowUp -> current.lastActionResult
                is AgentRuntimeState.Completed -> current.lastActionResult
                is AgentRuntimeState.WaitingForUserInput -> current.lastActionResult
                is AgentRuntimeState.WaitingForConfirmation -> current.lastActionResult
                else -> null
            }
        )
    }

    private fun stopFailed(
        step: Int,
        code: AgentStopCode,
        message: String,
        decision: com.example.testandroidenv.agent.contract.PlannerDecision? = null,
        result: ActionResult? = null
    ) {
        mutableState.value = AgentRuntimeState.Failed(step, code, message, decision, result)
    }

    private fun terminalCompletionError(
        decision: com.example.testandroidenv.agent.contract.PlannerDecision
    ): String? {
        if (
            decision.status != TaskStatus.TASK_COMPLETED ||
            decision.action != PlannerAction.NONE
        ) {
            return null
        }
        return when {
            decision.reason.isNullOrBlank() -> "Planner reason is required."
            decision.target != null -> "task_completed + none cannot have target."
            decision.targetId != null -> "task_completed + none cannot have target_id."
            decision.value != null -> "task_completed + none cannot have value."
            else -> null
        }
    }

    private fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    private fun event(
        runId: String,
        step: Int,
        observation: com.example.testandroidenv.agent.perception.AgentObservation,
        plannerStatus: String? = null,
        plannerAction: String? = null,
        targetId: String? = null,
        validation: String? = null,
        dispatch: String? = null,
        observationResult: String? = null,
        stopReason: String? = null,
        requestId: String? = null,
        requestedScreenVersion: Long? = null,
        requestedFingerprint: String? = null,
        currentScreenVersion: Long? = null,
        currentFingerprint: String? = null,
        staleReason: String? = null,
        requestPackageName: String? = null,
        livePackageName: String? = null,
        requestWindowId: Int? = null,
        liveWindowId: Int? = null,
        observationFreshness: String? = null,
        resolvedHomePackageName: String? = observation.resolvedHomePackageName,
        normalizedAppId: String? = observation.currentApp.appId,
        allowlistDecision: String? = null,
        allowlistReason: String? = null
    ) = AgentLogEvent(
        runId = runId,
        step = step,
        requestId = requestId,
        packageName = observation.currentApp.packageName,
        screenVersion = observation.uiState.screenVersion,
        fingerprintPrefix = observation.fingerprint.take(10),
        elementCount = observation.uiState.elements.size,
        plannerStatus = plannerStatus,
        plannerAction = plannerAction,
        targetId = targetId,
        validation = validation,
        dispatch = dispatch,
        observation = observationResult,
        stopReason = stopReason,
        requestedScreenVersion = requestedScreenVersion,
        requestedFingerprint = requestedFingerprint,
        currentScreenVersion = currentScreenVersion,
        currentFingerprint = currentFingerprint,
        staleReason = staleReason,
        requestPackageName = requestPackageName,
        livePackageName = livePackageName,
        requestWindowId = requestWindowId,
        liveWindowId = liveWindowId,
        observationFreshness = observationFreshness,
        resolvedHomePackageName = resolvedHomePackageName,
        normalizedAppId = normalizedAppId,
        allowlistDecision = allowlistDecision,
        allowlistReason = allowlistReason
    )

    private fun logStop(runId: String, step: Int, reason: String) {
        logger.log(
            AgentLogEvent(
                runId = runId,
                step = step,
                packageName = null,
                screenVersion = null,
                fingerprintPrefix = null,
                elementCount = null,
                stopReason = reason
            )
        )
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 12
        const val MAX_HISTORY = 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L
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
