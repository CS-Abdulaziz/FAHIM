package com.example.testandroidenv

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    override fun toString(): String = "[$left,$top][$right,$bottom]"
}

data class AccessibilityNodeSnapshot(
    val nodeId: String,
    val parentId: String?,
    val depth: Int,
    val index: Int,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val password: Boolean,
    val bounds: NodeBounds,
    val supportedActions: List<String>
)

data class SerializedAccessibilityTree(
    val nodes: List<AccessibilityNodeSnapshot>,
    val text: String,
    val truncated: Boolean
)

object AccessibilityTreeSerializer {
    private data class TraversalState(
        var visitedNodeCount: Int = 0,
        var truncated: Boolean = false,
        val nodes: MutableList<AccessibilityNodeSnapshot> = mutableListOf()
    )

    fun capture(root: AccessibilityNodeInfo): SerializedAccessibilityTree {
        val state = TraversalState()
        visit(
            node = root,
            depth = 0,
            index = 0,
            nearestIncludedParentId = null,
            state = state
        )
        return serializeNodes(state.nodes, state.truncated)
    }

    internal fun sanitizeText(value: CharSequence?, password: Boolean): String? {
        if (password) return "[REDACTED]"
        return value?.toString()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(DemoConfig.MAX_NODE_TEXT_CHARS)
    }

    private fun sanitizeNodeText(
        value: CharSequence?,
        password: Boolean,
        state: TraversalState
    ): String? {
        if (password) return "[REDACTED]"
        val normalized = value?.toString()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (normalized.length > DemoConfig.MAX_NODE_TEXT_CHARS) {
            state.truncated = true
        }
        return normalized.take(DemoConfig.MAX_NODE_TEXT_CHARS)
    }

    internal fun serializeNodes(
        nodes: List<AccessibilityNodeSnapshot>,
        initiallyTruncated: Boolean = false
    ): SerializedAccessibilityTree {
        val body = StringBuilder()
        val includedNodes = mutableListOf<AccessibilityNodeSnapshot>()
        var truncated = initiallyTruncated
        val headerReserve = 512

        for (node in nodes) {
            val line = buildNodeLine(node)
            if (body.length + line.length + headerReserve > DemoConfig.MAX_TREE_CHARS) {
                truncated = true
                break
            }
            body.append(line)
            includedNodes += node
        }

        val serialized = buildString {
            append("tree_truncated=").append(truncated).append('\n')
            append(body)
        }
        return SerializedAccessibilityTree(
            nodes = includedNodes,
            text = serialized,
            truncated = truncated
        )
    }

    fun withScreenMetadata(
        packageName: String,
        windowTitle: String?,
        capturedAtEpochMillis: Long,
        tree: SerializedAccessibilityTree
    ): String = buildString {
        append("target_package=").append(quoted(packageName)).append('\n')
        append("window_title=").append(nullableQuoted(windowTitle)).append('\n')
        append("captured_at_epoch_ms=").append(capturedAtEpochMillis).append('\n')
        append("node_count=").append(tree.nodes.size).append('\n')
        append(tree.text)
    }

