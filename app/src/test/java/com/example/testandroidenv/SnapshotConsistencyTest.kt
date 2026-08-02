package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.CompactUiElement
import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.UiRole
import com.example.testandroidenv.agent.perception.ActiveAgentSnapshot
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.AgentSnapshotStore
import com.example.testandroidenv.agent.perception.SnapshotConsistency
import com.example.testandroidenv.agent.perception.TargetEntry
import com.example.testandroidenv.agent.perception.TargetMetadata
import com.example.testandroidenv.agent.perception.TargetRegistrySnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotConsistencyTest {
    @After
    fun clearStore() {
        AgentSnapshotStore.invalidate()
    }

    @Test
    fun packageWindowVersionElementsAndRegistryShareOneOrigin() {
        val snapshot = snapshot(
            observationPackage = LAUNCHER,
            elementSourcePackage = LAUNCHER
        )

        assertNull(
            SnapshotConsistency.validate(
                snapshot.observation,
                snapshot.registry.asView()
            )
        )
        AgentSnapshotStore.replace(snapshot)
        assertEquals(
            LAUNCHER,
            AgentSnapshotStore.currentObservation()?.uiState?.sourcePackageName
        )
    }

    @Test
    fun cachedPackageHeaderCannotBeCombinedWithDifferentWindowElements() {
        val mixed = snapshot(
            observationPackage = LAUNCHER,
            elementSourcePackage = "com.example.testandroidenv"
        )
        val issue = SnapshotConsistency.validate(
            mixed.observation,
            mixed.registry.asView()
        )

        assertEquals("SNAPSHOT_PACKAGE_ELEMENT_INCONSISTENCY", issue?.reason)
        val failure = runCatching { AgentSnapshotStore.replace(mixed) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("PACKAGE_ELEMENT_INCONSISTENCY"))
    }

    private fun snapshot(
        observationPackage: String,
        elementSourcePackage: String
    ): ActiveAgentSnapshot {
        val element = CompactUiElement(
            targetId = TARGET_ID,
            role = UiRole.APP_ICON,
            label = "WhatsApp",
            actions = listOf(PlannerAction.TAP)
        )
        val metadata = TargetMetadata(
            targetId = TARGET_ID,
            role = element.role,
            label = element.label,
            advertisedActions = element.actions.toSet(),
            visibleAtCapture = true,
            enabledAtCapture = true
        )
        val observation = AgentObservation(
            currentApp = CurrentApp(
                packageName = observationPackage,
                displayName = "Home",
                appId = "android_launcher"
            ),
            uiState = CompactUiState(
                screenVersion = VERSION,
                elements = listOf(element),
                sourcePackageName = elementSourcePackage,
                sourceWindowId = WINDOW_ID
            ),
            fingerprint = FINGERPRINT,
            capturedAtEpochMillis = 1,
            activeWindowId = WINDOW_ID,
            resolvedHomePackageName = LAUNCHER
        )
        return ActiveAgentSnapshot(
            observation = observation,
            registry = TargetRegistrySnapshot(
                screenVersion = VERSION,
                fingerprint = FINGERPRINT,
                entries = mapOf(
                    TARGET_ID to TargetEntry(
                        metadata = metadata,
                        screenVersion = VERSION,
                        fingerprint = FINGERPRINT,
                        node = null
                    )
                ),
                packageName = observationPackage,
                activeWindowId = WINDOW_ID
            )
        )
    }

    private companion object {
        const val LAUNCHER = "com.transsion.XOSLauncher.upgrade"
        const val TARGET_ID = "s1_e001_whatsapp"
        const val FINGERPRINT = "launcher-fingerprint"
        const val VERSION = 1L
        const val WINDOW_ID = 17
    }
}
