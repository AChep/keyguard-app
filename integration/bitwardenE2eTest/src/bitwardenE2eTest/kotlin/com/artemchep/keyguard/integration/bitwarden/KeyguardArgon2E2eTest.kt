package com.artemchep.keyguard.integration.bitwarden

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Logs in through Keyguard's own Argon2id to accounts the server stores with that KDF. */
class KeyguardArgon2E2eTest {
    @Test
    fun `argon2id account logs in through keyguard`() = runE2eTest {
        val account = accounts.register(
            kdf = TestKdf.Argon2id(iterations = 3, memoryMib = 64, parallelism = 4),
        )

        val session = keyguardLogin(account)
        assertContentEquals(account.masterKey, session.secrets.masterKey)
        val sync = keyguardSync(session)
        assertEquals(account.email, sync.profile.email)
        val userKey = accounts.decrypt(session.secrets.encryptionKey + session.secrets.macKey, sync.profile.key)
        assertContentEquals(account.userKey, userKey)
    }

    @Test
    fun `argon2id parameters below the keyguard minimum are rejected`() = runE2eTest {
        // The server allows a single iteration; Keyguard's prelogin requires two.
        val account = accounts.register(
            kdf = TestKdf.Argon2id(iterations = 1, memoryMib = 16, parallelism = 1),
        )
        assertTrue(accounts.login(account).accessToken.isNotBlank())

        clearRecordedRequests()
        val failure = assertFailsWith<IllegalArgumentException> { keyguardLogin(account) }
        assertContains(failure.message.orEmpty(), "must be at least")
        val requests = recordedRequests.toList()
        assertTrue(requests.any { it.startsWith("POST /identity/accounts/prelogin") }, requests.toString())
        assertFalse("POST /identity/connect/token" in requests, requests.toString())
    }
}
