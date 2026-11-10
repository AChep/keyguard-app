package com.artemchep.keyguard.feature.navigation

import androidx.activity.OnBackPressedDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationHostAndroidTest {
    @Test
    fun `the callback is enabled in the same call that changes the host`() {
        var fallbackCalls = 0
        val dispatcher = OnBackPressedDispatcher { fallbackCalls++ }
        val host = NavigationHost()
        val remove = dispatcher.addNavigationHostCallback(host)
        assertFalse(dispatcher.hasEnabledCallbacks())

        var canPop = true
        var pops = 0
        host.registerBackTarget(canPop = { canPop }) {
            pops++
            // What a router does when it pops its last entry.
            canPop = false
            host.invalidateBack()
        }
        assertTrue(dispatcher.hasEnabledCallbacks())

        dispatcher.onBackPressed()
        assertEquals(1, pops)
        assertEquals(0, fallbackCalls)
        assertFalse(dispatcher.hasEnabledCallbacks())

        dispatcher.onBackPressed()
        assertEquals(1, pops)
        assertEquals(1, fallbackCalls)

        canPop = true
        host.invalidateBack()
        assertTrue(dispatcher.hasEnabledCallbacks())
        remove()
        assertFalse(dispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `a press that nothing can handle reaches the fallback`() {
        var fallbackCalls = 0
        val dispatcher = OnBackPressedDispatcher { fallbackCalls++ }
        val host = NavigationHost()
        var canPop = true
        var pops = 0
        host.registerBackTarget(canPop = { canPop }) { pops++ }
        dispatcher.addNavigationHostCallback(host)
        assertTrue(dispatcher.hasEnabledCallbacks())

        // Nothing invalidated the host since the target stopped being poppable.
        canPop = false
        dispatcher.onBackPressed()

        assertEquals(0, pops)
        assertEquals(1, fallbackCalls)
        assertFalse(dispatcher.hasEnabledCallbacks())
    }
}
