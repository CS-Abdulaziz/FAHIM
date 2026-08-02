package com.example.testandroidenv.agent.perception

import com.example.testandroidenv.agent.contract.CompactUiElement
import com.example.testandroidenv.agent.contract.CompactUiState
import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.UiRole
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale

data class TargetDescriptor(
    val targetId: String,
    val nodeKey: Int,
    val role: UiRole,
    val label: String,
    val advertisedActions: Set<PlannerAction>,
    val visible: Boolean,
    val enabled: Boolean
)

data class CompactBuildOutput(
    val uiState: CompactUiState,
    val fingerprint: String,
    val targets: List<TargetDescriptor>
)

object CompactUiStateBuilder {
    const val MAX_COMPACT_ELEMENTS = 120
    const val MAX_LABEL_CHARS = 200

    private data class Candidate(
        val nodeKey: Int,
        val role: UiRole,
        val label: String,
        val actions: Set<PlannerAction>,
        val visible: Boolean,
        val enabled: Boolean,
        val traversalIndex: Int,
        val priority: Int,
        val boundsLeft: Int,
        val boundsTop: Int,
        val structuralKey: String,
        val materialIdentity: String,
        val volatilePassiveContent: Boolean,
        val contentDescription: String?
    )

    fun build(
        screenVersion: Long,
        packageName: String?,
        nodes: List<UiNodeSnapshot>,
        activeWindowId: Int? = null
    ): CompactBuildOutput {
        val byKey = nodes.associateBy(UiNodeSnapshot::key)
        val ordered = nodes.sortedBy(UiNodeSnapshot::traversalIndex)
        val candidates = ordered
            .asSequence()
            .filter(UiNodeSnapshot::visible)
            .mapNotNull { source -> candidate(source, ordered, byKey) }
            .distinctBy { candidate ->
                listOf(
                    candidate.nodeKey.toString(),
                    candidate.role.wireValue,
                    normalizeForComparison(candidate.label),
                    candidate.actions.joinToString(",") { it.wireValue }
                ).joinToString("|")
            }
            .sortedWith(
                compareBy<Candidate> { it.priority }
                    .thenBy { it.boundsTop }
                    .thenBy { it.boundsLeft }
                    .thenBy { it.traversalIndex }
            )
            .take(MAX_COMPACT_ELEMENTS)
            .toList()

        val elements = candidates.mapIndexed { index, candidate ->
            CompactUiElement(
                targetId = targetId(screenVersion, index, candidate.structuralKey),
                role = candidate.role,
                label = candidate.label,
                actions = candidate.actions.sortedBy(PlannerAction::wireValue),
                contentDescription = candidate.contentDescription,
                enabled = candidate.enabled
            )
        }
        val targets = candidates.zip(elements).map { (candidate, element) ->
            TargetDescriptor(
                targetId = element.targetId,
                nodeKey = candidate.nodeKey,
                role = candidate.role,
                label = candidate.label,
                advertisedActions = candidate.actions,
                visible = candidate.visible,
                enabled = candidate.enabled
            )
        }
        val state = CompactUiState(
            screenVersion = screenVersion,
            elements = elements,
            sourcePackageName = packageName,
            sourceWindowId = activeWindowId
        )
        return CompactBuildOutput(
            uiState = state,
            fingerprint = fingerprint(packageName, activeWindowId, candidates),
            targets = targets
        )
    }

    fun normalizeDisplayLabel(
        text: String?,
        contentDescription: String?,
        hintText: String?,
        password: Boolean
    ): String {
        if (password) return "[REDACTED]"
        val parts = listOf(text, contentDescription, hintText)
            .mapNotNull(::collapseWhitespace)
            .distinctBy(::normalizeForComparison)
        return parts.joinToString(" · ").take(MAX_LABEL_CHARS)
    }

    fun normalizeForComparison(value: String): String {
        return collapseWhitespace(value)
            .orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace("\u0640", "")
            .replace(Regex("[\u0622\u0623\u0625]"), "\u0627")
            .replace("\u0649", "\u064A")
    }

