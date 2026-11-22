package com.artemchep.keyguard.feature.browserextension.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserProtocolTest {
    @Test
    fun `the prologue binds the origin`() {
        val prologue = BrowserProtocol.prologue("chrome-extension://abcdefghijklmnopabcdefghijklmnop/")
        assertContentEquals(
            "keyguard-browser/v1\nchrome-extension://abcdefghijklmnopabcdefghijklmnop/".encodeToByteArray(),
            prologue,
        )
        // 20 bytes, ending in a line feed, then the origin.
        assertEquals(0x0a, prologue[19].toInt())
    }

    @Test
    fun `a session takes the lower of both sides' highest versions, if the app speaks it`() {
        // The extension's highest, the app's versions, the version of the session.
        for ((extensionMax, versions, expected) in listOf(
            Triple(1, 1..1, 1),
            // A future extension against this app.
            Triple(5, 1..1, 1),
            Triple(0, 1..1, null),
            Triple(1, 1..2, 1),
            // An extension older than the oldest version of the app.
            Triple(1, 2..3, null),
            Triple(3, 1..2, 2),
        )) {
            assertEquals(expected, BrowserProtocol.negotiate(extensionMax, versions), "$extensionMax vs $versions")
        }
    }

    @Test
    fun `chromium origins and firefox ids are valid`() {
        for (origin in listOf(
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop/",
            "keyguard@artemchep.com",
            "{8a3b1c2d-4e5f-6a7b-8c9d-0e1f2a3b4c5d}",
            "a".repeat(255),
        )) {
            assertTrue(BrowserOrigin.isValid(origin), origin)
        }
    }

    @Test
    fun `other origins are invalid`() {
        for (origin in listOf(
            "",
            "chrome-extension://abcdefghijklmnopabcdefghijklmno/",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnopa/",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnoq/",
            "chrome-extension://ABCDEFGHIJKLMNOPABCDEFGHIJKLMNOP/",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop",
            "a".repeat(256),
            "with space",
            "with/slash",
            "ключ",
            "tab\t",
        )) {
            assertFalse(BrowserOrigin.isValid(origin), origin)
        }
    }

    @Test
    fun `the gates of README section 4`() {
        // paired, unlocked
        val sessions = listOf(false to false, false to true, true to false, true to true)
        val invalid = BrowserError.INVALID_REQUEST
        val unpaired = BrowserError.UNPAIRED
        val locked = BrowserError.VAULT_LOCKED
        val expected = mapOf(
            BrowserGate.NONE to listOf(null, null, null, null),
            BrowserGate.UNPAIRED to listOf(null, null, invalid, invalid),
            BrowserGate.PAIRED to listOf(unpaired, unpaired, null, null),
            BrowserGate.UNLOCKED to listOf(unpaired, unpaired, locked, null),
            BrowserGate.APPROVAL to listOf(unpaired, unpaired, locked, null),
        )
        for ((gate, errors) in expected) {
            assertEquals(errors, sessions.map { (paired, unlocked) -> gate.check(paired, unlocked) }, gate.name)
        }
    }

    @Test
    fun `requests are looked up by their wire names`() {
        assertEquals(BrowserRequestType.PAIR_COMMIT, BrowserRequestType.of("pair_commit"))
        assertEquals(BrowserCapability.PASSKEYS, BrowserRequestType.of("passkey_get")?.capability)
        // A passkey request on a locked vault shows the unlock prompt first.
        assertEquals(BrowserGate.PAIRED, BrowserRequestType.of("passkey_create")?.gate)
        assertNull(BrowserRequestType.of("delete_everything"))
    }
}
