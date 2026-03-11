package com.artemchep.keyguard.common.service.quicksearch

import com.artemchep.autotype.GlobalHotKeyKey
import com.artemchep.autotype.GlobalHotKeyRegistration
import com.artemchep.autotype.GlobalHotKeyRegistrationFailureReason
import com.artemchep.autotype.GlobalHotKeyRegistrationResult
import com.artemchep.autotype.GlobalHotKeySpec
import com.artemchep.keyguard.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QuickSearchHotkeyServiceTest {
    @Test
    fun `start registers macos quick search shortcut and opens window on trigger`() {
        var registeredHotKey: GlobalHotKeySpec? = null
        var callback: (() -> Unit)? = null
        var disposed = false
        val events = mutableListOf<String>()
        val windowManager = QuickSearchWindowManager()

        val service = QuickSearchHotkeyService(
            windowManager = windowManager,
            globalHotKeyRegistrar = GlobalHotKeyRegistrar { hotKey, onPressed ->
                registeredHotKey = hotKey
                callback = onPressed
                GlobalHotKeyRegistrationResult.Success(
                    object : GlobalHotKeyRegistration {
                        override fun unregister(): Boolean {
                            disposed = true
                            return true
                        }
                    },
                )
            },
            beforeOpen = {
                events += "beforeOpen"
            },
            platform = Platform.Desktop.MacOS,
        )

        val stop = service.start()

        val hotKey = QuickSearchHotkeyService.quickSearchHotKey(Platform.Desktop.MacOS)
        assertEquals(hotKey, registeredHotKey)
        assertNotNull(callback)

        callback.invoke()

        events += "afterOpen:${windowManager.stateFlow.value.requestRevision}"
        assertEquals(listOf("beforeOpen", "afterOpen:1"), events)
        assertTrue(windowManager.stateFlow.value.visible)
        assertEquals(1, windowManager.stateFlow.value.requestRevision)

        stop()
        assertTrue(disposed)
    }

    @Test
    fun `start handles missing registration`() {
        var registerCalls = 0
        val service = QuickSearchHotkeyService(
            windowManager = QuickSearchWindowManager(),
            globalHotKeyRegistrar = GlobalHotKeyRegistrar { _, _ ->
                registerCalls += 1
                GlobalHotKeyRegistrationResult.Failure(
                    GlobalHotKeyRegistrationFailureReason.Unavailable,
                )
            },
            platform = Platform.Desktop.Windows,
        )

        val stop = service.start()

        stop()
        assertEquals(1, registerCalls)
    }

    @Test
    fun `start opens window each time hotkey callback is invoked`() {
        var callback: (() -> Unit)? = null
        var beforeOpenCalls = 0
        val windowManager = QuickSearchWindowManager()

        val service = QuickSearchHotkeyService(
            windowManager = windowManager,
            globalHotKeyRegistrar = GlobalHotKeyRegistrar { _, onPressed ->
                callback = onPressed
                GlobalHotKeyRegistrationResult.Success(
                    object : GlobalHotKeyRegistration {
                        override fun unregister(): Boolean = true
                    },
                )
            },
            beforeOpen = {
                beforeOpenCalls += 1
            },
            platform = Platform.Desktop.Linux,
        )

        service.start()
        assertNotNull(callback)

        callback.invoke()
        callback.invoke()

        assertTrue(windowManager.stateFlow.value.visible)
        assertEquals(2, windowManager.stateFlow.value.requestRevision)
        assertEquals(2, beforeOpenCalls)
    }

    @Test
    fun `quick search hotkey matches platform defaults`() {
        assertEquals(
            GlobalHotKeySpec(
                key = GlobalHotKeyKey.Space,
                isShiftPressed = true,
                isMetaPressed = true,
            ),
            QuickSearchHotkeyService.quickSearchHotKey(Platform.Desktop.MacOS),
        )
        assertEquals(
            GlobalHotKeySpec(
                key = GlobalHotKeyKey.Space,
                isCtrlPressed = true,
                isShiftPressed = true,
            ),
            QuickSearchHotkeyService.quickSearchHotKey(Platform.Desktop.Windows),
        )
        assertEquals(
            GlobalHotKeySpec(
                key = GlobalHotKeyKey.Space,
                isCtrlPressed = true,
                isShiftPressed = true,
            ),
            QuickSearchHotkeyService.quickSearchHotKey(Platform.Desktop.Linux),
        )
    }

    @Test
    fun `start does not dispose when registration fails`() {
        var registerCalls = 0
        val service = QuickSearchHotkeyService(
            windowManager = QuickSearchWindowManager(),
            globalHotKeyRegistrar = GlobalHotKeyRegistrar { _, _ ->
                registerCalls += 1
                GlobalHotKeyRegistrationResult.Failure(
                    GlobalHotKeyRegistrationFailureReason.UnsupportedPlatform,
                )
            },
            platform = Platform.Desktop.Linux,
        )

        val stop = service.start()

        stop()

        assertEquals(1, registerCalls)
    }
}