    private fun candidate(
        source: UiNodeSnapshot,
        orderedNodes: List<UiNodeSnapshot>,
        byKey: Map<Int, UiNodeSnapshot>
    ): Candidate? {
        var label = normalizeDisplayLabel(
            source.text,
            source.contentDescription,
            source.hintText,
            source.password
        )
        val ownActions = executableActions(source)
        val executable = if (ownActions.isNotEmpty()) {
            source
        } else if (label.isNotEmpty()) {
            nearestActionableAncestor(source, byKey) ?: source
        } else {
            source
        }
        val actions = executableActions(executable)
        if (label.isEmpty() && actions.isNotEmpty()) {
            label = firstMeaningfulDescendantLabel(executable, orderedNodes, byKey)
        }
        if (label.isEmpty() && actions.isEmpty()) return null

        val role = inferRole(source, executable, actions)
        val comparisonLabel = normalizeForComparison(label)
        val volatilePassiveContent =
            actions.isEmpty() && isVolatilePassiveLabel(comparisonLabel, role)
        val structuralKey = buildString {
            append(packageSafe(source.className)).append('|')
            append(packageSafe(executable.className)).append('|')
            append(executable.viewId.orEmpty()).append('|')
            append(boundsBucket(executable.boundsLeft)).append(',')
            append(boundsBucket(executable.boundsTop)).append(',')
            append(boundsBucket(executable.boundsRight)).append(',')
            append(boundsBucket(executable.boundsBottom)).append('|')
            append(role.wireValue).append('|')
            append(comparisonLabel).append('|')
            append(actions.sortedBy(PlannerAction::wireValue)
                .joinToString(",") { it.wireValue })
        }
        val materialIdentity = buildString {
            append(packageSafe(source.className)).append('|')
            append(packageSafe(executable.className)).append('|')
            append(executable.viewId.orEmpty()).append('|')
            append(boundsBucket(executable.boundsLeft)).append(',')
            append(boundsBucket(executable.boundsTop)).append(',')
            append(boundsBucket(executable.boundsRight)).append(',')
            append(boundsBucket(executable.boundsBottom)).append('|')
            append(role.wireValue).append('|')
            if (!volatilePassiveContent) append(comparisonLabel)
            append('|')
            append(actions.sortedBy(PlannerAction::wireValue)
                .joinToString(",") { it.wireValue })
            append('|').append(executable.enabled)
        }
        return Candidate(
            nodeKey = executable.key,
            role = role,
            label = label,
            actions = actions,
            visible = executable.visible,
            enabled = executable.enabled,
            traversalIndex = source.traversalIndex,
            priority = priority(actions, role, label),
            boundsLeft = executable.boundsLeft,
            boundsTop = executable.boundsTop,
            structuralKey = structuralKey,
            materialIdentity = materialIdentity,
            volatilePassiveContent = volatilePassiveContent,
            contentDescription = collapseWhitespace(source.contentDescription)
        )
    }

    private fun executableActions(node: UiNodeSnapshot): Set<PlannerAction> = buildSet {
        if (node.clickable && node.supportsClick) add(PlannerAction.TAP)
        if (node.editable && node.supportsSetText) add(PlannerAction.TYPE)
        if (node.scrollForward || node.scrollBackward) add(PlannerAction.SCROLL)
    }

    private fun nearestActionableAncestor(
        node: UiNodeSnapshot,
        byKey: Map<Int, UiNodeSnapshot>
    ): UiNodeSnapshot? {
        var parentKey = node.parentKey
        while (parentKey != null) {
            val parent = byKey[parentKey] ?: return null
            if (parent.visible && executableActions(parent).isNotEmpty()) return parent
            parentKey = parent.parentKey
        }
        return null
    }

    private fun firstMeaningfulDescendantLabel(
        ancestor: UiNodeSnapshot,
        orderedNodes: List<UiNodeSnapshot>,
        byKey: Map<Int, UiNodeSnapshot>
    ): String {
        return orderedNodes.asSequence()
            .filter { it.visible && isDescendant(it, ancestor.key, byKey) }
            .map {
                normalizeDisplayLabel(
                    it.text,
                    it.contentDescription,
                    it.hintText,
                    it.password
                )
            }
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
    }

