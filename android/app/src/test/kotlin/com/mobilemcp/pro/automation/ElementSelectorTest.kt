package com.mobilemcp.pro.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementSelectorTest {

    private data class Node(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val viewId: String? = null,
        override val className: String? = null,
    ) : NodeAttributes

    @Test
    fun `an empty selector matches anything and reports itself as empty`() {
        assertTrue(ElementSelector().isEmpty)
        assertTrue(ElementSelector().matches(Node(text = "anything")))
    }

    @Test
    fun `a selector with any criterion is not empty`() {
        assertFalse(ElementSelector(text = "Send").isEmpty)
        assertFalse(ElementSelector(viewId = "btn").isEmpty)
        assertFalse(ElementSelector(className = "android.widget.Button").isEmpty)
        assertFalse(ElementSelector(contentDescription = "Close").isEmpty)
    }

    @Test
    fun `text matches as a case-insensitive substring by default`() {
        val selector = ElementSelector(text = "send")
        assertTrue(selector.matches(Node(text = "Send message")))
        assertTrue(selector.matches(Node(text = "RESEND")))
        assertFalse(selector.matches(Node(text = "Cancel")))
    }

    /**
     * `text` falls back to the content description because that is where a label lives for
     * icon-only buttons — the case where a caller most needs it and is least likely to know
     * which field holds the string.
     */
    @Test
    fun `text also matches the content description`() {
        assertTrue(ElementSelector(text = "Close").matches(Node(contentDescription = "Close dialog")))
    }

    /**
     * The reason `exact` exists: substring matching makes "OK" match "NOT OK", which is the
     * kind of mis-click that is very hard to diagnose after the fact.
     */
    @Test
    fun `exact matching requires full equality`() {
        val exact = ElementSelector(text = "OK", exact = true)
        assertTrue(exact.matches(Node(text = "OK")))
        assertTrue("still case-insensitive", exact.matches(Node(text = "ok")))
        assertFalse(exact.matches(Node(text = "NOT OK")))
        assertFalse(exact.matches(Node(text = "OKAY")))
    }

    @Test
    fun `all supplied criteria must match`() {
        val selector = ElementSelector(text = "Send", className = "Button")

        assertTrue(selector.matches(Node(text = "Send", className = "android.widget.Button")))
        assertFalse(selector.matches(Node(text = "Send", className = "android.widget.TextView")))
        assertFalse(selector.matches(Node(text = "Cancel", className = "android.widget.Button")))
    }

    @Test
    fun `a criterion never matches a null attribute`() {
        assertFalse(ElementSelector(text = "Send").matches(Node()))
        assertFalse(ElementSelector(viewId = "btn").matches(Node(text = "Send")))
        assertFalse(ElementSelector(className = "Button").matches(Node(text = "Send")))
        assertFalse(ElementSelector(contentDescription = "Close").matches(Node(text = "Send")))
    }

    @Test
    fun `view id matches the qualified resource name as a substring`() {
        val selector = ElementSelector(viewId = "search_bar")
        assertTrue(selector.matches(Node(viewId = "com.example.app:id/search_bar")))
        assertFalse(selector.matches(Node(viewId = "com.example.app:id/toolbar")))
    }

    @Test
    fun `content description is matched independently of text`() {
        val selector = ElementSelector(contentDescription = "Close")
        assertTrue(selector.matches(Node(contentDescription = "Close dialog")))
        assertFalse(selector.matches(Node(text = "Close")))
    }

    @Test
    fun `a UiNode can be matched directly`() {
        // UiNode implements NodeAttributes, so a snapshot returned by find_element and a
        // live node during a click walk are matched by exactly the same predicate.
        val node = UiNode(text = "Submit", className = "android.widget.Button", isClickable = true)
        assertTrue(ElementSelector(text = "Submit").matches(node))
    }
}
