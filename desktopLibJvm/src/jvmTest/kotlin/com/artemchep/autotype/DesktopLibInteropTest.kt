package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.util.DisposableScope
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLibInteropTest {
    @Test
    fun `system accent color returns native color`() {
        val expected = 0xFF33_6699.toInt()
        val lib = FakeDesktopLibJna().apply {
            nativeSystemAccentColor = expected
        }

        val result = getSystemAccentColorOrDefault(lib)

        assertEquals(expected, result)
    }

    @Test
    fun `keychain add throws when native write fails`() {
        val lib = FakeDesktopLibJna().apply {
            keychainAddPasswordResult = false
        }
        val scope = DisposableScope()

        try {
            assertFailsWith<IllegalStateException> {
                scope.keychainAddPasswordOrThrow(
                    lib = lib,
                    id = "id",
                    password = "password",
                )
            }
        } finally {
            scope.dispose()
        }
    }

    @Test
    fun `keychain get throws on null pointer without freeing`() {
        val lib = FakeDesktopLibJna().apply {
            keychainGetPasswordResult = null
        }
        val scope = DisposableScope()

        try {
            assertFailsWith<IllegalStateException> {
                scope.keychainGetPasswordOrThrow(
                    lib = lib,
                    id = "id",
                )
            }
        } finally {
            scope.dispose()
        }

        assertTrue(lib.freedPointers.isEmpty())
    }

    @Test
    fun `keychain get returns password and frees pointer`() {
        val pointer = Memory(9).apply {
            setString(0L, "password")
        }
        val lib = FakeDesktopLibJna().apply {
            keychainGetPasswordResult = pointer
        }
        val scope = DisposableScope()

        val result = try {
            scope.keychainGetPasswordOrThrow(
                lib = lib,
                id = "id",
            )
        } finally {
            scope.dispose()
        }

        assertTrue(result == "password")
        assertTrue(lib.freedPointers == listOf(pointer))
    }

    @Test
    fun `biometrics verify resumes after async callback`() = runTest {
        val lib = FakeDesktopLibJna()

        val result = async {
            biometricsVerifyOrThrow(
                lib = lib,
                title = "Verify",
            )
        }
        runCurrent()
        assertTrue(!result.isCompleted)

        lib.biometricsCallback!!.invoke(true, null)

        result.await()
    }

    @Test
    fun `biometrics verify ignores duplicate completions`() = runTest {
        val lib = FakeDesktopLibJna()

        val result = async {
            biometricsVerifyOrThrow(
                lib = lib,
                title = "Verify",
            )
        }
        runCurrent()

        val callback = lib.biometricsCallback!!
        callback.invoke(true, null)
        callback.invoke(
            false,
            Memory(5).apply {
                setString(0L, "boom")
            },
        )

        result.await()
        assertTrue(result.isCompleted)
    }

    @Test
    fun `biometrics verify retains callback until callback after cancellation`() = runTest {
        val lib = FakeDesktopLibJna()
        val callbackRetention = BiometricsCallbackRetention()
        val result = async {
            biometricsVerifyOrThrow(
                lib = lib,
                title = "Verify",
                callbackRetention = callbackRetention,
            )
        }
        runCurrent()
        val callback = lib.biometricsCallback!!

        result.cancel()
        runCurrent()

        assertTrue(result.isCancelled)
        assertEquals(1, callbackRetention.size)

        callback.invoke(true, null)

        assertTrue(result.isCancelled)
        assertEquals(0, callbackRetention.size)
    }

    @Test
    fun `biometrics verify releases callback on synchronous native failure`() = runTest {
        val failure = IllegalStateException("native failure")
        val lib = FakeDesktopLibJna().apply {
            biometricsVerifyFailure = failure
        }
        val callbackRetention = BiometricsCallbackRetention()

        val actual = assertFailsWith<IllegalStateException> {
            biometricsVerifyOrThrow(
                lib = lib,
                title = "Verify",
                callbackRetention = callbackRetention,
            )
        }

        assertEquals(failure.message, actual.message)
        assertEquals(0, callbackRetention.size)
    }

    private class FakeDesktopLibJna : DesktopLibJna {
        var keychainAddPasswordResult: Boolean = true
        var keychainGetPasswordResult: Pointer? = null
        var biometricsCallback: DesktopLibJna.BiometricsVerifyCallback? = null
        var biometricsVerifyFailure: Throwable? = null
        var nativeSystemAccentColor: Int = 0
        val freedPointers = mutableListOf<Pointer>()

        override fun autoType(payload: Pointer): Boolean = true

        override fun getSystemAccentColor(): Int = nativeSystemAccentColor

        override fun biometricsIsSupported(): Boolean = true

        override fun biometricsVerify(
            title: Pointer,
            callback: DesktopLibJna.BiometricsVerifyCallback,
        ) {
            biometricsCallback = callback
            biometricsVerifyFailure?.let { throw it }
        }

        override fun keychainAddPassword(id: Pointer, password: Pointer): Boolean =
            keychainAddPasswordResult

        override fun keychainGetPassword(id: Pointer): Pointer? =
            keychainGetPasswordResult

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
        ): Int = 0

        override fun unregisterNativeGlobalHotKey(id: Int): Boolean = false

        override fun freePointer(ptr: Pointer) {
            freedPointers += ptr
        }
    }
}