    private fun isDescendant(
        node: UiNodeSnapshot,
        ancestorKey: Int,
        byKey: Map<Int, UiNodeSnapshot>
    ): Boolean {
        var parentKey = node.parentKey
        while (parentKey != null) {
            if (parentKey == ancestorKey) return true
            parentKey = byKey[parentKey]?.parentKey
        }
        return false
    }

    private fun inferRole(
        source: UiNodeSnapshot,
        executable: UiNodeSnapshot,
        actions: Set<PlannerAction>
    ): UiRole {
        val className = (executable.className ?: source.className).orEmpty()
        return when {
            executable.editable -> UiRole.TEXT_FIELD
            executable.heading -> UiRole.HEADING
            executable.checkable && className.contains("switch", ignoreCase = true) -> UiRole.SWITCH
            executable.checkable -> UiRole.CHECKBOX
            className.contains("tab", ignoreCase = true) -> UiRole.TAB
            className.contains("BubbleTextView", ignoreCase = true) ||
                className.contains("AppIcon", ignoreCase = true) -> UiRole.APP_ICON
            className.contains("button", ignoreCase = true) -> UiRole.BUTTON
            className.contains("image", ignoreCase = true) -> UiRole.IMAGE
            PlannerAction.SCROLL in actions -> UiRole.SCROLLABLE
            PlannerAction.TAP in actions && executable.key != source.key -> UiRole.LIST_ITEM
            PlannerAction.TAP in actions &&
                className.contains(Regex("ViewGroup|Layout|RecyclerView")) -> UiRole.LIST_ITEM
            source.text != null || source.contentDescription != null -> UiRole.TEXT
            else -> UiRole.UNKNOWN
        }
    }

    private fun priority(
        actions: Set<PlannerAction>,
        role: UiRole,
        label: String
    ): Int = when {
        actions.isNotEmpty() && label.isNotEmpty() -> 0
        role == UiRole.TEXT_FIELD -> 0
        role == UiRole.SCROLLABLE -> 1
        label.isNotEmpty() -> 2
        actions.isNotEmpty() -> 3
        else -> 4
    }

    private fun fingerprint(
        packageName: String?,
        activeWindowId: Int?,
        candidates: List<Candidate>
    ): String {
        val canonical = buildString {
            append("package=").append(packageName.orEmpty()).append('\n')
            activeWindowId?.takeIf { it >= 0 }?.let {
                append("window=").append(it).append('\n')
            }
            candidates
                .filterNot(Candidate::volatilePassiveContent)
                .forEach { candidate ->
                    append(candidate.materialIdentity).append('\n')
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isVolatilePassiveLabel(label: String, role: UiRole): Boolean {
        if (label.isEmpty()) return true
        if (role !in PASSIVE_ROLES) return false
        return VOLATILE_NUMERIC_LABEL.matches(label) ||
            VOLATILE_STATUS_LABEL.matches(label)
    }

    private fun boundsBucket(value: Int): Int {
        return if (value >= 0) {
            value / BOUNDS_BUCKET_PX
        } else {
            -((-value) / BOUNDS_BUCKET_PX)
        }
    }

    private val PASSIVE_ROLES = setOf(
        UiRole.TEXT,
        UiRole.IMAGE,
        UiRole.UNKNOWN
    )
    private val VOLATILE_NUMERIC_LABEL =
        Regex("^[\\p{N}\\s:./,،+\\-%٪]+$")
    private val VOLATILE_STATUS_LABEL = Regex(
        "^(typing|online|last seen.*|recording audio|يكتب.*|متصل.*|آخر ظهور.*)$",
        RegexOption.IGNORE_CASE
    )
    private const val BOUNDS_BUCKET_PX = 8

    private fun collapseWhitespace(value: String?): String? {
        return value
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }
            ?.replace(INVISIBLE_UNICODE, "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun targetId(
        screenVersion: Long,
        index: Int,
        structuralKey: String
    ): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(structuralKey.toByteArray(Charsets.UTF_8))
            .take(3)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "s${screenVersion}_e${(index + 1).toString().padStart(3, '0')}_$hash"
    }

    private fun packageSafe(value: String?): String = value.orEmpty()

    private val INVISIBLE_UNICODE =
        Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
}
