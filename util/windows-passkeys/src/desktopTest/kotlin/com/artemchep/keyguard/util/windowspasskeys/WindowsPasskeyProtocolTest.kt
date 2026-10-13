package com.artemchep.keyguard.util.windowspasskeys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Checks the encodings in schema/fixtures/, which the Rust tests check as well: Kotlin
 * encodes each command to its fixture, and decodes each reply fixture to its value.
 */
class WindowsPasskeyProtocolTest {
    @Test
    fun commandsEncodeToTheSharedFixtures() {
        val identity = WindowsPasskeyIdentity(
            credentialId = byteArrayOf(1),
            rpId = "example.com",
            rpName = "Example",
            userId = byteArrayOf(2),
            userName = "alice",
            userDisplayName = "Alice",
        )
        val assertion = WindowsPasskeyResponse(
            authenticatorData = ByteArray(3) { 0xaa.toByte() },
            credentialId = byteArrayOf(1, 2),
            signature = byteArrayOf(3),
            userHandle = byteArrayOf(4),
        )
        val attestation = WindowsPasskeyResponse(authenticatorData = ByteArray(3) { 0xaa.toByte() })
        val commands = with(WindowsPasskeyCommands) {
            linkedMapOf(
                "status" to status,
                "register" to register,
                "unregister" to unregister,
                "clear" to clear,
                "start" to start,
                "stop" to stop,
                "retire" to retire,
                "limits" to limits,
                "next_unbounded" to next(active = null, timeout = null),
                "next_known" to next(active = 7, timeout = 30.seconds),
                "next_zero" to next(active = 0, timeout = Duration.ZERO),
                "set_locked" to setLocked(true),
                "set_unlocked" to setLocked(false),
                "complete_abandoned" to complete(1, null, null),
                "complete_not_found" to complete(1, null, WindowsPasskeyFailure.NOT_FOUND),
                "complete_assertion" to complete(2, assertion, null),
                "complete_registration" to complete(3, attestation, null),
                "verify" to verify(1, window = -2, username = "alice", hint = "example.com", unlocked = false),
                "verify_unlocked" to verify(1, window = -2, username = "alice", hint = "example.com", unlocked = true),
                "sync" to sync(listOf(identity)),
                "sync_empty" to sync(emptyList()),
            )
        }
        val fixtures = fixtures("commands.pbhex")
        assertEquals(fixtures.keys.toList(), commands.keys.toList(), "The same messages, in order")
        for ((name, command) in commands) {
            assertEquals(fixtures.getValue(name), encodeCommand(command).toHexString(), name)
        }
    }

    @Test
    fun repliesDecodeFromTheSharedFixtures() {
        val signIn = WindowsPasskeyRequest(
            id = 1,
            create = false,
            window = -2,
            rpId = "example.com",
            clientDataHash = ByteArray(32) { 7 },
            userVerification = "required",
            credentials = listOf(WindowsPasskeyDescriptor(id = ByteArray(16) { 0x11 }, type = "public-key")),
            user = null,
            rpName = null,
            algorithms = emptyList(),
            discoverable = false,
            expiresInMs = 110_000,
        )
        val registration = WindowsPasskeyRequest(
            id = 2,
            create = true,
            window = 42,
            rpId = "example.com",
            clientDataHash = ByteArray(32) { 7 },
            userVerification = "preferred",
            credentials = emptyList(),
            user = WindowsPasskeyUser(id = "user".encodeToByteArray(), name = "alice", displayName = "Alice"),
            rpName = "Example",
            algorithms = listOf(-7, -257),
            discoverable = true,
            expiresInMs = 110_000,
        )
        val replies = linkedMapOf<String, Any>(
            "error_invalid" to Failure(WindowsPasskeyException.FAILED),
            "error_unsupported" to Failure(WindowsPasskeyException.UNSUPPORTED),
            "error_unpackaged" to Failure(WindowsPasskeyException.UNPACKAGED),
            "error_cancelled" to Failure(WindowsPasskeyException.CANCELLED),
            "error_dismissed" to Failure(WindowsPasskeyException.DISMISSED),
            "done" to Unit,
            "status_unsupported" to WindowsPasskeyStatus.UNSUPPORTED,
            "status_unpackaged" to WindowsPasskeyStatus.UNPACKAGED,
            "status_unavailable" to WindowsPasskeyStatus.UNAVAILABLE,
            "status_unregistered" to WindowsPasskeyStatus.UNREGISTERED,
            "status_disabled" to WindowsPasskeyStatus.DISABLED,
            "status_enabled" to WindowsPasskeyStatus.ENABLED,
            "retired" to true,
            "not_retired" to false,
            "limits" to LimitsProto(credentialIdBytes = 1023, userIdBytes = 64, textBytes = 4096),
            "event_idle" to WindowsPasskeyEvent(request = null, activeId = null),
            "event_active" to WindowsPasskeyEvent(request = null, activeId = 1, watching = true),
            "event_closed" to WindowsPasskeyEvent(request = null, activeId = null, closed = true),
            "event_sign_in" to WindowsPasskeyEvent(request = signIn, activeId = 1, watching = true),
            "event_registration" to WindowsPasskeyEvent(request = registration, activeId = 2),
        )
        val fixtures = fixtures("replies.pbhex")
        assertEquals(fixtures.keys.toList(), replies.keys.toList(), "The same messages, in order")
        for ((name, value) in replies) {
            assertEquals(value, decode(fixtures.getValue(name)), name)
        }
    }

    /** A reply that ends a command with an error [code]. */
    private data class Failure(val code: Int)

    /** The value of the framed reply fixture, as the bridge reads it. */
    private fun decode(hex: String): Any = try {
        when (val outcome = decodeReply(byteArrayOf(0) + hex.hexToByteArray())) {
            is ReplyOutcomeProto.Done -> Unit
            is ReplyOutcomeProto.Status -> outcome.value.toModel()
            is ReplyOutcomeProto.Event -> outcome.value.toModel()
            is ReplyOutcomeProto.Limits -> outcome.value
            is ReplyOutcomeProto.Retired -> outcome.value
            is ReplyOutcomeProto.Error -> error("decodeReply throws errors")
        }
    } catch (e: WindowsPasskeyException) {
        Failure(e.code)
    }

    /** The hex of each fixture by its name, in file order. */
    private fun fixtures(name: String): Map<String, String> {
        val text = requireNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture $name" }.readText()
        return text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val (fixture, hex) = line.split(' ', limit = 2)
                fixture to hex.trim()
            }
    }
}
