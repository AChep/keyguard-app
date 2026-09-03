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
import kotlin.test.assertIs
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
                windowHandle = 42L,
                title = "Verify",
            )
        }
        runCurrent()
        assertTrue(!result.isCompleted)

        lib.biometricsCallback!!.invoke(BiometricsStatus.SUCCESS.code, null)

        result.await()
        assertEquals(42L, lib.biometricsWindowHandle)
        assertEquals("Verify", lib.biometricsTitle)
    }

    @Test
    fun `biometrics verify preserves native failure status`() = runTest {
        val lib = FakeDesktopLibJna()

        val result = async {
            runCatching {
                biometricsVerifyOrThrow(
                    lib = lib,
                    windowHandle = 0L,
                    title = "Verify",
                )
            }
        }
        runCurrent()

        Memory(9).use { error ->
            error.setString(0L, "canceled")
            lib.biometricsCallback!!.invoke(BiometricsStatus.USER_CANCELED.code, error)
        }

        val exception = result.await().exceptionOrNull()
        assertIs<BiometricsException>(exception)
        assertEquals(BiometricsStatus.USER_CANCELED, exception.status)
        assertEquals("canceled", exception.message)
    }

    @Test
    fun `biometrics verify preserves native lockout status`() = runTest {
        val lib = FakeDesktopLibJna()

        val result = async {
            runCatching {
                biometricsVerifyOrThrow(
                    lib = lib,
                    windowHandle = 0L,
                    title = "Verify",
                )
            }
        }
        runCurrent()

        Memory(7).use { error ->
            error.setString(0L, "locked")
            lib.biometricsCallback!!.invoke(BiometricsStatus.SECURITY_DEVICE_LOCKED.code, error)
        }

        val exception = result.await().exceptionOrNull()
        assertIs<BiometricsException>(exception)
        assertEquals(BiometricsStatus.SECURITY_DEVICE_LOCKED, exception.status)
        assertEquals("locked", exception.message)
    }

    @Test
    fun `biometrics verify ignores duplicate completions`() = runTest {
        val lib = FakeDesktopLibJna()

        val result = async {
            biometricsVerifyOrThrow(
                lib = lib,
                windowHandle = 0L,
                title = "Verify",
            )
        }
        runCurrent()

        val callback = lib.biometricsCallback!!
        callback.invoke(BiometricsStatus.SUCCESS.code, null)
        callback.invoke(
            BiometricsStatus.UNKNOWN.code,
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
                windowHandle = 0L,
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

        callback.invoke(BiometricsStatus.SUCCESS.code, null)

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
                windowHandle = 0L,
                title = "Verify",
                callbackRetention = callbackRetention,
            )
        }

        assertEquals(failure.message, actual.message)
        assertEquals(0, callbackRetention.size)
    }

    @Test
    fun `secret transform copies result before native call returns`() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val lib = FakeDesktopLibJna().apply {
            biometricsResult = expected
        }
        val scope = DisposableScope()

        val actual = try {
            scope.biometricsTransformSecretOrThrow(
                lib = lib,
                windowHandle = 42L,
                title = "Unlock Keyguard",
                input = byteArrayOf(5, 6),
                decrypt = true,
            )
        } finally {
            scope.dispose()
        }

        assertTrue(expected.contentEquals(actual))
        assertEquals(42L, lib.biometricsWindowHandle)
        assertEquals("Unlock Keyguard", lib.biometricsTitle)
        assertEquals(1, lib.biometricsDecrypt)
    }

    @Test
    fun `secret transform preserves native failure status`() {
        val lib = FakeDesktopLibJna().apply {
            biometricsStatus = BiometricsStatus.USER_CANCELED
        }
        val scope = DisposableScope()

        val error = try {
            assertFailsWith<BiometricsException> {
                scope.biometricsTransformSecretOrThrow(
                    lib = lib,
                    windowHandle = 0L,
                    title = "Verify",
                    input = byteArrayOf(1),
                    decrypt = false,
                )
            }
        } finally {
            scope.dispose()
        }

        assertEquals(BiometricsStatus.USER_CANCELED, error.status)
        assertEquals(0, lib.biometricsDecrypt)
    }

    private class FakeDesktopLibJna : DesktopLibJna {
        var keychainAddPasswordResult: Boolean = true
        var keychainGetPasswordResult: Pointer? = null
        var biometricsCallback: DesktopLibJna.BiometricsVerifyCallback? = null
        var biometricsVerifyFailure: Throwable? = null
        var biometricsStatus: BiometricsStatus = BiometricsStatus.SUCCESS
        var biometricsResult: ByteArray = byteArrayOf(1)
        var biometricsWindowHandle: Long = 0L
        var biometricsTitle: String = ""
        var biometricsDecrypt: Int = 0
        var nativeSystemAccentColor: Int = 0
        val freedPointers = mutableListOf<Pointer>()

        override fun autoType(payload: Pointer): Boolean = true

        override fun getSystemAccentColor(): Int = nativeSystemAccentColor

        override fun biometricsIsSupported(): Boolean = true

        override fun biometricsVerify(
            windowHandle: Long,
            title: Pointer,
            callback: DesktopLibJna.BiometricsVerifyCallback,
        ) {
            biometricsWindowHandle = windowHandle
            biometricsTitle = title.getString(0L)
            biometricsCallback = callback
            biometricsVerifyFailure?.let { throw it }
        }

        override fun biometricsDeleteCredential(): Int = 1

        override fun biometricsTransformSecret(
            windowHandle: Long,
            title: Pointer,
            input: Pointer,
            inputLength: Long,
            decrypt: Int,
            callback: DesktopLibJna.BiometricsResultCallback,
        ): Int {
            biometricsWindowHandle = windowHandle
            biometricsTitle = title.getString(0L)
            biometricsDecrypt = decrypt
            if (biometricsStatus == BiometricsStatus.SUCCESS) {
                Memory(biometricsResult.size.toLong()).use { result ->
                    result.write(0L, biometricsResult, 0, biometricsResult.size)
                    callback.invoke(
                        biometricsStatus.code,
                        result,
                        biometricsResult.size.toLong(),
                        null,
                    )
                }
            } else {
                callback.invoke(biometricsStatus.code, null, 0L, null)
            }
            return 1
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
