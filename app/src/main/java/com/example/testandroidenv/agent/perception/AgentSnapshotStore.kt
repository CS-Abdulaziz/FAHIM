package com.example.testandroidenv.agent.perception

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.CurrentApp
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.UiRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class AgentObservation(
    val currentApp: CurrentApp,
    val uiState: CompactUiState,
    val fingerprint: String,
    val capturedAtEpochMillis: Long,
    val activeWindowId: Int? = null,
    val resolvedHomePackageName: String? = null,
    val captureDurationMs: Long? = null,
    val normalizationDurationMs: Long? = null
)

data class TargetMetadata(
    val targetId: String,
    val role: UiRole,
    val label: String,
    val advertisedActions: Set<PlannerAction>,
    val visibleAtCapture: Boolean,
    val enabledAtCapture: Boolean
)

data class TargetEntry(
    val metadata: TargetMetadata,
    val screenVersion: Long,
    val fingerprint: String,
    internal val node: AccessibilityNodeInfo?
)

data class TargetRegistrySnapshot(
    val screenVersion: Long,
    val fingerprint: String,
    val entries: Map<String, TargetEntry>,
    val packageName: String? = null,
    val activeWindowId: Int? = null
) {
    private val readOnlyView by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TargetRegistryView(
            screenVersion = screenVersion,
            fingerprint = fingerprint,
            targets = entries.mapValues { it.value.metadata },
            packageName = packageName,
            activeWindowId = activeWindowId
        )
    }

    internal fun asView(): TargetRegistryView = readOnlyView

    @Suppress("DEPRECATION")
    internal fun release() {
        if (entries.values.none { it.node != null }) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            entries.values
                .mapNotNull(TargetEntry::node)
                .distinctBy(System::identityHashCode)
                .forEach(AccessibilityNodeInfo::recycle)
        }
    }
}

data class ActiveAgentSnapshot(
    val observation: AgentObservation,
    val registry: TargetRegistrySnapshot
)

data class TargetRegistryView(
    val screenVersion: Long,
    val fingerprint: String,
    val targets: Map<String, TargetMetadata>,
    val packageName: String? = null,
    val activeWindowId: Int? = null
)

sealed interface SnapshotReplaceResult {
    val activeSnapshot: ActiveAgentSnapshot

    data class MateriallyChanged(
        override val activeSnapshot: ActiveAgentSnapshot
    ) : SnapshotReplaceResult

    data class MateriallyUnchanged(
        override val activeSnapshot: ActiveAgentSnapshot
    ) : SnapshotReplaceResult
}

/** Atomically owns the Planner-visible state and the matching executable-node registry. */
object AgentSnapshotStore {
    private val active = AtomicReference<ActiveAgentSnapshot?>(null)
    private val replacementLock = Any()
    private val mutableObservations = MutableStateFlow<AgentObservation?>(null)
    val observations: StateFlow<AgentObservation?> = mutableObservations.asStateFlow()

    fun replace(snapshot: ActiveAgentSnapshot): SnapshotReplaceResult = synchronized(
        replacementLock
    ) {
        require(snapshot.observation.uiState.screenVersion == snapshot.registry.screenVersion)
        require(snapshot.observation.fingerprint == snapshot.registry.fingerprint)
        require(snapshot.observation.currentApp.packageName == snapshot.registry.packageName)
        require(snapshot.observation.activeWindowId == snapshot.registry.activeWindowId)
        require(SnapshotConsistency.validate(snapshot) == null) {
            val issue = requireNotNull(SnapshotConsistency.validate(snapshot))
            "${issue.reason}: ${issue.details}"
        }

        val previous = active.get()
        if (previous != null && previous.hasSameMaterialUi(snapshot)) {
            val reconfirmed = previous.copy(
                observation = previous.observation.copy(
                    capturedAtEpochMillis = snapshot.observation.capturedAtEpochMillis,
                    captureDurationMs = snapshot.observation.captureDurationMs,
                    normalizationDurationMs = snapshot.observation.normalizationDurationMs
                )
            )
            active.set(reconfirmed)
            mutableObservations.value = reconfirmed.observation
            snapshot.registry.release()
            return@synchronized SnapshotReplaceResult.MateriallyUnchanged(reconfirmed)
        }

        active.set(snapshot)
        mutableObservations.value = snapshot.observation
        previous?.registry?.release()
        SnapshotReplaceResult.MateriallyChanged(snapshot)
    }

    fun currentObservation(): AgentObservation? = active.get()?.observation

    fun currentRegistryView(): TargetRegistryView? {
        val registry = active.get()?.registry ?: return null
        return registry.asView()
    }

    fun registryViewFor(observation: AgentObservation): TargetRegistryView? {
        val snapshot = active.get() ?: return null
        if (snapshot.observation != observation) return null
        return snapshot.registry.asView()
    }

    internal fun currentActiveSnapshot(): ActiveAgentSnapshot? = active.get()

    fun invalidate() {
        synchronized(replacementLock) {
            val previous = active.getAndSet(null)
            mutableObservations.value = null
            previous?.registry?.release()
        }
    }

    private fun ActiveAgentSnapshot.hasSameMaterialUi(other: ActiveAgentSnapshot): Boolean {
        return observation.currentApp.packageName == other.observation.currentApp.packageName &&
            observation.currentApp.appId == other.observation.currentApp.appId &&
            observation.resolvedHomePackageName == other.observation.resolvedHomePackageName &&
            observation.activeWindowId == other.observation.activeWindowId &&
            observation.uiState.screenVersion == other.observation.uiState.screenVersion &&
            observation.fingerprint == other.observation.fingerprint
    }
}

object ScreenVersionGenerator {
    private val value = AtomicLong(0)
    private val lock = Any()
    private var lastFingerprint: String? = null

    fun next(): Long {
        return value.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "In-process screen version exhausted" }
            current + 1
        }
    }

    fun versionFor(fingerprint: String): Long = synchronized(lock) {
        if (lastFingerprint != fingerprint) {
            lastFingerprint = fingerprint
            next()
        } else {
            value.get().coerceAtLeast(1)
        }
    }
}
