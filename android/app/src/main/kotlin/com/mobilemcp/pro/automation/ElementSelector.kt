package com.mobilemcp.pro.automation

/**
 * Criteria for locating a UI element.
 *
 * An empty selector matches everything, which is what `get_ui_tree`-style enumeration
 * wants but is almost never what a `click_element` caller wants — so [isEmpty] is exposed
 * and the dispatcher rejects empty selectors for action commands. That guard exists
 * because the previous implementation would happily click the first node in the tree when
 * given no criteria, which looks like a successful click and is not one.
 */
data class ElementSelector(
    val text: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val contentDescription: String? = null,
    /**
     * When true, [text] must equal the node's text or description exactly rather than
     * being contained in it. Substring matching is the friendlier default for
     * hand-written automation, but it makes "OK" match "NOT OK", so callers that need
     * precision can ask for it.
     */
    val exact: Boolean = false,
) {

    val isEmpty: Boolean
        get() = text == null && viewId == null && className == null && contentDescription == null

    fun matches(node: NodeAttributes): Boolean {
        if (text != null) {
            // `text` is the catch-all humans reach for first, so it matches either the
            // visible label or the accessibility description.
            val hitsText = compare(node.text, text)
            val hitsDescription = compare(node.contentDescription, text)
            if (!hitsText && !hitsDescription) return false
        }
        if (viewId != null && !compare(node.viewId, viewId)) return false
        if (className != null && !compare(node.className, className)) return false
        if (contentDescription != null && !compare(node.contentDescription, contentDescription)) return false
        return true
    }

    private fun compare(actual: String?, expected: String): Boolean {
        if (actual == null) return false
        return if (exact) {
            actual.equals(expected, ignoreCase = true)
        } else {
            actual.contains(expected, ignoreCase = true)
        }
    }
}