    @Suppress("DEPRECATION")
    private fun visit(
        node: AccessibilityNodeInfo,
        depth: Int,
        index: Int,
        nearestIncludedParentId: String?,
        state: TraversalState
    ) {
        if (depth > DemoConfig.MAX_TREE_DEPTH) {
            state.truncated = true
            return
        }
        if (state.visitedNodeCount >= DemoConfig.MAX_TREE_NODES) {
            state.truncated = true
            return
        }

        val traversalId = "node_${state.visitedNodeCount}"
        state.visitedNodeCount++
        val childCount = safe(0) { node.childCount }
        val visible = safe(false) { node.isVisibleToUser }
        val password = safe(false) { node.isPassword }
        val text = sanitizeNodeText(safe<CharSequence?>(null) { node.text }, password, state)
        val description = sanitizeNodeText(
            safe<CharSequence?>(null) { node.contentDescription },
            password,
            state
        )
        val viewId = sanitizeNodeText(
            safe<String?>(null) { node.viewIdResourceName },
            false,
            state
        )
        val className = sanitizeNodeText(
            safe<CharSequence?>(null) { node.className },
            false,
            state
        )
        val actions = readActions(node)
        val clickable = safe(false) { node.isClickable }
        val longClickable = safe(false) { node.isLongClickable }
        val scrollable = safe(false) { node.isScrollable }
        val editable = safe(false) { node.isEditable }
        val actionable = clickable || longClickable || scrollable || editable || actions.isNotEmpty()
        val include = depth == 0 || visible &&
            (text != null || description != null || viewId != null || actionable || childCount > 0)

        val includedId = if (include) traversalId else nearestIncludedParentId
        if (include) {
            state.nodes += AccessibilityNodeSnapshot(
                nodeId = traversalId,
                parentId = nearestIncludedParentId,
                depth = depth,
                index = index,
                className = className,
                text = text,
                contentDescription = description,
                viewId = viewId,
                clickable = clickable,
                longClickable = longClickable,
                enabled = safe(false) { node.isEnabled },
                focusable = safe(false) { node.isFocusable },
                focused = safe(false) { node.isFocused },
                scrollable = scrollable,
                editable = editable,
                checkable = safe(false) { node.isCheckable },
                checked = safe(false) { node.isChecked },
                selected = safe(false) { node.isSelected },
                password = password,
                bounds = readBounds(node),
                supportedActions = actions
            )
        }

        for (childIndex in 0 until childCount) {
            if (state.visitedNodeCount >= DemoConfig.MAX_TREE_NODES) {
                state.truncated = true
                break
            }
            val child = safe<AccessibilityNodeInfo?>(null) { node.getChild(childIndex) } ?: continue
            try {
                visit(
                    node = child,
                    depth = depth + 1,
                    index = childIndex,
                    nearestIncludedParentId = includedId,
                    state = state
                )
            } finally {
                recycleChildIfRequired(child)
            }
        }
    }

    private fun readBounds(node: AccessibilityNodeInfo): NodeBounds {
        val rect = Rect()
        safe(Unit) { node.getBoundsInScreen(rect) }
        return NodeBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun readActions(node: AccessibilityNodeInfo): List<String> {
        return safe(emptyList()) {
            node.actionList.map { action ->
                actionName(action.id, action.label?.toString())
            }.distinct()
        }
    }

    private fun actionName(id: Int, customLabel: String?): String {
        return when (id) {
            AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
            AccessibilityNodeInfo.ACTION_SELECT -> "select"
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear_selection"
            AccessibilityNodeInfo.ACTION_CLICK -> "click"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "accessibility_focus"
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "clear_accessibility_focus"
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY -> "next_at_movement_granularity"
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY ->
                "previous_at_movement_granularity"
            AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT -> "next_html_element"
            AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT -> "previous_html_element"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
            AccessibilityNodeInfo.ACTION_COPY -> "copy"
            AccessibilityNodeInfo.ACTION_PASTE -> "paste"
            AccessibilityNodeInfo.ACTION_CUT -> "cut"
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> "set_selection"
            AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
            AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
            AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
            else -> customLabel?.takeIf { it.isNotBlank() } ?: "action_$id"
        }
    }

    private fun buildNodeLine(node: AccessibilityNodeSnapshot): String = buildString {
        append("node_id=").append(quoted(node.nodeId))
        append(" parent_id=").append(node.parentId?.let(::quoted) ?: "null")
        append(" depth=").append(node.depth)
        append(" index=").append(node.index)
        append(" class_name=").append(nullableQuoted(node.className))
        append(" text=").append(nullableQuoted(node.text))
        append(" content_description=").append(nullableQuoted(node.contentDescription))
        append(" view_id=").append(nullableQuoted(node.viewId))
        append(" clickable=").append(node.clickable)
        append(" long_clickable=").append(node.longClickable)
        append(" enabled=").append(node.enabled)
        append(" focusable=").append(node.focusable)
        append(" focused=").append(node.focused)
        append(" scrollable=").append(node.scrollable)
        append(" editable=").append(node.editable)
        append(" checkable=").append(node.checkable)
        append(" checked=").append(node.checked)
        append(" selected=").append(node.selected)
        append(" password=").append(node.password)
        append(" bounds=").append(quoted(node.bounds.toString()))
        append(" supported_actions=[")
        append(node.supportedActions.joinToString(",") { quoted(it) })
        append("]\n")
    }

    private fun nullableQuoted(value: String?): String = value?.let(::quoted) ?: "null"

    private fun quoted(value: String): String {
        val escaped = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
        }
        return "\"$escaped\""
    }

    private inline fun <T> safe(default: T, block: () -> T): T {
        return try {
            block()
        } catch (_: RuntimeException) {
            default
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleChildIfRequired(child: AccessibilityNodeInfo) {
        // recycle() became a no-op in API 33; it remains required for this project's API 26–32.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            child.recycle()
        }
    }
}
