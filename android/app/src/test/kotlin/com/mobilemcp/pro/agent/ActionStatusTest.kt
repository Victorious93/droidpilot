package com.mobilemcp.pro.agent

import com.mobilemcp.pro.core.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionStatusTest {

    @Test
    fun `permission and authorization failures require permission`() {
        assertEquals(ActionStatus.REQUIRES_PERMISSION, ActionStatus.from(ErrorCode.PERMISSION_DENIED))
        assertEquals(ActionStatus.REQUIRES_PERMISSION, ActionStatus.from(ErrorCode.UNAUTHORIZED))
    }

    @Test
    fun `unavailable service and unsupported operations are blocked`() {
        assertEquals(ActionStatus.BLOCKED, ActionStatus.from(ErrorCode.SERVICE_UNAVAILABLE))
        assertEquals(ActionStatus.BLOCKED, ActionStatus.from(ErrorCode.UNSUPPORTED))
    }

    @Test
    fun `timeouts and missing elements are retryable`() {
        assertEquals(ActionStatus.RETRYABLE, ActionStatus.from(ErrorCode.TIMEOUT))
        assertEquals(ActionStatus.RETRYABLE, ActionStatus.from(ErrorCode.NOT_FOUND))
    }

    @Test
    fun `every remaining code falls back to failed rather than a false retryable guess`() {
        val handled = setOf(
            ErrorCode.PERMISSION_DENIED, ErrorCode.UNAUTHORIZED,
            ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.UNSUPPORTED,
            ErrorCode.TIMEOUT, ErrorCode.NOT_FOUND,
        )
        ErrorCode.entries.filterNot { it in handled }.forEach { code ->
            assertEquals("$code should map to FAILED", ActionStatus.FAILED, ActionStatus.from(code))
        }
    }
}
