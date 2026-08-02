package com.example.testandroidenv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTreeSerializerTest {
    @Test
    fun preservesArabicAndEscapesSpecialCharacters() {
        val sanitized = AccessibilityTreeSerializer.sanitizeText(
            "  مرحبا   \"بك\"  ",
            password = false
        )
        val result = AccessibilityTreeSerializer.serializeNodes(
            listOf(node(text = sanitized))
        )

        assertTrue(result.text.contains("مرحبا \\\"بك\\\""))
        assertFalse(result.truncated)
    }

    @Test
    fun redactsPasswordValues() {
        assertEquals(
            "[REDACTED]",
            AccessibilityTreeSerializer.sanitizeText("secret", password = true)
        )
        assertEquals(
            "[REDACTED]",
            AccessibilityTreeSerializer.sanitizeText(null, password = true)
        )
    }

    @Test
    fun emitsHierarchyAndTruncationMetadata() {
        val result = AccessibilityTreeSerializer.serializeNodes(
            nodes = listOf(
                node(nodeId = "node_0"),
                node(nodeId = "node_1", parentId = "node_0", depth = 1, index = 2)
            ),
            initiallyTruncated = true
        )

        assertTrue(result.text.startsWith("tree_truncated=true"))
        assertTrue(result.text.contains("node_id=\"node_1\" parent_id=\"node_0\" depth=1 index=2"))
    }

    private fun node(
        nodeId: String = "node_0",
        parentId: String? = null,
        depth: Int = 0,
        index: Int = 0,
        text: String? = null
    ) = AccessibilityNodeSnapshot(
        nodeId = nodeId,
        parentId = parentId,
        depth = depth,
        index = index,
        className = "android.widget.TextView",
        text = text,
        contentDescription = null,
        viewId = null,
        clickable = false,
        longClickable = false,
        enabled = true,
        focusable = false,
        focused = false,
        scrollable = false,
        editable = false,
        checkable = false,
        checked = false,
        selected = false,
        password = false,
        bounds = NodeBounds(0, 0, 100, 50),
        supportedActions = emptyList()
    )
}
