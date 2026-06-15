package com.artemchep.keyguard.common.service.quicksearch

import kotlin.test.Test
import kotlin.test.assertEquals

class QuickSearchWindowManagerTest {
    @Test
    fun `requestOpen shows the window and bumps revision`() {
        val manager = QuickSearchWindowManager()

        manager.requestOpen()
        manager.requestOpen()

        assertEquals(
            QuickSearchWindowState(
                visible = true,
                requestRevision = 2,
            ),
            manager.stateFlow.value,
        )
    }

    @Test
    fun `dismiss hides the window without resetting revision`() {
        val manager = QuickSearchWindowManager()

        manager.requestOpen()
        manager.dismiss()

        assertEquals(
            QuickSearchWindowState(
                visible = false,
                requestRevision = 1,
            ),
            manager.stateFlow.value,
        )
    }
}
