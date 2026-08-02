package com.example.testandroidenv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccessibilitySnapshot(
    val packageName: String,
    val windowTitle: String?,
    val capturedAtEpochMillis: Long,
    val serializedTree: String,
    val nodes: List<AccessibilityNodeSnapshot>,
    val treeTruncated: Boolean
) {
    val nodeCount: Int get() = nodes.size

    fun isFresh(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val age = nowEpochMillis - capturedAtEpochMillis
        return age in 0..DemoConfig.SNAPSHOT_FRESHNESS_MS
    }
}

enum class AccessibilityConnectionState {
    DISCONNECTED,
    CONNECTED,
    INTERRUPTED
}

/**
 * Process-local bridge between the system-created AccessibilityService and the activity.
 *
 * Only immutable text is retained. Live AccessibilityNodeInfo instances must never be stored here.
 */
object AccessibilitySnapshotRepository {
    private val mutableSnapshot = MutableStateFlow<AccessibilitySnapshot?>(null)
    val snapshot: StateFlow<AccessibilitySnapshot?> = mutableSnapshot.asStateFlow()
    private val mutableConnectionState =
        MutableStateFlow(AccessibilityConnectionState.DISCONNECTED)
    val connectionState: StateFlow<AccessibilityConnectionState> =
        mutableConnectionState.asStateFlow()

    fun publish(snapshot: AccessibilitySnapshot) {
        mutableSnapshot.value = snapshot
    }

    fun setConnectionState(state: AccessibilityConnectionState) {
        mutableConnectionState.value = state
    }
}

internal object AccessibilityCapturePolicy {
    fun isExternalTarget(rootPackageName: String?, controllerPackageName: String): Boolean {
        return !rootPackageName.isNullOrBlank() && rootPackageName != controllerPackageName
    }
}
