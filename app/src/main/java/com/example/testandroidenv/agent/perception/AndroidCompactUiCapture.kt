package com.example.testandroidenv.agent.perception

import android.os.Build
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.testandroidenv.DemoConfig
import java.util.ArrayDeque

object AndroidCompactUiCapture {
    private data class WorkItem(
        val node: AccessibilityNodeInfo,
        val parentKey: Int?,
        val depth: Int,
        val owned: Boolean
    )

    @Suppress("DEPRECATION")
    fun capture(
        root: AccessibilityNodeInfo,
        packageName: String?,
        appName: String?,
        screenName: String? = null,
        launcherPackageName: String? = null,
        retainExecutableNodes: Boolean = false
    ): ActiveAgentSnapshot {
        val captureStartedNanos = SystemClock.elapsedRealtimeNanos()
        val nodes = mutableListOf<UiNodeSnapshot>()
        val executableCopies = mutableMapOf<Int, AccessibilityNodeInfo>()
        val stack = ArrayDeque<WorkItem>()
        stack.addLast(WorkItem(root, parentKey = null, depth = 0, owned = false))
        var nextKey = 0

        try {
            while (stack.isNotEmpty() && nodes.size < DemoConfig.MAX_TREE_NODES) {
                val work = stack.removeLast()
                val node = work.node
                try {
                    if (work.depth > DemoConfig.MAX_TREE_DEPTH) continue
                    val key = nextKey++
                    val actionIds = safe(emptySet()) {
                        node.actionList.map { it.id }.toSet()
                    }
                    val clickable = safe(false) { node.isClickable }
                    val editable = safe(false) { node.isEditable }
                    val supportsClick =
                        clickable || AccessibilityNodeInfo.ACTION_CLICK in actionIds
                    val supportsSetText =
                        editable && AccessibilityNodeInfo.ACTION_SET_TEXT in actionIds
                    val scrollForward =
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actionIds
                    val scrollBackward =
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actionIds
                    val bounds = Rect()
                    safe(Unit) { node.getBoundsInScreen(bounds) }

                    nodes += UiNodeSnapshot(
                        key = key,
                        parentKey = work.parentKey,
                        traversalIndex = nodes.size,
                        visible = safe(false) { node.isVisibleToUser },
                        enabled = safe(false) { node.isEnabled },
                        password = safe(false) { node.isPassword },
                        className = safe<CharSequence?>(null) { node.className }?.toString(),
                        text = safe<CharSequence?>(null) { node.text }?.toString(),
                        contentDescription = safe<CharSequence?>(null) {
                            node.contentDescription
                        }?.toString(),
                        hintText = safe<CharSequence?>(null) { node.hintText }?.toString(),
                        clickable = clickable,
                        supportsClick = supportsClick,
                        editable = editable,
                        supportsSetText = supportsSetText,
                        scrollForward = scrollForward,
                        scrollBackward = scrollBackward,
                        checkable = safe(false) { node.isCheckable },
                        checked = safe(false) { node.isChecked },
                        focusable = safe(false) { node.isFocusable },
                        selected = safe(false) { node.isSelected },
                        heading = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            safe(false) { node.isHeading },
                        viewId = safe<String?>(null) { node.viewIdResourceName },
                        boundsLeft = bounds.left,
                        boundsTop = bounds.top,
                        boundsRight = bounds.right,
                        boundsBottom = bounds.bottom
                    )

                    if (
                        retainExecutableNodes &&
                        (supportsClick || supportsSetText || scrollForward || scrollBackward)
                    ) {
                        safe<AccessibilityNodeInfo?>(null) {
                            AccessibilityNodeInfo.obtain(node)
                        }?.let { executableCopies[key] = it }
                    }

                    val childCount = safe(0) { node.childCount }
                    for (childIndex in childCount - 1 downTo 0) {
                        if (nodes.size + stack.size >= DemoConfig.MAX_TREE_NODES) break
                        safe<AccessibilityNodeInfo?>(null) {
                            node.getChild(childIndex)
                        }?.let { child ->
                            stack.addLast(
                                WorkItem(
                                    node = child,
                                    parentKey = key,
                                    depth = work.depth + 1,
                                    owned = true
                                )
                            )
                        }
                    }
                } finally {
                    recycleIfRequired(node, work.owned)
                }
            }

            while (stack.isNotEmpty()) {
                val abandoned = stack.removeLast()
                recycleIfRequired(abandoned.node, abandoned.owned)
            }

            val snapshotCaptureDurationMs = elapsedMillis(captureStartedNanos)
            val normalizationStartedNanos = SystemClock.elapsedRealtimeNanos()
            val activeWindowId = safe(-1) { root.windowId }.takeIf { it >= 0 }
            val preliminary = CompactUiStateBuilder.build(
                screenVersion = 0,
                packageName = packageName,
                nodes = nodes,
                activeWindowId = activeWindowId
            )
            val version = ScreenVersionGenerator.versionFor(preliminary.fingerprint)
            val compact = CompactUiStateBuilder.build(
                screenVersion = version,
                packageName = packageName,
                nodes = nodes,
                activeWindowId = activeWindowId
            )
            val entries = compact.targets.associate { descriptor ->
                val nodeCopy = executableCopies[descriptor.nodeKey]?.let {
                    safe<AccessibilityNodeInfo?>(null) { AccessibilityNodeInfo.obtain(it) }
                }
                descriptor.targetId to TargetEntry(
                    metadata = TargetMetadata(
                        targetId = descriptor.targetId,
                        role = descriptor.role,
                        label = descriptor.label,
                        advertisedActions = descriptor.advertisedActions,
                        visibleAtCapture = descriptor.visible,
                        enabledAtCapture = descriptor.enabled
                    ),
                    screenVersion = version,
                    fingerprint = compact.fingerprint,
                    node = nodeCopy
                )
            }
            val normalizationDurationMs = elapsedMillis(normalizationStartedNanos)
            val observation = AgentObservation(
                currentApp = CurrentAppNormalizer.normalize(
                    packageName = packageName,
                    displayName = appName,
                    screenName = screenName,
                    launcherPackageName = launcherPackageName
                ),
                uiState = compact.uiState,
                fingerprint = compact.fingerprint,
                capturedAtEpochMillis = System.currentTimeMillis(),
                activeWindowId = activeWindowId,
                resolvedHomePackageName = launcherPackageName,
                captureDurationMs = snapshotCaptureDurationMs,
                normalizationDurationMs = normalizationDurationMs
            )
            return ActiveAgentSnapshot(
                observation = observation,
                registry = TargetRegistrySnapshot(
                    screenVersion = version,
                    fingerprint = compact.fingerprint,
                    entries = entries,
                    packageName = packageName,
                    activeWindowId = activeWindowId
                )
            )
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                executableCopies.values
                    .distinctBy(System::identityHashCode)
                    .forEach(AccessibilityNodeInfo::recycle)
            }
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T {
        return try {
            block()
        } catch (_: RuntimeException) {
            default
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long {
        val elapsedNanos = (SystemClock.elapsedRealtimeNanos() - startedNanos).coerceAtLeast(0L)
        return (elapsedNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND
    }

    @Suppress("DEPRECATION")
    private fun recycleIfRequired(node: AccessibilityNodeInfo, owned: Boolean) {
        if (owned && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            node.recycle()
        }
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
