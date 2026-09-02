package com.mobilemcp.pro.automation

import kotlinx.serialization.Serializable

/**
 * The subset of a node's attributes that element selection is allowed to look at.
 *
 * Selection logic is expressed against this interface rather than against
 * `AccessibilityNodeInfo` for two reasons. It keeps [ElementSelector] free of Android
 * types, so matching behaviour — the part most likely to be subtly wrong — is covered by
 * plain JVM unit tests. And it lets the same predicate run over a serialised [UiNode]
 * snapshot and over a live node during a tree walk, so a `find_element` result and a
 * `click_element` target can never disagree about what "matching" means.
 */
interface NodeAttributes {
    val text: String?
    val contentDescription: String?
    val viewId: String?
    val className: String?
}

/** An immutable snapshot of one node in the accessibility tree. */
@Serializable
data class UiNode(
    override val className: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val viewId: String? = null,
    val isClickable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val isEnabled: Boolean = true,
    val isChecked: Boolean = false,
    val isFocused: Boolean = false,
    val isSelected: Boolean = false,
    val isPassword: Boolean = false,
    val bounds: Bounds? = null,
    val packageName: String? = null,
    val children: List<UiNode>? = null,
) : NodeAttributes

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = right <= left || bottom <= top
}
