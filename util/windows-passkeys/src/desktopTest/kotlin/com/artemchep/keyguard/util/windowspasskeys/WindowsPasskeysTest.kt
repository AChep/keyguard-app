package com.artemchep.keyguard.util.windowspasskeys

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class WindowsPasskeysTest {
    /** Executes the [request] as a native operation of its own. */
    private fun execute(request: ByteArray): ByteArray {
        library.ensureLoaded()
        val handle = NativeWindowsPasskeys.create()
        assertNotEquals(0L, handle)
        try {
            return NativeWindowsPasskeys.execute(handle, request)
        } finally {
            NativeWindowsPasskeys.close(handle)
        }
    }

    @Test
    fun bridgeRejectsMalformedCommandsWithoutThrowingThroughJni() {
        library.ensureLoaded()
        assertEquals(WINDOWS_PASSKEYS_ABI_VERSION, NativeWindowsPasskeys.abiVersion())
        // Not protobuf, a field number of zero, and a command this bridge does not know.
        val requests = listOf(byteArrayOf(-1, -1), byteArrayOf(0, 0), byteArrayOf(0x7a, 0))
        for (request in requests) {
            val response = execute(request)
            // A zero status byte, then the reply with the invalid-command error.
            assertContentEquals(byteArrayOf(0, 0x08, 0x01), response)
            val error = assertFailsWith<WindowsPasskeyException> { decodeReply(response) }
            assertEquals(WindowsPasskeyException.FAILED, error.code)
        }
    }

    @Test
    fun bridgeRejectionIsAWindowsPasskeyException() {
        // Shorter than any command, so the shared JNI helper rejects it with a status byte.
        val response = execute(ByteArray(1))
        assertEquals(1, response.size)
        val error = assertFailsWith<WindowsPasskeyException> { decodeReply(response) }
        assertEquals(WindowsPasskeyException.FAILED, error.code)
    }

    @Test
    fun aCancelledOrUnknownOperationRunsNoCommand() {
        library.ensureLoaded()
        val request = encodeCommand(WindowsPasskeyCommands.retire)
        val handle = NativeWindowsPasskeys.create()
        NativeWindowsPasskeys.cancel(handle)
        // The status bytes of a canceled operation and of an unknown one.
        assertContentEquals(byteArrayOf(2), NativeWindowsPasskeys.execute(handle, request))
        NativeWindowsPasskeys.close(handle)
        assertContentEquals(byteArrayOf(1), NativeWindowsPasskeys.execute(handle, request))
        assertContentEquals(byteArrayOf(1), NativeWindowsPasskeys.execute(0, request))
    }

    @Test
    fun unpackagedProcessDoesNotRegisterAnAuthenticator() {
        val response = execute(encodeCommand(WindowsPasskeyCommands.status))
        val status = decodeReply(response).expect<ReplyOutcomeProto.Status>().value.toModel()
        assertTrue(status in listOf(WindowsPasskeyStatus.UNPACKAGED, WindowsPasskeyStatus.UNSUPPORTED))
    }

    @Test
    fun eventWaitCommandIsUnderstoodWithoutAProvider() {
        if (!WindowsPasskeys.isWindows) return
        val error = assertFailsWith<WindowsPasskeyException> {
            runBlocking { WindowsPasskeys.next(active = null, timeout = 1.seconds) }
        }
        // A malformed command would fail; this process cannot start the provider instead.
        assertTrue(error.code in PROVIDERLESS, "code ${error.code}")
    }

    @Test
    fun nothingKeepsAProcessWithoutAProvider() {
        assertTrue(runBlocking { WindowsPasskeys.retire() })
    }

    @Test
    fun everyCommandIsUnderstoodWithoutAProvider() {
        if (!WindowsPasskeys.isWindows) return
        val identity = WindowsPasskeyIdentity(
            credentialId = byteArrayOf(1),
            rpId = "example.com",
            rpName = "Example",
            userId = byteArrayOf(2),
            userName = "alice",
            userDisplayName = "Alice",
        )
        val assertion = WindowsPasskeyResponse(authenticatorData = byteArrayOf(0), credentialId = byteArrayOf(1))
        val commands = listOf<suspend () -> Unit>(
            { WindowsPasskeys.register() },
            { WindowsPasskeys.unregister() },
            { WindowsPasskeys.clear() },
            { WindowsPasskeys.setLocked(true) },
            { WindowsPasskeys.complete(1, null) },
            { WindowsPasskeys.complete(1, null, WindowsPasskeyFailure.NOT_FOUND) },
            { WindowsPasskeys.complete(1, assertion) },
            { WindowsPasskeys.verify(1, 0, "alice", "example.com") },
            { WindowsPasskeys.sync(listOf(identity)) },
            { WindowsPasskeys.stop() },
        )
        for (command in commands) {
            val error = assertFailsWith<WindowsPasskeyException> { runBlocking { command() } }
            assertTrue(error.code in PROVIDERLESS, "code ${error.code}")
        }
    }

    private companion object {
        /** Why a test process has no provider: an old Windows, or no MSIX package. */
        val PROVIDERLESS = setOf(WindowsPasskeyException.UNSUPPORTED, WindowsPasskeyException.UNPACKAGED)
    }
}
