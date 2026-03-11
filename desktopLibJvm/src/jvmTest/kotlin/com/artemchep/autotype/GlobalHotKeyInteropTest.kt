package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.sun.jna.Pointer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlobalHotKeyInteropTest {
    @Test
    fun `register global hotkey returns typed failures for native status codes`() {
        val expectations = listOf(
            -1 to GlobalHotKeyRegistrationFailureReason.UnsupportedPlatform,
            -2 to GlobalHotKeyRegistrationFailureReason.UnsupportedSession,
            -3 to GlobalHotKeyRegistrationFailureReason.InvalidShortcut,
            -4 to GlobalHotKeyRegistrationFailureReason.Unavailable,
            -5 to GlobalHotKeyRegistrationFailureReason.InternalError,
        )

        expectations.forEach { (status, expectedReason) ->
            val lib = FakeDesktopLibJna().apply {
                registerNativeGlobalHotKeyResult = status
            }

            val result = registerGlobalHotKey(
                lib = lib,
                platform = GlobalHotKeyPlatform.Windows,
                spec = GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                ),
            ) { }

            val failure = assertIs<GlobalHotKeyRegistrationResult.Failure>(result)
            assertEquals(expectedReason, failure.reason)
        }
    }

    @Test
    fun `register global hotkey dispatches callbacks on dedicated thread`() {
        val lib = FakeDesktopLibJna().apply {
            registerNativeGlobalHotKeyResult = 42
        }
        val callbackThreads = LinkedBlockingQueue<String>()
        val callerThread = Thread.currentThread().name

        val result = registerGlobalHotKey(
            lib = lib,
            platform = GlobalHotKeyPlatform.Windows,
            spec = GlobalHotKeySpec(
                key = GlobalHotKeyKey.Space,
                isCtrlPressed = true,
                isShiftPressed = true,
            ),
        ) {
            callbackThreads.offer(Thread.currentThread().name)
        }

        assertIs<GlobalHotKeyRegistrationResult.Success>(result)

        lib.globalHotKeyCallback!!.invoke(42)

        val callbackThread = callbackThreads.poll(5, TimeUnit.SECONDS)
        assertEquals("keyguard-global-hotkey-callback", callbackThread)
        assertTrue(callbackThread != callerThread)
    }

    @Test
    fun `registered global hotkey unregisters only once`() {
        val lib = FakeDesktopLibJna().apply {
            registerNativeGlobalHotKeyResult = 7
        }
        val result = registerGlobalHotKey(
            lib = lib,
            platform = GlobalHotKeyPlatform.Windows,
            spec = GlobalHotKeySpec(
                key = GlobalHotKeyKey.Space,
                isCtrlPressed = true,
                isShiftPressed = true,
            ),
        ) { }
        val success = assertIs<GlobalHotKeyRegistrationResult.Success>(result)

        assertTrue(success.registration.unregister())
        assertTrue(!success.registration.unregister())
        assertEquals(listOf(7), lib.unregisteredHotKeys)
    }

    @Test
    fun `register global hotkey rejects unsupported key for platform`() {
        val lib = FakeDesktopLibJna()

        val result = registerGlobalHotKey(
            lib = lib,
            platform = GlobalHotKeyPlatform.MacOS,
            spec = GlobalHotKeySpec(
                key = GlobalHotKeyKey.Insert,
                isMetaPressed = true,
            ),
        ) { }

        val failure = assertIs<GlobalHotKeyRegistrationResult.Failure>(result)
        assertEquals(GlobalHotKeyRegistrationFailureReason.InvalidShortcut, failure.reason)
        assertEquals(0, lib.registerCalls)
    }

    @Test
    fun `map native global hotkey spec uses platform native constants`() {
        assertEquals(
            NativeGlobalHotKeySpec(
                keyCode = 0x31,
                modifiers = (1 shl 8) or (1 shl 9),
            ),
            mapNativeGlobalHotKeySpec(
                platform = GlobalHotKeyPlatform.MacOS,
                spec = GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isShiftPressed = true,
                    isMetaPressed = true,
                ),
            ),
        )
        assertEquals(
            NativeGlobalHotKeySpec(
                keyCode = 0x20,
                modifiers = 0x0002 or 0x0004,
            ),
            mapNativeGlobalHotKeySpec(
                platform = GlobalHotKeyPlatform.Windows,
                spec = GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                ),
            ),
        )
        assertEquals(
            NativeGlobalHotKeySpec(
                keyCode = 0x0020,
                modifiers = (1 shl 2) or (1 shl 0),
            ),
            mapNativeGlobalHotKeySpec(
                platform = GlobalHotKeyPlatform.Linux,
                spec = GlobalHotKeySpec(
                    key = GlobalHotKeyKey.Space,
                    isCtrlPressed = true,
                    isShiftPressed = true,
                ),
            ),
        )
    }

    private class FakeDesktopLibJna : DesktopLibJna {
        var registerNativeGlobalHotKeyResult: Int = 1
        var globalHotKeyCallback: DesktopLibJna.GlobalHotKeyCallback? = null
        val unregisteredHotKeys = mutableListOf<Int>()
        var registerCalls: Int = 0

        override fun autoType(payload: Pointer): Boolean = true

        override fun biometricsIsSupported(): Boolean = true

        override fun biometricsVerify(
            title: Pointer,
            callback: DesktopLibJna.BiometricsVerifyCallback,
        ) = Unit

        override fun keychainAddPassword(id: Pointer, password: Pointer): Boolean = true

        override fun keychainGetPassword(id: Pointer): Pointer? = null

        override fun keychainDeletePassword(id: Pointer): Boolean = true

        override fun keychainContainsPassword(id: Pointer): Boolean = false

        override fun postNotification(
            id: Int,
            title: Pointer,
            text: Pointer,
        ): Int = 0

        override fun registerNativeGlobalHotKey(
            nativeKeyCode: Int,
            nativeModifiers: Int,
            callback: DesktopLibJna.GlobalHotKeyCallback,
        ): Int {
            registerCalls += 1
            globalHotKeyCallback = callback
            return registerNativeGlobalHotKeyResult
        }

        override fun unregisterNativeGlobalHotKey(id: Int): Boolean {
            unregisteredHotKeys += id
            return true
        }

        override fun freePointer(ptr: Pointer) = Unit
    }
}
