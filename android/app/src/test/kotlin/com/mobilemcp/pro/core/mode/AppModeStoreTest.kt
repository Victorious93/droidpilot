package com.mobilemcp.pro.core.mode

import org.junit.Assert.assertEquals
import org.junit.Test

class AppModeStoreTest {

    private fun storeBackedBy(initial: String? = null): Pair<AppModeStore, () -> String?> {
        var stored: String? = initial
        val store = AppModeStore(readRaw = { stored }, writeRaw = { stored = it })
        return store to { stored }
    }

    @Test
    fun `defaults to Pilot when nothing has been stored`() {
        val (store, _) = storeBackedBy(initial = null)
        assertEquals(AppMode.PILOT, store.get())
    }

    @Test
    fun `defaults to Pilot for an unrecognised stored value`() {
        val (store, _) = storeBackedBy(initial = "some_future_mode")
        assertEquals(AppMode.PILOT, store.get())
    }

    @Test
    fun `set persists and get reflects it`() {
        val (store, stored) = storeBackedBy()

        store.set(AppMode.DEVELOPER_AGENT)

        assertEquals("developer_agent", stored())
        assertEquals(AppMode.DEVELOPER_AGENT, store.get())
    }

    @Test
    fun `round trips back to Pilot`() {
        val (store, _) = storeBackedBy()

        store.set(AppMode.DEVELOPER_AGENT)
        store.set(AppMode.PILOT)

        assertEquals(AppMode.PILOT, store.get())
    }
}
