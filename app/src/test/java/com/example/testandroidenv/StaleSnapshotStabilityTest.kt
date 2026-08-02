package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.CompactUiElement
import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.UiRole
import com.example.testandroidenv.agent.loop.PlannerRequestSnapshotBinding
import com.example.testandroidenv.agent.loop.PlannerSnapshotGuard
import com.example.testandroidenv.agent.loop.SnapshotFreshness
import com.example.testandroidenv.agent.loop.StaleSnapshotReason
import com.example.testandroidenv.agent.perception.ActiveAgentSnapshot
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.AgentSnapshotStore
import com.example.testandroidenv.agent.perception.SnapshotReplaceResult
import com.example.testandroidenv.agent.perception.TargetEntry
import com.example.testandroidenv.agent.perception.TargetMetadata
import com.example.testandroidenv.agent.perception.TargetRegistrySnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleSnapshotStabilityTest {
    @After
    fun clearStore() {
        AgentSnapshotStore.invalidate()
    }

    @Test
    fun repeatedIdenticalAccessibilityEventsRetainVersionAndRegistry() {
        val first = snapshot(version = 7, fingerprint = "material-chats", label = "أحمد")
        assertTrue(
            AgentSnapshotStore.replace(first) is SnapshotReplaceResult.MateriallyChanged
        )
        val requestedRegistry = requireNotNull(AgentSnapshotStore.currentRegistryView())
        val binding = PlannerRequestSnapshotBinding(
            requestId = "req-identical",
            observation = first.observation,
            registry = requestedRegistry
        )

        val repeatedEvent = snapshot(
            version = 7,
            fingerprint = "material-chats",
            label = "أحمد",
            capturedAtEpochMillis = 8_000
        )
        assertTrue(
            AgentSnapshotStore.replace(repeatedEvent) is
                SnapshotReplaceResult.MateriallyUnchanged
        )

        assertEquals(7L, AgentSnapshotStore.currentObservation()?.uiState?.screenVersion)
        assertEquals(
            8_000L,
            AgentSnapshotStore.currentObservation()?.capturedAtEpochMillis
        )
        assertSame(requestedRegistry, AgentSnapshotStore.currentRegistryView())
        assertEquals(
            SnapshotFreshness.Fresh,
            PlannerSnapshotGuard.evaluate(
                binding = binding,
                activeRequestId = "req-identical",
                currentObservation = AgentSnapshotStore.currentObservation(),
                currentRegistry = AgentSnapshotStore.currentRegistryView(),
                requestedTargetId = TARGET_ID
            )
        )
    }

    @Test
    fun realWhatsAppScreenChangeInvalidatesOldResponse() {
        val requested = snapshot(11, "chat-list-fingerprint", "أحمد")
        val current = snapshot(12, "conversation-fingerprint", "اكتب رسالة")
        val result = PlannerSnapshotGuard.evaluate(
            binding = PlannerRequestSnapshotBinding(
                "req-old-screen",
                requested.observation,
                requested.registry.asView()
            ),
            activeRequestId = "req-old-screen",
            currentObservation = current.observation,
            currentRegistry = current.registry.asView(),
            requestedTargetId = TARGET_ID
        )

        assertTrue(result is SnapshotFreshness.Stale)
        assertEquals(
            StaleSnapshotReason.MATERIAL_FINGERPRINT_CHANGED,
            (result as SnapshotFreshness.Stale).reason
        )
    }

    @Test
    fun lateResponseFromOlderRequestIsRejected() {
        val current = snapshot(20, "same-material", "Search")
        val result = PlannerSnapshotGuard.evaluate(
            binding = PlannerRequestSnapshotBinding(
                "req-old",
                current.observation,
                current.registry.asView()
            ),
            activeRequestId = "req-new",
            currentObservation = current.observation,
            currentRegistry = current.registry.asView(),
            requestedTargetId = TARGET_ID
        )

        assertTrue(result is SnapshotFreshness.Stale)
        assertEquals(
            StaleSnapshotReason.REQUEST_SUPERSEDED,
            (result as SnapshotFreshness.Stale).reason
        )
    }

    @Test
    fun matchingResponseIsAcceptedForNormalExecution() {
        val current = snapshot(30, "stable-search", "Search")
        val registry = current.registry.asView()
        assertEquals(
            SnapshotFreshness.Fresh,
            PlannerSnapshotGuard.evaluate(
                binding = PlannerRequestSnapshotBinding(
                    "req-current",
                    current.observation,
                    registry
                ),
                activeRequestId = "req-current",
                currentObservation = current.observation,
                currentRegistry = registry,
                requestedTargetId = TARGET_ID
            )
        )
    }

    private fun snapshot(
        version: Long,
        fingerprint: String,
        label: String,
        capturedAtEpochMillis: Long = version
    ): ActiveAgentSnapshot {
        val metadata = TargetMetadata(
            targetId = TARGET_ID,
            role = UiRole.BUTTON,
            label = label,
            advertisedActions = setOf(PlannerAction.TAP),
            visibleAtCapture = true,
            enabledAtCapture = true
        )
        val observation = AgentObservation(
            currentApp = CurrentApp(
                packageName = WHATSAPP_PACKAGE,
                displayName = "WhatsApp",
                appId = "whatsapp",
                screenName = "Chats"
            ),
            uiState = CompactUiState(
                screenVersion = version,
                elements = listOf(
                    CompactUiElement(
                        targetId = TARGET_ID,
                        role = UiRole.BUTTON,
                        label = label,
                        actions = listOf(PlannerAction.TAP)
                    )
                )
            ),
            fingerprint = fingerprint,
            capturedAtEpochMillis = capturedAtEpochMillis,
            activeWindowId = 42
        )
        return ActiveAgentSnapshot(
            observation = observation,
            registry = TargetRegistrySnapshot(
                screenVersion = version,
                fingerprint = fingerprint,
                entries = mapOf(
                    TARGET_ID to TargetEntry(
                        metadata = metadata,
                        screenVersion = version,
                        fingerprint = fingerprint,
                        node = null
                    )
                ),
                packageName = WHATSAPP_PACKAGE,
                activeWindowId = 42
            )
        )
    }

    private companion object {
        const val TARGET_ID = "s7_e001_abc123"
        const val WHATSAPP_PACKAGE = "com.whatsapp"
    }
}
