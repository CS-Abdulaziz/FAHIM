package com.example.testandroidenv.agent.perception

/**
 * Framework-free accessibility node data. Production traversal creates these records; pure unit
 * tests can construct them without mocking final Android framework classes.
 */
data class UiNodeSnapshot(
    val key: Int,
    val parentKey: Int?,
    val traversalIndex: Int,
    val visible: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val clickable: Boolean,
    val supportsClick: Boolean,
    val editable: Boolean,
    val supportsSetText: Boolean,
    val scrollForward: Boolean,
    val scrollBackward: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val focusable: Boolean = false,
    val selected: Boolean = false,
    val heading: Boolean = false,
    val viewId: String? = null,
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0
)
