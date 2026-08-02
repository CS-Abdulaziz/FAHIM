package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.ActionResultCode
import com.example.testandroidenv.agent.contract.BoundPlannerDecision
import com.example.testandroidenv.agent.contract.CompactUiElement
import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.contract.ConversationContextEntry
import com.example.testandroidenv.agent.contract.Planner
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.PlannerDecision
import com.example.testandroidenv.agent.contract.PlannerRequest
import com.example.testandroidenv.agent.contract.TaskStatus
import com.example.testandroidenv.agent.contract.UiRole
import com.example.testandroidenv.agent.execution.ActionExecutor
import com.example.testandroidenv.agent.execution.ExecutionDispatchResult
import com.example.testandroidenv.agent.execution.TargetRuntimeInspector
import com.example.testandroidenv.agent.execution.UiObservationResult
import com.example.testandroidenv.agent.execution.UiObservationSource
import com.example.testandroidenv.agent.loop.AgentLoop
import com.example.testandroidenv.agent.loop.AgentDiagnosticLogger
import com.example.testandroidenv.agent.loop.AgentLogEvent
import com.example.testandroidenv.agent.loop.AgentRuntimeState
import com.example.testandroidenv.agent.loop.AgentStopCode
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.TargetMetadata
import com.example.testandroidenv.agent.perception.TargetRegistryView
import com.example.testandroidenv.agent.validation.DecisionValidator
import com.example.testandroidenv.agent.validation.LiveTargetState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {
    @Test
    fun normalOpenTapCompleteSequenceObservesBeforeEveryReplan() = runBlocking {
        val source = FakeObservationSource(
            observation(1, "controller", "one"),
            ArrayDeque(
                listOf(
                    observation(2, "com.whatsapp", "two", tappable = true),
                    observation(3, "com.whatsapp", "three", composer = true)
                )
            )
        )
        val planner = RecordingPlanner(
            ArrayDeque(
                listOf(
                    decision(PlannerAction.OPEN_APP, target = "WhatsApp"),
                    decision(PlannerAction.TAP, targetId = "e001"),
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.TASK_COMPLETED,
                        message = "Done"
                    )
                )
            )
        )
        val executor = RecordingExecutor()
        val loop = loop(planner, source, executor)

        loop.run("افتح محادثة أحمد محمد")

        assertTrue(loop.state.value is AgentRuntimeState.Completed)
        assertFalse(loop.state.value is AgentRuntimeState.SessionEnded)
        assertEquals(listOf(PlannerAction.OPEN_APP, PlannerAction.TAP), executor.actions)
        assertEquals(3, planner.requests.size)
        assertEquals(6, source.currentCalls)
        assertEquals("افتح محادثة أحمد محمد", planner.requests.first().userInput)
        assertEquals(null, planner.requests[1].userInput)
    }

    @Test
    fun openAppPlanningWorksWhenLauncherHasNoUsefulElements() = runBlocking {
        val emptyLauncher = AgentObservation(
            CurrentApp(
                packageName = "dynamic.launcher",
                displayName = "Home",
                appId = "android_launcher",
                screenName = "Home Screen"
            ),
            CompactUiState(1, emptyList()),
            "empty-launcher",
            1
        )
        val source = FakeObservationSource(
            emptyLauncher,
            ArrayDeque(listOf(observation(2, "com.whatsapp", "whatsapp")))
        )
        val planner = RecordingPlanner(
            ArrayDeque(
                listOf(
                    decision(PlannerAction.OPEN_APP, target = "WhatsApp"),
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.TASK_COMPLETED,
                        message = "Done"
                    )
                )
            )
        )
        val executor = RecordingExecutor()

        loop(planner, source, executor).run("افتح واتساب")

        assertEquals(listOf(PlannerAction.OPEN_APP), executor.actions)
        assertTrue(planner.requests.first().uiState.elements.isEmpty())
    }

    @Test
    fun invalidOrTerminalDecisionNeverReachesExecutor() = runBlocking {
        val source = FakeObservationSource(observation(1, "com.whatsapp", "one"))
        val invented = RecordingExecutor()
        val invalidLoop = loop(
            RecordingPlanner(
                ArrayDeque(listOf(decision(PlannerAction.TAP, targetId = "invented")))
            ),
            source,
            invented
        )

        invalidLoop.run("goal")

        assertTrue(invalidLoop.state.value is AgentRuntimeState.Failed)
        assertEquals(AgentStopCode.INVALID_DECISION,
            (invalidLoop.state.value as AgentRuntimeState.Failed).code)
        assertEquals(
            ActionResultCode.TARGET_NOT_FOUND,
            (invalidLoop.state.value as AgentRuntimeState.Failed)
                .lastActionResult?.resultCode
        )
        assertTrue(invented.actions.isEmpty())
    }

    @Test
    fun staleBoundTargetNeverReachesExecutor() = runBlocking {
        val source = StaleRegistryObservationSource()
        val executor = RecordingExecutor()
        val loop = loop(
            LambdaPlanner {
                decision(PlannerAction.TAP, targetId = "e001")
            },
            source,
            executor
        )

        loop.run("goal")

        assertEquals(
            AgentStopCode.OBSERVATION_ERROR,
            (loop.state.value as AgentRuntimeState.Failed).code
        )
        assertTrue(executor.actions.isEmpty())
    }

    @Test
    fun realScreenChangeWhilePlannerRespondsRejectsOldResponse() = runBlocking {
        val source = ChangesDuringPlannerResponseSource()
        val executor = RecordingExecutor()
        val loop = loop(
            LambdaPlanner {
                decision(PlannerAction.TAP, targetId = "e001")
            },
            source,
            executor
        )

        loop.run("goal")

        val failed = loop.state.value as AgentRuntimeState.Failed
        assertEquals(AgentStopCode.INVALID_DECISION, failed.code)
        assertEquals(ActionResultCode.STALE_TARGET, failed.lastActionResult?.resultCode)
        assertTrue(failed.message.contains("MATERIAL_FINGERPRINT_CHANGED"))
        assertTrue(executor.actions.isEmpty())
    }

    @Test
    fun screenUnchangedIsPassedIntoFreshReplan() = runBlocking {
        val source = FakeObservationSource(
            observation(1, "controller", "same"),
            ArrayDeque(listOf(observation(2, "controller", "same")))
        )
        val planner = RecordingPlanner(
            ArrayDeque(
                listOf(
                    decision(PlannerAction.OPEN_APP, target = "WhatsApp"),
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.FAILED,
                        message = "Stopped"
                    )
                )
            )
        )
        val loop = loop(planner, source, RecordingExecutor())

        loop.run("goal")

        assertEquals(2, planner.requests.size)
        assertEquals(
            ActionResultCode.SCREEN_UNCHANGED,
            planner.requests[1].lastActionResult?.resultCode
        )
    }

    @Test
    fun maximumStepsStopsFurtherPlanning() = runBlocking {
        val source = ChangingObservationSource()
        val planner = LambdaPlanner {
            decision(PlannerAction.BACK)
        }
        val executor = RecordingExecutor()
        val loop = loop(planner, source, executor, maxSteps = 2)

        loop.run("goal")

        assertEquals(2, executor.actions.size)
        assertEquals(
            AgentStopCode.MAX_STEPS_REACHED,
            (loop.state.value as AgentRuntimeState.Failed).code
        )
    }

    @Test
    fun repeatedFailedActionStopsLoop() = runBlocking {
        val source = FakeObservationSource(observation(1, "com.whatsapp", "same"))
        val planner = LambdaPlanner { decision(PlannerAction.BACK) }
        val executor = RecordingExecutor(
            result = ExecutionDispatchResult(
                PlannerAction.BACK,
                false,
                ActionResultCode.EXECUTOR_ERROR,
                "false"
            )
        )
        val loop = loop(planner, source, executor)

        loop.run("goal")

        assertEquals(2, executor.actions.size)
        assertEquals(
            AgentStopCode.REPEATED_ACTION_LOOP,
            (loop.state.value as AgentRuntimeState.Failed).code
        )
    }

    @Test
    fun cancellationPreventsLaterExecution() = runBlocking {
        val enteredPlanner = CompletableDeferred<Unit>()
        val planner = LambdaPlanner {
            enteredPlanner.complete(Unit)
            awaitCancellation()
        }
        val executor = RecordingExecutor()
        val loop = loop(
            planner,
            FakeObservationSource(observation(1, "controller", "one")),
            executor
        )

        val job = launch { loop.run("goal") }
        enteredPlanner.await()
        job.cancelAndJoin()

        assertTrue(loop.state.value is AgentRuntimeState.Cancelled)
        assertTrue(executor.actions.isEmpty())
    }

    @Test
    fun plannerAndExecutorExceptionsBecomeControlledFailures() = runBlocking {
        val plannerFailure = loop(
            LambdaPlanner { error("planner") },
            FakeObservationSource(observation(1, "controller", "one")),
            RecordingExecutor()
        )
        plannerFailure.run("goal")
        assertEquals(
            AgentStopCode.PLANNER_ERROR,
            (plannerFailure.state.value as AgentRuntimeState.Failed).code
        )

        val executorFailure = loop(
            LambdaPlanner { decision(PlannerAction.BACK) },
            FakeObservationSource(observation(1, "controller", "one")),
            RecordingExecutor(throwOnExecute = true)
        )
        executorFailure.run("goal")
        assertEquals(
            AgentStopCode.EXECUTOR_ERROR,
            (executorFailure.state.value as AgentRuntimeState.Failed).code
        )
    }

    @Test
    fun needsUserInputPausesWithoutExecuting() = runBlocking {
        val executor = RecordingExecutor()
        val loop = loop(
            LambdaPlanner {
                decision(
                    PlannerAction.ASK_USER,
                    status = TaskStatus.NEEDS_USER_INPUT,
                    message = "Which one?"
                )
            },
            FakeObservationSource(observation(1, "com.whatsapp", "one")),
            executor
        )

        loop.run("goal")

        assertTrue(loop.state.value is AgentRuntimeState.WaitingForUserInput)
        assertTrue(executor.actions.isEmpty())
    }

    @Test
    fun needsConfirmationPausesWithoutExecuting() = runBlocking {
        val executor = RecordingExecutor()
        val loop = loop(
            LambdaPlanner {
                decision(
                    PlannerAction.CONFIRM_WITH_USER,
                    status = TaskStatus.NEEDS_CONFIRMATION,
                    message = "Confirm?"
                )
            },
            FakeObservationSource(observation(1, "com.whatsapp", "one")),
            executor
        )

        loop.run("goal")

        assertTrue(loop.state.value is AgentRuntimeState.WaitingForConfirmation)
        assertTrue(executor.actions.isEmpty())
    }

    @Test
    fun secondGoalStartsWithFreshGoalScopedActionState() = runBlocking {
        val requests = mutableListOf<PlannerRequest>()
        val planner = LambdaPlanner { request ->
            requests += request
            decision(
                PlannerAction.NONE,
                status = TaskStatus.TASK_COMPLETED,
                message = "Done"
            )
        }
        val loop = loop(
            planner,
            FakeObservationSource(observation(1, "com.whatsapp", "same")),
            RecordingExecutor()
        )

        loop.run("افتح محادثة خالد")
        loop.resetToIdle()
        loop.run("افتح محادثة سارة")

        assertEquals(listOf("افتح محادثة خالد", "افتح محادثة سارة"),
            requests.map { it.activeGoal })
        assertTrue(requests.all { it.userInput == it.activeGoal })
        assertTrue(requests.all { it.actionHistory.isEmpty() })
        assertTrue(requests.all { it.lastActionResult == null })
    }

    @Test
    fun naturalArabicPhrasingsAreForwardedToPlannerUnchanged() = runBlocking {
        val requests = mutableListOf<PlannerRequest>()
        val planner = LambdaPlanner { request ->
            requests += request
            decision(
                PlannerAction.NONE,
                status = TaskStatus.TASK_COMPLETED,
                message = "Planner handled the utterance."
            )
        }
        val loop = loop(
            planner,
            FakeObservationSource(observation(1, "com.whatsapp", "same")),
            RecordingExecutor()
        )
        val utterances = listOf(
            "افتح رسالة خالد",
            "روح لخالد",
            "أبي محادثة محمد",
            "افتح لي الواتس",
            "إيه، افتح محادثة سارة",
            "لا خلاص يعطيك العافية"
        )

        utterances.forEach { utterance ->
            loop.run(utterance)
            loop.resetToIdle()
        }

        assertEquals(utterances, requests.map { it.userInput })
        assertEquals(utterances, requests.map { it.activeGoal })
    }

    @Test
    fun followUpIsSentToPlannerWithPriorConversationContext() = runBlocking {
        val requests = mutableListOf<PlannerRequest>()
        val priorContext = listOf(
            ConversationContextEntry("user", "افتح محادثة خالد"),
            ConversationContextEntry("assistant", "تم فتح المحادثة. تبغى شيء ثاني؟")
        )
        val planner = LambdaPlanner { request ->
            requests += request
            decision(
                PlannerAction.NONE,
                status = TaskStatus.END_SESSION,
                message = "حياك الله."
            )
        }
        val loop = loop(
            planner,
            FakeObservationSource(observation(1, "com.whatsapp", "same")),
            RecordingExecutor()
        )
        val followUp = "لا خلاص يعطيك العافية"

        loop.run(
            initialUserInput = followUp,
            activeGoal = followUp,
            conversationContext = priorContext
        )

        assertEquals(followUp, requests.single().userInput)
        assertEquals(priorContext, requests.single().conversationContext)
        assertTrue(loop.state.value is AgentRuntimeState.SessionEnded)
    }

    @Test
    fun identicalGoalExecutesDifferentPlannerSelectedActionsForDifferentUi() = runBlocking {
        val goal = "افتح رسالة خالد"

        val launcherSource = FakeObservationSource(
            observation(1, "third.party.app", "launcher"),
            ArrayDeque(listOf(observation(2, "com.whatsapp", "opened")))
        )
        var launcherCalls = 0
        val launcherPlanner = LambdaPlanner {
            launcherCalls++
            if (launcherCalls == 1) {
                decision(PlannerAction.OPEN_APP, target = "WhatsApp")
            } else {
                decision(
                    PlannerAction.NONE,
                    status = TaskStatus.TASK_COMPLETED,
                    message = "Done"
                )
            }
        }
        val launcherExecutor = RecordingExecutor()
        loop(launcherPlanner, launcherSource, launcherExecutor).run(goal)

        val chatsSource = FakeObservationSource(
            observation(3, "com.whatsapp", "chats", tappable = true),
            ArrayDeque(listOf(observation(4, "com.whatsapp", "conversation")))
        )
        var chatsCalls = 0
        val chatsPlanner = LambdaPlanner {
            chatsCalls++
            if (chatsCalls == 1) {
                decision(PlannerAction.TAP, targetId = "e001")
            } else {
                decision(
                    PlannerAction.NONE,
                    status = TaskStatus.TASK_COMPLETED,
                    message = "Done"
                )
            }
        }
        val chatsExecutor = RecordingExecutor()
        loop(chatsPlanner, chatsSource, chatsExecutor).run(goal)

        assertEquals(listOf(PlannerAction.OPEN_APP), launcherExecutor.actions)
        assertEquals(listOf(PlannerAction.TAP), chatsExecutor.actions)
    }

    @Test
    fun controllerForegroundWithCachedLauncherSnapshotDoesNotSendPlannerRequest() = runBlocking {
        val source = NoFreshExternalObservationSource()
        val planner = RecordingPlanner(
            ArrayDeque(listOf(decision(PlannerAction.TAP, targetId = "e001")))
        )
        val executor = RecordingExecutor()
        val loop = loop(planner, source, executor)

        loop.run(
            initialUserInput = "Open WhatsApp",
            requireFreshExternalObservationAfterEpochMillis = 1_000
        )

        val failed = loop.state.value as AgentRuntimeState.Failed
        assertEquals(AgentStopCode.ACCESSIBILITY_UNAVAILABLE, failed.code)
        assertTrue(failed.message.contains("cached snapshot was not used"))
        assertTrue(planner.requests.isEmpty())
        assertTrue(executor.actions.isEmpty())
    }

    @Test
    fun backgroundedControllerWithFreshLauncherSnapshotAllowsMatchingTap() = runBlocking {
        val source = FreshLauncherObservationSource()
        var plannerCall = 0
        val planner = LambdaPlanner {
            plannerCall++
            if (plannerCall == 1) {
                decision(PlannerAction.TAP, targetId = "e001")
            } else {
                decision(
                    PlannerAction.NONE,
                    status = TaskStatus.TASK_COMPLETED,
                    message = "Done"
                )
            }
        }
        val executor = RecordingExecutor()
        val loop = loop(planner, source, executor)

        loop.run(
            initialUserInput = "Open WhatsApp",
            requireFreshExternalObservationAfterEpochMillis = 1_000
        )

        assertEquals(listOf(PlannerAction.TAP), executor.actions)
        assertTrue(loop.state.value is AgentRuntimeState.Completed)
    }

    @Test
    fun taskCompletedNoneSkipsExecutorAndWaitsForFinalSpeechBeforeFollowUp() = runBlocking {
        val source = FakeObservationSource(observation(1, "com.whatsapp", "stable"))
        val planner = RecordingPlanner(
            ArrayDeque(
                listOf(
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.TASK_COMPLETED,
                        message = "WhatsApp is open."
                    )
                )
            )
        )
        val executor = RecordingExecutor()
        val events = mutableListOf<AgentLogEvent>()

        val loop = loop(planner, source, executor, logger = events::add)
        loop.run("Open WhatsApp")

        val completed = loop.state.value as AgentRuntimeState.Completed
        assertEquals("WhatsApp is open.", completed.message)
        assertEquals(null, completed.lastActionResult)
        assertTrue(executor.actions.isEmpty())
        assertTrue(planner.requests.single().actionHistory.isEmpty())
        assertTrue(events.any { it.validation == "VALID_TASK_COMPLETION" })
        assertTrue(
            events.any { it.stopReason == "TARGET_VALIDATION_SKIPPED_ACTION_NONE" }
        )
        assertTrue(loop.onCompletionSpeechFinished())
        val waiting = loop.state.value as AgentRuntimeState.WaitingForFollowUp
        assertEquals("WhatsApp is open.", waiting.message)
        assertFalse(loop.onCompletionSpeechFinished())
    }

    @Test
    fun taskCompletionUsesPlannerReasonWhenMessageIsAbsent() = runBlocking {
        val executor = RecordingExecutor()
        val loop = loop(
            LambdaPlanner {
                PlannerDecision(
                    reason = "الواتساب مفتوح، والهدف تحقق.",
                    status = TaskStatus.TASK_COMPLETED,
                    action = PlannerAction.NONE,
                    target = null,
                    targetId = null,
                    value = null,
                    message = null
                )
            },
            FakeObservationSource(observation(1, "com.whatsapp", "stable")),
            executor
        )

        loop.run("Open WhatsApp")

        val completed = loop.state.value as AgentRuntimeState.Completed
        assertEquals("الواتساب مفتوح، والهدف تحقق.", completed.message)
        assertTrue(executor.actions.isEmpty())
    }

    @Test
    fun changedUiDuringTerminalResponseReplansWithoutFakeStaleTarget() = runBlocking {
        val source = TerminalChangesDuringResponseSource()
        val planner = RecordingPlanner(
            ArrayDeque(
                listOf(
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.TASK_COMPLETED,
                        message = "First completion"
                    ),
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.TASK_COMPLETED,
                        message = "Confirmed completion"
                    )
                )
            )
        )
        val executor = RecordingExecutor()
        val events = mutableListOf<AgentLogEvent>()

        val loop = loop(planner, source, executor, logger = events::add)
        loop.run("Open WhatsApp")

        val completed = loop.state.value as AgentRuntimeState.Completed
        assertEquals("Confirmed completion", completed.message)
        assertEquals(null, completed.lastActionResult)
        assertTrue(executor.actions.isEmpty())
        assertEquals(2, planner.requests.size)
        assertEquals(null, planner.requests[1].userInput)
        assertEquals(null, planner.requests[1].lastActionResult)
        assertTrue(planner.requests[1].actionHistory.isEmpty())
        assertTrue(
            events.any { it.validation == "STALE_TERMINAL_REPLAN_REQUIRED" }
        )
    }

    @Test
    fun openedWhatsAppThenStaleTerminalReplansAndPreservesRealActionResult() = runBlocking {
        val source = OpenAppThenTerminalUiChangeSource()
        val planner = RecordingPlanner(
            ArrayDeque(
                listOf(
                    decision(PlannerAction.OPEN_APP, target = "WhatsApp"),
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.TASK_COMPLETED,
                        message = "WhatsApp opened while loading"
                    ),
                    decision(
                        PlannerAction.NONE,
                        status = TaskStatus.TASK_COMPLETED,
                        message = "WhatsApp is open"
                    )
                )
            )
        )
        val executor = RecordingExecutor()

        val loop = loop(planner, source, executor)
        loop.run("Open WhatsApp")

        val completed = loop.state.value as AgentRuntimeState.Completed
        assertEquals(listOf(PlannerAction.OPEN_APP), executor.actions)
        assertEquals(ActionResultCode.ACTION_SUCCEEDED, completed.lastActionResult?.resultCode)
        assertEquals("WhatsApp is open", completed.message)
        assertEquals(3, planner.requests.size)
        assertEquals(null, planner.requests[2].userInput)
        assertEquals(1, planner.requests[2].actionHistory.size)
        assertEquals(PlannerAction.OPEN_APP, planner.requests[2].actionHistory.single().action)
        assertEquals(ActionResultCode.ACTION_SUCCEEDED,
            planner.requests[2].lastActionResult?.resultCode)
    }

    private fun loop(
        planner: Planner,
        source: BaseFakeObservationSource,
        executor: RecordingExecutor,
        maxSteps: Int = 10,
        logger: AgentDiagnosticLogger = AgentDiagnosticLogger { }
    ): AgentLoop = AgentLoop(
        planner = planner,
        observationSource = source,
        validator = DecisionValidator(),
        targetInspector = FakeInspector(source),
        executor = executor,
        registryProvider = { source.registry() },
        logger = logger,
        maxSteps = maxSteps
    )

    private fun decision(
        action: PlannerAction,
        status: TaskStatus = TaskStatus.CONTINUE,
        target: String? = null,
        targetId: String? = null,
        value: String? = null,
        message: String? = null
    ) = PlannerDecision(
        reason = "Operational reason.",
        status = status,
        action = action,
        target = target,
        targetId = targetId,
        value = value,
        message = message
    )

    private fun observation(
        version: Long,
        packageName: String,
        fingerprint: String,
        tappable: Boolean = false,
        composer: Boolean = false
    ): AgentObservation {
        val elements = mutableListOf(
            CompactUiElement("e000", UiRole.TEXT, "Screen", emptyList())
        )
        if (tappable) {
            elements += CompactUiElement(
                "e001",
                UiRole.LIST_ITEM,
                "أحمد محمد",
                listOf(PlannerAction.TAP)
            )
        }
        if (composer) {
            elements += CompactUiElement(
                "e002",
                UiRole.TEXT_FIELD,
                "اكتب رسالة",
                listOf(PlannerAction.TYPE)
            )
        }
        return AgentObservation(
            CurrentApp(packageName, packageName),
            CompactUiState(version, elements),
            fingerprint,
            version,
            activeWindowId = 42
        )
    }

    private abstract class BaseFakeObservationSource : UiObservationSource {
        var currentCalls = 0
        abstract var current: AgentObservation

        open fun registry(): TargetRegistryView = TargetRegistryView(
            current.uiState.screenVersion,
            current.fingerprint,
            current.uiState.elements.associate { element ->
                element.targetId to TargetMetadata(
                    element.targetId,
                    element.role,
                    element.label,
                    element.actions.toSet(),
                    true,
                    true
                )
            },
            packageName = current.currentApp.packageName,
            activeWindowId = current.activeWindowId
        )
    }

    private inner class NoFreshExternalObservationSource : BaseFakeObservationSource() {
        override var current = launcherObservation(version = 1, capturedAt = 100)

        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            return current
        }

        override suspend fun awaitFreshExternalObservation(
            capturedAfterEpochMillis: Long
        ): AgentObservation? = null

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult = error("A cached observation must never execute.")
    }

    private inner class FreshLauncherObservationSource : BaseFakeObservationSource() {
        override var current = launcherObservation(version = 1, capturedAt = 1_001)

        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            return current
        }

        override suspend fun awaitFreshExternalObservation(
            capturedAfterEpochMillis: Long
        ): AgentObservation? = current.takeIf {
            it.capturedAtEpochMillis >= capturedAfterEpochMillis
        }

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult {
            current = observation(
                version = 2,
                packageName = "com.whatsapp",
                fingerprint = "whatsapp-opened"
            )
            return UiObservationResult(
                observation = current,
                changed = true,
                resultCode = ActionResultCode.ACTION_SUCCEEDED,
                details = "changed"
            )
        }
    }

    private fun launcherObservation(
        version: Long,
        capturedAt: Long
    ) = AgentObservation(
        currentApp = CurrentApp(
            packageName = "com.android.launcher3",
            displayName = "Launcher",
            appId = "android_launcher",
            screenName = "Home"
        ),
        uiState = CompactUiState(
            screenVersion = version,
            elements = listOf(
                CompactUiElement(
                    targetId = "e001",
                    role = UiRole.APP_ICON,
                    label = "WhatsApp",
                    actions = listOf(PlannerAction.TAP)
                )
            )
        ),
        fingerprint = "launcher",
        capturedAtEpochMillis = capturedAt,
        activeWindowId = 17,
        resolvedHomePackageName = "com.android.launcher3"
    )

    private inner class StaleRegistryObservationSource : BaseFakeObservationSource() {
        override var current = observation(
            version = 1,
            packageName = "com.whatsapp",
            fingerprint = "current",
            tappable = true
        )

        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            return current
        }

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult = error("A stale target must not execute.")

        override fun registry(): TargetRegistryView {
            return super.registry().copy(screenVersion = current.uiState.screenVersion + 1)
        }
    }

    private inner class ChangesDuringPlannerResponseSource : BaseFakeObservationSource() {
        override var current = observation(
            version = 1,
            packageName = "com.whatsapp",
            fingerprint = "chat-list",
            tappable = true
        )

        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            if (currentCalls == 2) {
                current = observation(
                    version = 2,
                    packageName = "com.whatsapp",
                    fingerprint = "conversation",
                    composer = true
                )
            }
            return current
        }

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult = error("A changed response snapshot must not execute.")
    }

    private inner class TerminalChangesDuringResponseSource : BaseFakeObservationSource() {
        override var current = observation(
            version = 1,
            packageName = "com.whatsapp",
            fingerprint = "whatsapp-loading"
        )

        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            if (currentCalls == 2) {
                current = observation(
                    version = 2,
                    packageName = "com.whatsapp",
                    fingerprint = "whatsapp-stable"
                )
            }
            return current
        }

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult = error("Terminal decisions must not execute.")
    }

    private inner class OpenAppThenTerminalUiChangeSource : BaseFakeObservationSource() {
        override var current = observation(
            version = 1,
            packageName = "third.party.app",
            fingerprint = "unsupported-app"
        )

        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            if (currentCalls == 4) {
                current = observation(
                    version = 3,
                    packageName = "com.whatsapp",
                    fingerprint = "whatsapp-stable"
                )
            }
            return current
        }

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult {
            current = observation(
                version = 2,
                packageName = "com.whatsapp",
                fingerprint = "whatsapp-loading"
            )
            return UiObservationResult(
                observation = current,
                changed = true,
                resultCode = ActionResultCode.ACTION_SUCCEEDED,
                details = "WhatsApp opened."
            )
        }
    }

    private class FakeObservationSource(
        override var current: AgentObservation,
        private val next: ArrayDeque<AgentObservation> = ArrayDeque()
    ) : BaseFakeObservationSource() {
        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            return current
        }

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult {
            if (next.isNotEmpty()) current = next.removeFirst()
            val changed = source.currentApp.packageName != current.currentApp.packageName ||
                source.fingerprint != current.fingerprint
            return UiObservationResult(
                current,
                changed,
                if (changed) {
                    ActionResultCode.ACTION_SUCCEEDED
                } else {
                    ActionResultCode.SCREEN_UNCHANGED
                },
                if (changed) "changed" else "unchanged"
            )
        }
    }

    private inner class ChangingObservationSource : BaseFakeObservationSource() {
        override var current = observation(1, "controller", "fp1")

        override suspend fun currentObservation(): AgentObservation {
            currentCalls++
            return current
        }

        override suspend fun awaitPostActionObservation(
            source: AgentObservation,
            action: PlannerAction
        ): UiObservationResult {
            val nextVersion = source.uiState.screenVersion + 1
            current = observation(nextVersion, "controller", "fp$nextVersion")
            return UiObservationResult(
                current,
                true,
                ActionResultCode.ACTION_SUCCEEDED,
                "changed"
            )
        }
    }

    private class RecordingPlanner(
        private val decisions: ArrayDeque<PlannerDecision>
    ) : Planner {
        val requests = mutableListOf<PlannerRequest>()
        override suspend fun plan(request: PlannerRequest): PlannerDecision {
            requests += request
            return decisions.removeFirst()
        }
    }

    private class LambdaPlanner(
        private val block: suspend (PlannerRequest) -> PlannerDecision
    ) : Planner {
        override suspend fun plan(request: PlannerRequest): PlannerDecision = block(request)
    }

    private class RecordingExecutor(
        private val result: ExecutionDispatchResult? = null,
        private val throwOnExecute: Boolean = false
    ) : ActionExecutor {
        val actions = mutableListOf<PlannerAction>()
        override suspend fun execute(
            boundDecision: BoundPlannerDecision
        ): ExecutionDispatchResult {
            if (throwOnExecute) error("executor")
            actions += boundDecision.decision.action
            return result ?: ExecutionDispatchResult(
                boundDecision.decision.action,
                true,
                null,
                "dispatched"
            )
        }
    }

    private class FakeInspector(
        private val source: BaseFakeObservationSource
    ) : TargetRuntimeInspector {
        override suspend fun inspect(
            boundDecision: BoundPlannerDecision
        ): LiveTargetState {
            val registry = source.registry()
            val target = boundDecision.decision.targetId?.let(registry.targets::get)
            return LiveTargetState(
                exists = target != null,
                screenVersion = registry.screenVersion,
                fingerprint = registry.fingerprint,
                visible = target != null,
                enabled = target != null,
                supportedActions = target?.advertisedActions.orEmpty(),
                packageName = source.current.currentApp.packageName,
                activeWindowId = source.current.activeWindowId,
                observationWasFresh = true,
                appId = source.current.currentApp.appId,
                resolvedHomePackageName = source.current.resolvedHomePackageName,
                allowlistAllowed = true,
                allowlistReason = "TEST_MATCH"
            )
        }
    }
}
