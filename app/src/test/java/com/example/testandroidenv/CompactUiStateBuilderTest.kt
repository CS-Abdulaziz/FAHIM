package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.UiRole
import com.example.testandroidenv.agent.perception.CompactUiStateBuilder
import com.example.testandroidenv.agent.perception.ScreenVersionGenerator
import com.example.testandroidenv.agent.perception.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactUiStateBuilderTest {
    @Test
    fun includesVisibleUsefulNodesAndExcludesInvisibleOrEmptyNodes() {
        val output = build(
            listOf(
                node(0, label = "Visible", clickable = true),
                node(1, label = "Hidden", visible = false, clickable = true),
                node(2)
            )
        )

        assertEquals(listOf("Visible"), output.uiState.elements.map { it.label })
        assertEquals(listOf(PlannerAction.TAP), output.uiState.elements.single().actions)
    }

    @Test
    fun normalizesLabelsPreservesArabicAndRedactsPasswords() {
        assertEquals(
            "أحمد محمد",
            CompactUiStateBuilder.normalizeDisplayLabel(
                text = "  أحمد   محمد ",
                contentDescription = "أحمد محمد",
                hintText = null,
                password = false
            )
        )
        assertEquals(
            "احمد محمد",
            CompactUiStateBuilder.normalizeForComparison("إحْمَـد  محمد")
        )

        val output = build(listOf(node(0, label = "synthetic-secret", password = true)))
        assertEquals("[REDACTED]", output.uiState.elements.single().label)
        assertFalse(output.uiState.elements.single().label.contains("synthetic-secret"))
    }

    @Test
    fun removesInvisibleUnicodeAndCollapsesAllWhitespace() {
        assertEquals(
            "محادثة خالد",
            CompactUiStateBuilder.normalizeDisplayLabel(
                text = "\u200Fمحادثة\n\t  خالد\u2067",
                contentDescription = null,
                hintText = null,
                password = false
            )
        )
    }

    @Test
    fun labeledChildResolvesToNearestClickableAncestorWithoutWrapperDuplicates() {
        val output = build(
            listOf(
                node(
                    key = 0,
                    className = "android.widget.LinearLayout",
                    clickable = true
                ),
                node(key = 1, parentKey = 0, label = "أحمد محمد")
            )
        )

        assertEquals(1, output.uiState.elements.size)
        assertEquals("أحمد محمد", output.uiState.elements.single().label)
        assertEquals(UiRole.LIST_ITEM, output.uiState.elements.single().role)
        assertEquals(0, output.targets.single().nodeKey)
        assertEquals(setOf(PlannerAction.TAP), output.targets.single().advertisedActions)
    }

    @Test
    fun duplicateLabelsOnDifferentTargetsRemainDistinctAndIdsAreDeterministic() {
        val nodes = listOf(
            node(0, label = "أحمد محمد", clickable = true),
            node(1, label = "أحمد محمد", clickable = true)
        )
        val first = build(nodes)
        val second = build(nodes)

        assertTrue(first.uiState.elements.all { it.targetId.startsWith("s10_e") })
        assertEquals(2, first.uiState.elements.map { it.targetId }.distinct().size)
        assertEquals(first.uiState.elements, second.uiState.elements)
        assertEquals(2, first.targets.map { it.nodeKey }.distinct().size)
    }

    @Test
    fun advertisesOnlyExecutableContractActions() {
        val output = build(
            listOf(
                node(0, label = "Editable but unsupported", editable = true),
                node(
                    1,
                    label = "Search",
                    editable = true,
                    supportsSetText = true
                ),
                node(2, label = "", scrollForward = true)
            )
        )

        assertTrue(
            output.uiState.elements
                .first { it.label == "Search" }
                .actions.contains(PlannerAction.TYPE)
        )
        assertTrue(output.uiState.elements.any { PlannerAction.SCROLL in it.actions })
        assertTrue(
            output.uiState.elements
                .first { it.label == "Editable but unsupported" }
                .actions.isEmpty()
        )
    }

    @Test
    fun priorityLimitKeepsLateActionableItemAheadOfPassiveText() {
        val passive = (0 until 150).map { index ->
            node(index, label = "Passive $index")
        }
        val output = build(passive + node(100, label = "Important", clickable = true))

        assertEquals(CompactUiStateBuilder.MAX_COMPACT_ELEMENTS, output.uiState.elements.size)
        assertEquals("Important", output.uiState.elements.first().label)
        assertTrue(output.uiState.elements.any { it.label == "Important" })
    }

    @Test
    fun fingerprintIgnoresVersionButChangesWithMeaningfulContent() {
        val nodes = listOf(node(0, label = "أحمد محمد", clickable = true))
        val first = CompactUiStateBuilder.build(1, "com.whatsapp", nodes)
        val same = CompactUiStateBuilder.build(2, "com.whatsapp", nodes)
        val changed = CompactUiStateBuilder.build(
            3,
            "com.whatsapp",
            listOf(node(0, label = "شخص آخر", clickable = true))
        )

        assertEquals(first.fingerprint, same.fingerprint)
        assertNotEquals(first.fingerprint, changed.fingerprint)
        assertTrue(first.uiState.elements.single().targetId.startsWith("s1_"))
        assertTrue(same.uiState.elements.single().targetId.startsWith("s2_"))
        assertTrue(ScreenVersionGenerator.next() < ScreenVersionGenerator.next())
    }

    @Test
    fun materialFingerprintControlsMonotonicScreenVersion() {
        val first = ScreenVersionGenerator.versionFor("material-a")
        val unchanged = ScreenVersionGenerator.versionFor("material-a")
        val changed = ScreenVersionGenerator.versionFor("material-b")

        assertEquals(first, unchanged)
        assertTrue(changed > unchanged)
    }

    @Test
    fun volatilePassiveClockDoesNotInvalidateMaterialFingerprint() {
        val first = CompactUiStateBuilder.build(
            screenVersion = 1,
            packageName = "com.whatsapp",
            nodes = listOf(
                node(0, label = "Chats", clickable = true),
                node(1, label = "10:41")
            ),
            activeWindowId = 42
        )
        val repeatedEvent = CompactUiStateBuilder.build(
            screenVersion = 1,
            packageName = "com.whatsapp",
            nodes = listOf(
                node(0, label = "Chats", clickable = true),
                node(1, label = "10:42")
            ),
            activeWindowId = 42
        )
        val realChange = CompactUiStateBuilder.build(
            screenVersion = 2,
            packageName = "com.whatsapp",
            nodes = listOf(
                node(0, label = "Conversation", clickable = true),
                node(1, label = "10:42")
            ),
            activeWindowId = 42
        )

        assertEquals(first.fingerprint, repeatedEvent.fingerprint)
        assertNotEquals(first.fingerprint, realChange.fingerprint)
    }

    private fun build(nodes: List<UiNodeSnapshot>) =
        CompactUiStateBuilder.build(10, "com.whatsapp", nodes)

    private fun node(
        key: Int,
        parentKey: Int? = null,
        label: String? = null,
        visible: Boolean = true,
        password: Boolean = false,
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        supportsSetText: Boolean = false,
        scrollForward: Boolean = false
    ) = UiNodeSnapshot(
        key = key,
        parentKey = parentKey,
        traversalIndex = key,
        visible = visible,
        enabled = true,
        password = password,
        className = className,
        text = label,
        contentDescription = label,
        hintText = null,
        clickable = clickable,
        supportsClick = clickable,
        editable = editable,
        supportsSetText = supportsSetText,
        scrollForward = scrollForward,
        scrollBackward = false,
        checkable = false,
        checked = false
    )
}
