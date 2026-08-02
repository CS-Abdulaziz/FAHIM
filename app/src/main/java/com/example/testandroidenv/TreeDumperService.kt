package com.example.testandroidenv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.testandroidenv.agent.perception.AgentObservation
import com.example.testandroidenv.agent.perception.AgentSnapshotStore
import com.example.testandroidenv.agent.perception.ActiveAgentSnapshot
import com.example.testandroidenv.agent.perception.AndroidCompactUiCapture
import com.example.testandroidenv.agent.perception.AndroidHomePackageResolver
import com.example.testandroidenv.agent.perception.SnapshotReplaceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

class TreeDumperService : AccessibilityService() {
    private val eventHandler = Handler(Looper.getMainLooper())
    private val stableObservationWaiters = mutableSetOf<StableObservationWaiter>()
    private val publishDebouncedActiveWindow = Runnable {
        val observation = captureDebouncedActiveWindow()
        completeEligibleStableObservationWaiters(observation)
    }

    companion object {
        private const val TAG = "TreeDumper"
        private const val EVENT_DEBOUNCE_MS = 250L

        @Volatile
        internal var currentInstance: TreeDumperService? = null
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            return
        }

        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "Active window root is not available")
            return
        }

        val rootPackageName = root.packageName?.toString() ?: event.packageName?.toString()
        if (!AccessibilityCapturePolicy.isExternalTarget(rootPackageName, packageName)) {
            Log.d(TAG, "Ignoring controller accessibility window")
            return
        }
        val rootWindowId = safe(-1) { root.windowId }
        if (event.windowId >= 0 && rootWindowId >= 0 && event.windowId != rootWindowId) {
            Log.d(
                TAG,
                "Ignoring inactive window event eventWindow=${event.windowId} " +
                    "activeWindow=$rootWindowId"
            )
            return
        }
        scheduleStableObservation()
    }

    override fun onInterrupt() {
        cancelPendingObservation()
        AgentSnapshotStore.invalidate()
        AccessibilitySnapshotRepository.setConnectionState(
            AccessibilityConnectionState.INTERRUPTED
        )
        Log.w(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = this
        AccessibilitySnapshotRepository.setConnectionState(
            AccessibilityConnectionState.CONNECTED
        )
        Log.i(TAG, "Accessibility tree service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        currentInstance = null
        cancelPendingObservation()
        AgentSnapshotStore.invalidate()
        AccessibilitySnapshotRepository.setConnectionState(
            AccessibilityConnectionState.DISCONNECTED
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        currentInstance = null
        cancelPendingObservation()
        AgentSnapshotStore.invalidate()
        AccessibilitySnapshotRepository.setConnectionState(
            AccessibilityConnectionState.DISCONNECTED
        )
        super.onDestroy()
    }

    internal fun captureCurrentAgentObservation(): AgentObservation? {
        val root = rootInActiveWindow ?: return null
        val rootPackageName = root.packageName?.toString()
        if (!AccessibilityCapturePolicy.isExternalTarget(rootPackageName, packageName)) {
            return null
        }
        return captureAgentObservation(root, rootPackageName)
    }

    internal fun requestStableAgentObservation(
        capturedAfterEpochMillis: Long? = null
    ): Deferred<AgentObservation?> {
        check(Looper.myLooper() == Looper.getMainLooper())
        val deferred = CompletableDeferred<AgentObservation?>()
        val waiter = StableObservationWaiter(
            deferred = deferred,
            capturedAfterEpochMillis = capturedAfterEpochMillis
        )
        stableObservationWaiters += waiter
        deferred.invokeOnCompletion {
            if (deferred.isCancelled) {
                eventHandler.post { stableObservationWaiters.remove(waiter) }
            }
        }
        scheduleStableObservation()
        return deferred
    }

    internal fun performGlobalBackForAgent(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Produces a short-lived, freshly traversed registry for immediate validation/execution.
     * Callers must release its registry after using the resolved AccessibilityNodeInfo.
     */
    internal fun captureFreshAgentSnapshotForResolution(): ActiveAgentSnapshot? {
        val root = rootInActiveWindow ?: return null
        val rootPackageName = root.packageName?.toString()
        val resolvedHomePackageName = resolvedLauncherPackage()
        return AndroidCompactUiCapture.capture(
            root = root,
            packageName = rootPackageName,
            appName = readableAppName(rootPackageName),
            screenName = activeWindowTitle(root),
            launcherPackageName = resolvedHomePackageName,
            retainExecutableNodes = true
        ).also(::logForegroundIdentity)
    }

    private fun captureAgentObservation(
        root: android.view.accessibility.AccessibilityNodeInfo,
        rootPackageName: String?
    ): AgentObservation? {
        return try {
            val resolvedHomePackageName = resolvedLauncherPackage()
            val active = AndroidCompactUiCapture.capture(
                root = root,
                packageName = rootPackageName,
                appName = readableAppName(rootPackageName),
                screenName = activeWindowTitle(root),
                launcherPackageName = resolvedHomePackageName,
                retainExecutableNodes = false
            )
            logForegroundIdentity(active)
            when (val replacement = AgentSnapshotStore.replace(active)) {
                is SnapshotReplaceResult.MateriallyChanged -> {
                    Log.d(
                        TAG,
                        "Published material UI package=${rootPackageName ?: "unknown"} " +
                            "window=${replacement.activeSnapshot.observation.activeWindowId} " +
                            "version=${replacement.activeSnapshot.observation.uiState.screenVersion} " +
                            "elements=${replacement.activeSnapshot.observation.uiState.elements.size} " +
                            "fingerprint=${
                                replacement.activeSnapshot.observation.fingerprint.take(10)
                            }"
                    )
                }
                is SnapshotReplaceResult.MateriallyUnchanged -> {
                    Log.d(
                        TAG,
                        "Retained material UI package=${rootPackageName ?: "unknown"} " +
                            "window=${replacement.activeSnapshot.observation.activeWindowId} " +
                            "version=${replacement.activeSnapshot.observation.uiState.screenVersion} " +
                            "fingerprint=${
                                replacement.activeSnapshot.observation.fingerprint.take(10)
                            }"
                    )
                }
            }
            AgentSnapshotStore.currentObservation()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Compact observation failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun readableAppName(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun activeWindowTitle(
        root: android.view.accessibility.AccessibilityNodeInfo
    ): String? {
        return try {
            root.window?.title?.toString()?.trim()?.takeIf(String::isNotEmpty)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun resolvedLauncherPackage(): String? {
        return AndroidHomePackageResolver.resolve(packageManager)
    }

    private fun logForegroundIdentity(snapshot: ActiveAgentSnapshot) {
        val observation = snapshot.observation
        Log.i(
            TAG,
            "Foreground identity foregroundPackage=${observation.currentApp.packageName} " +
                "resolvedHomePackage=${observation.resolvedHomePackageName} " +
                "normalizedAppId=${observation.currentApp.appId}"
        )
    }

    private fun scheduleStableObservation() {
        eventHandler.removeCallbacks(publishDebouncedActiveWindow)
        eventHandler.postDelayed(publishDebouncedActiveWindow, EVENT_DEBOUNCE_MS)
    }

    private fun captureDebouncedActiveWindow(): AgentObservation? {
        val root = rootInActiveWindow ?: return null
        val rootPackageName = root.packageName?.toString()
        if (!AccessibilityCapturePolicy.isExternalTarget(rootPackageName, packageName)) {
            Log.d(TAG, "Stable capture withheld while controller is foreground")
            return null
        }
        val observation = captureAgentObservation(root, rootPackageName)
        publishLegacyTree(root, requireNotNull(rootPackageName))
        return observation
    }

    private fun publishLegacyTree(
        root: android.view.accessibility.AccessibilityNodeInfo,
        rootPackageName: String
    ) {
        val serializedTree = AccessibilityTreeSerializer.capture(root)
        if (serializedTree.nodes.isEmpty()) return
        val capturedAt = System.currentTimeMillis()
        val windowTitle = activeWindowTitle(root)
        AccessibilitySnapshotRepository.publish(
            AccessibilitySnapshot(
                packageName = rootPackageName,
                windowTitle = windowTitle,
                capturedAtEpochMillis = capturedAt,
                serializedTree = AccessibilityTreeSerializer.withScreenMetadata(
                    packageName = rootPackageName,
                    windowTitle = windowTitle,
                    capturedAtEpochMillis = capturedAt,
                    tree = serializedTree
                ),
                nodes = serializedTree.nodes,
                treeTruncated = serializedTree.truncated
            )
        )
        Log.d(
            TAG,
            "Captured debounced external tree package=$rootPackageName " +
                "nodes=${serializedTree.nodes.size} truncated=${serializedTree.truncated}"
        )
    }

    private fun completeEligibleStableObservationWaiters(observation: AgentObservation?) {
        val eligible = stableObservationWaiters.filter { waiter ->
            observation != null &&
                (
                    waiter.capturedAfterEpochMillis == null ||
                        observation.capturedAtEpochMillis >= waiter.capturedAfterEpochMillis
                    )
        }
        stableObservationWaiters.removeAll(eligible.toSet())
        eligible.forEach { waiter ->
            if (!waiter.deferred.isCompleted) waiter.deferred.complete(observation)
        }
    }

    private fun cancelPendingObservation() {
        eventHandler.removeCallbacks(publishDebouncedActiveWindow)
        val waiters = stableObservationWaiters.toList()
        stableObservationWaiters.clear()
        waiters.forEach { waiter ->
            if (!waiter.deferred.isCompleted) waiter.deferred.complete(null)
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T {
        return try {
            block()
        } catch (_: RuntimeException) {
            default
        }
    }

    private data class StableObservationWaiter(
        val deferred: CompletableDeferred<AgentObservation?>,
        val capturedAfterEpochMillis: Long?
    )
}
