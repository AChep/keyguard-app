package com.artemchep.jna.macos

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MacPopupOverlayManagerTest {
    @Test
    fun `first session sets accessory before returning`() = runTest {
        val operations = FakeMacPopupOverlayOperations()
        val manager = MacPopupOverlayManager(operations)

        val session = manager.beginPopupSession()

        assertNotNull(session)
        assertEquals(
            listOf("setActivationPolicy:1"),
            operations.calls,
        )
        session.close()
        assertEquals(
            listOf(
                "setActivationPolicy:1",
                "setActivationPolicy:0",
            ),
            operations.calls,
        )
    }

    @Test
    fun `overlapping sessions restore regular after last close`() = runTest {
        val operations = FakeMacPopupOverlayOperations()
        val manager = MacPopupOverlayManager(operations)

        val firstSession = manager.beginPopupSession()
        val secondSession = manager.beginPopupSession()

        assertNotNull(firstSession)
        assertNotNull(secondSession)
        assertEquals(
            listOf("setActivationPolicy:1"),
            operations.calls,
        )

        firstSession.close()
        assertEquals(
            listOf("setActivationPolicy:1"),
            operations.calls,
        )

        secondSession.close()
        assertEquals(
            listOf(
                "setActivationPolicy:1",
                "setActivationPolicy:0",
            ),
            operations.calls,
        )
    }

    @Test
    fun `failed activation returns no session and allows retry`() = runTest {
        val operations = FakeMacPopupOverlayOperations().apply {
            activationPolicyResults += false
        }
        val manager = MacPopupOverlayManager(operations)

        val failedSession = manager.beginPopupSession()
        val retrySession = manager.beginPopupSession()

        assertNull(failedSession)
        assertNotNull(retrySession)
        assertEquals(
            listOf(
                "setActivationPolicy:1",
                "setActivationPolicy:0",
                "setActivationPolicy:1",
            ),
            operations.calls,
        )
    }

    @Test
    fun `apply overlay applies window operations in order`() {
        val operations = FakeMacPopupOverlayOperations()
        val manager = MacPopupOverlayManager(operations)

        val result = manager.applyOverlay(
            windowHandle = 42L,
            makeKeyWindow = true,
        )

        assertEquals(true, result)
        assertEquals(
            listOf(
                "setCollectionBehavior:42",
                "prepareWindow:42",
                "orderFrontRegardless:42",
                "activateApplication",
                "makeKeyWindowIfPossible:42",
            ),
            operations.calls,
        )
    }

    @Test
    fun `apply overlay and wait applies window operations in order`() = runTest {
        val operations = FakeMacPopupOverlayOperations()
        val manager = MacPopupOverlayManager(operations)

        val result = manager.applyOverlayAndWait(
            windowHandle = 42L,
            makeKeyWindow = true,
        )

        assertEquals(true, result)
        assertEquals(
            listOf(
                "setCollectionBehavior:42",
                "prepareWindow:42",
                "orderFrontRegardless:42",
                "activateApplication",
                "makeKeyWindowIfPossible:42",
            ),
            operations.calls,
        )
    }

    @Test
    fun `apply overlay and wait reports make key failure`() = runTest {
        val operations = FakeMacPopupOverlayOperations().apply {
            makeKeyWindowResults += false
        }
        val manager = MacPopupOverlayManager(operations)

        val result = manager.applyOverlayAndWait(
            windowHandle = 42L,
            makeKeyWindow = true,
        )

        assertEquals(false, result)
        assertEquals(
            listOf(
                "setCollectionBehavior:42",
                "prepareWindow:42",
                "orderFrontRegardless:42",
                "activateApplication",
                "makeKeyWindowIfPossible:42",
            ),
            operations.calls,
        )
    }

    @Test
    fun `apply overlay skips make key when disabled`() {
        val operations = FakeMacPopupOverlayOperations()
        val manager = MacPopupOverlayManager(operations)

        val result = manager.applyOverlay(
            windowHandle = 42L,
            makeKeyWindow = false,
        )

        assertEquals(true, result)
        assertEquals(
            listOf(
                "setCollectionBehavior:42",
                "prepareWindow:42",
                "orderFrontRegardless:42",
                "activateApplication",
            ),
            operations.calls,
        )
    }

    private class FakeMacPopupOverlayOperations : MacPopupOverlayOperations {
        override val isMac: Boolean = true
        val calls = mutableListOf<String>()
        val activationPolicyResults = ArrayDeque<Boolean>()
        val makeKeyWindowResults = ArrayDeque<Boolean>()

        override suspend fun <T> runOnMainThread(
            block: () -> T,
        ): T = block()

        override fun runOnMainThreadAsync(
            block: () -> Unit,
        ): Boolean {
            block()
            return true
        }

        override fun setApplicationActivationPolicyOnMainThread(
            activationPolicy: Long,
        ): Boolean {
            calls += "setActivationPolicy:$activationPolicy"
            return activationPolicyResults.removeFirstOrNull() ?: true
        }

        override fun setPopupOverlayCollectionBehaviorOnMainThread(
            windowHandle: Long,
        ) {
            calls += "setCollectionBehavior:$windowHandle"
        }

        override fun preparePopupOverlayWindowOnMainThread(
            windowHandle: Long,
        ) {
            calls += "prepareWindow:$windowHandle"
        }

        override fun orderFrontRegardlessOnMainThread(
            windowHandle: Long,
        ) {
            calls += "orderFrontRegardless:$windowHandle"
        }

        override fun activateApplicationOnMainThread() {
            calls += "activateApplication"
        }

        override fun makeKeyWindowIfPossibleOnMainThread(
            windowHandle: Long,
        ): Boolean {
            calls += "makeKeyWindowIfPossible:$windowHandle"
            return makeKeyWindowResults.removeFirstOrNull() ?: true
        }
    }
}
