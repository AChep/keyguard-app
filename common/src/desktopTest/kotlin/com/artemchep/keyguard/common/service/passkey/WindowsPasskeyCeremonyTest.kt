package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.MasterSession.Key.Persisted
import com.artemchep.keyguard.common.service.passkey.FakeWindowsPasskeyBridge.Verification
import com.artemchep.keyguard.common.service.session.testMasterSessionKey
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyException
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyFailure
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class WindowsPasskeyCeremonyTest {
    private val authenticator = testAuthenticator()
    private val passkey = SigningPasskey(authenticator)
    private val clock = object : Clock {
        override fun now() = TEST_NOW
    }

    private inner class Fixture(private val scope: CoroutineScope, session: MasterSession, vararg ciphers: DSecret) {
        val bridge = FakeWindowsPasskeyBridge()
        val sessions = FakeVaultSessions(session)
        val locker = sessions.locker(scope)
        val vault = FakeVault(*ciphers)
        var failures = 0
        val ceremony = WindowsPasskeyCeremony(
            bridge = bridge,
            getVaultSession = sessions,
            vaultAccess = vault.access,
            vaultSessionLocker = locker,
            authenticator = authenticator,
            onFailure = { failures += 1 },
            clock = clock,
        )

        /** Delivers [request] the way the service does. */
        fun serve(request: WindowsPasskeyRequest) =
            WindowsPasskeyOperation(request.id, scope) { ceremony.handle(request, it) }.also { it.start() }
    }

    @Test
    fun `closing the picker keeps the vault alive through Hello and completion resumes idle locking`() = runTest {
        val key = testMasterSessionKey(Persisted)
        val cipher = testCipher()
        val fixture = Fixture(backgroundScope, key, cipher)
        val window = backgroundScope.launch { fixture.locker.keepAlive() }
        fixture.bridge.hello = CompletableDeferred()
        try {
            fixture.serve(testRegistrationRequest())
            runCurrent()
            assertIs<AppMode.SavePasskey>(fixture.ceremony.ui.value?.mode).onComplete(cipher)
            runCurrent()
            assertNull(fixture.ceremony.ui.value)
            assertEquals(1, fixture.bridge.verifications.size)

            // Disposing the picker releases the last window's keep-alive.
            window.cancelAndJoin()
            runCurrent()
            advanceTimeBy(20.seconds)
            runCurrent()
            assertTrue(key.session.active.value)
            assertFalse(fixture.bridge.answered(1))
        } finally {
            fixture.bridge.hello.complete(Unit)
        }
        runCurrent()
        assertNotNull(fixture.bridge.answer(1))
        assertEquals(cipher.id, fixture.vault.added.single().cipherId)

        // The completed request must not hold the session open indefinitely.
        advanceTimeBy(11.seconds)
        runCurrent()
        assertFalse(key.session.active.value)
        assertIs<MasterSession.Empty>(fixture.sessions.state.value)
    }

    @Test
    fun `a single match suspends an almost expired idle timeout while Hello is open`() = runTest {
        val key = testMasterSessionKey(Persisted)
        val fixture = Fixture(backgroundScope, key, testCipher(passkey.credential))
        fixture.locker.start()
        runCurrent()
        // Only one second remains on the existing inactivity timer.
        advanceTimeBy(10.seconds)
        runCurrent()
        fixture.bridge.hello = CompletableDeferred()
        try {
            fixture.serve(testSignInRequest())
            runCurrent()
            assertNull(fixture.ceremony.ui.value)
            assertEquals(1, fixture.bridge.verifications.size)

            advanceTimeBy(20.seconds)
            runCurrent()
            assertTrue(key.session.active.value)
            assertFalse(fixture.bridge.answered(1))
        } finally {
            fixture.bridge.hello.complete(Unit)
        }
        runCurrent()
        assertTrue(passkey.signed(assertNotNull(fixture.bridge.answer(1))))
    }

    @Test
    fun `cancellation releases the keep alive before the native Hello prompt returns`() = runTest {
        val key = testMasterSessionKey(Persisted)
        val fixture = Fixture(backgroundScope, key, testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred()
        try {
            val operation = fixture.serve(testSignInRequest())
            runCurrent()
            assertEquals(1, fixture.bridge.verifications.size)

            operation.cancelIfPending()
            runCurrent()
            advanceTimeBy(11.seconds)
            runCurrent()
            assertFalse(fixture.bridge.hello.isCompleted)
            assertFalse(key.session.active.value)
            assertIs<MasterSession.Empty>(fixture.sessions.state.value)
        } finally {
            fixture.bridge.hello.complete(Unit)
        }
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertTrue(fixture.vault.used.isEmpty())
    }

    @Test
    fun `a restored session signs only after Windows Hello and then records the use`() = runTest {
        val cipher = testCipher(passkey.credential)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.bridge.hello = CompletableDeferred()
        fixture.serve(testSignInRequest())
        runCurrent()
        // The only match is used without a picker.
        assertNull(fixture.ceremony.ui.value)
        assertEquals(listOf(Verification(1, TEST_WINDOW, "alice", TEST_RP_ID)), fixture.bridge.verifications)
        assertFalse(fixture.bridge.answered(1))

        fixture.bridge.hello.complete(Unit)
        runCurrent()
        val answer = assertNotNull(fixture.bridge.answer(1))
        assertTrue(passkey.signed(answer))
        assertContentEquals(passkey.credential.descriptor().id, answer.credentialId)
        assertContentEquals(TEST_USER_HANDLE, answer.userHandle)
        val usage = fixture.vault.used.single()
        assertEquals(cipher.id, usage.cipherId)
        assertEquals(passkey.credential.credentialId, usage.credentialId)
    }

    @Test
    fun `a name the Windows Hello prompt cannot show is replaced by the site`() = runTest {
        for (name in listOf("ali\u0000ce", "a".repeat(4097))) {
            val credential = passkey.credential.copy(userName = name)
            val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(credential))
            fixture.serve(testSignInRequest())
            runCurrent()
            assertEquals(listOf(Verification(1, TEST_WINDOW, TEST_RP_ID, TEST_RP_ID)), fixture.bridge.verifications)
            assertTrue(passkey.signed(assertNotNull(fixture.bridge.answer(1))))
        }
    }

    @Test
    fun `a stored user handle in any Base64 form signs in with the same bytes`() = runTest {
        for (stored in listOf("dXNlcg==", "dXNlcg")) {
            val credential = passkey.credential.copy(userHandle = stored)
            val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(credential))
            fixture.serve(testSignInRequest())
            runCurrent()
            val answer = assertNotNull(fixture.bridge.answer(1), "user handle $stored")
            assertTrue(passkey.signed(answer))
            assertContentEquals(TEST_USER_HANDLE, answer.userHandle)
        }
    }

    @Test
    fun `an unreadable user handle is caught before Windows Hello`() = runTest {
        val credential = passkey.credential.copy(userHandle = "%%%")
        // Without an allow list, the site could not identify the account: no passkey is offered.
        val discoverable = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(credential))
        discoverable.serve(testSignInRequest())
        runCurrent()
        assertEquals(WindowsPasskeyFailure.NOT_FOUND, discoverable.bridge.failure(1))
        assertTrue(discoverable.bridge.verifications.isEmpty())

        // An allow list already identifies the account, so the handle is left out.
        val listed = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(credential))
        listed.serve(testSignInRequest(allowed = listOf(credential.descriptor())))
        runCurrent()
        val answer = assertNotNull(listed.bridge.answer(1))
        assertTrue(passkey.signed(answer))
        assertNull(answer.userHandle)
    }

    @Test
    fun `unlocking with the password during the request replaces Windows Hello`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty(), testCipher(passkey.credential))
        fixture.serve(testSignInRequest())
        runCurrent()
        // The request window shows the unlock screen.
        assertEquals(1L, fixture.ceremony.ui.value?.request?.id)
        assertNull(fixture.ceremony.ui.value?.mode)

        fixture.sessions.state.value = testMasterSessionKey(MasterSession.Key.Authenticated)
        runCurrent()
        // The bridge learns that the unlock verified the user; no Hello prompt is shown.
        assertEquals(
            listOf(Verification(1, TEST_WINDOW, "alice", TEST_RP_ID, unlocked = true)),
            fixture.bridge.verifications,
        )
        assertTrue(passkey.signed(assertNotNull(fixture.bridge.answer(1))))
    }

    @Test
    fun `the unlock countdown ends when Windows stops waiting`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty(), testCipher(passkey.credential))
        fixture.serve(testSignInRequest())
        runCurrent()
        // Not a full 120 seconds: Windows started its clock before the request arrived.
        assertEquals(TEST_NOW + TEST_EXPIRES_IN_MS.milliseconds, fixture.ceremony.ui.value?.expiresAt)
    }

    @Test
    fun `a session restored during the request still requires Windows Hello`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty(), testCipher(passkey.credential))
        fixture.serve(testSignInRequest())
        runCurrent()

        fixture.sessions.state.value = testMasterSessionKey(Persisted)
        runCurrent()
        assertEquals(1, fixture.bridge.verifications.size)
        assertTrue(passkey.signed(assertNotNull(fixture.bridge.answer(1))))
    }

    @Test
    fun `an item that requires the password is never picked automatically`() = runTest {
        val cipher = testCipher(passkey.credential).copy(reprompt = true)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.serve(testSignInRequest())
        runCurrent()
        val mode = assertIs<AppMode.PickPasskey>(fixture.ceremony.ui.value?.mode)
        assertTrue(fixture.bridge.verifications.isEmpty())
        assertFalse(fixture.bridge.answered(1))

        // The picker completes only after the user confirms the password.
        mode.onComplete(cipher, passkey.credential)
        runCurrent()
        assertNull(fixture.ceremony.ui.value)
        assertEquals(1, fixture.bridge.verifications.size)
        assertTrue(passkey.signed(assertNotNull(fixture.bridge.answer(1))))
    }

    @Test
    fun `an item that starts to require the password during Windows Hello is not signed`() = runTest {
        val cipher = testCipher(passkey.credential)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.bridge.hello = CompletableDeferred()
        fixture.serve(testSignInRequest())
        runCurrent()

        fixture.vault.ciphers.value = listOf(cipher.copy(reprompt = true))
        fixture.bridge.hello.complete(Unit)
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertTrue(fixture.vault.used.isEmpty())
        assertEquals(1, fixture.failures)
    }

    @Test
    fun `a passkey removed, archived or deleted during Windows Hello is not signed`() = runTest {
        val cipher = testCipher(passkey.credential)
        val changes = listOf(
            cipher.copy(login = cipher.login!!.copy(fido2Credentials = emptyList())),
            cipher.copy(archivedDate = TEST_NOW),
            cipher.copy(deletedDate = TEST_NOW),
        )
        for ((index, changed) in changes.withIndex()) {
            val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
            fixture.bridge.hello = CompletableDeferred()
            fixture.serve(testSignInRequest())
            runCurrent()

            fixture.vault.ciphers.value = listOf(changed)
            fixture.bridge.hello.complete(Unit)
            runCurrent()
            assertTrue(fixture.bridge.answered(1), "change $index")
            assertNull(fixture.bridge.answer(1), "change $index")
        }
    }

    @Test
    fun `locking the vault during Windows Hello cancels the request at once`() = runTest {
        val key = testMasterSessionKey(Persisted)
        val fixture = Fixture(backgroundScope, key, testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred()
        fixture.serve(testSignInRequest())
        runCurrent()

        key.session.retire()
        runCurrent()
        // Windows learns of it while its Hello prompt may still be open.
        assertTrue(fixture.bridge.answered(1))
        assertNull(fixture.bridge.answer(1))

        fixture.bridge.hello.complete(Unit)
        runCurrent()
        assertTrue(fixture.bridge.completions.all { (_, response) -> response == null })
        assertTrue(fixture.vault.used.isEmpty())
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `replacing the session during Windows Hello cancels the request`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred()
        fixture.serve(testSignInRequest())
        runCurrent()

        fixture.sessions.state.value = testMasterSessionKey(Persisted)
        fixture.bridge.hello.complete(Unit)
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `a request Windows stops waiting on during Windows Hello ends without an error`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred()
        fixture.serve(testSignInRequest())
        runCurrent()

        // The site cancelled, or the request expired.
        fixture.bridge.ended += 1
        fixture.bridge.hello.complete(Unit)
        runCurrent()
        assertFalse(fixture.bridge.answered(1))
        assertTrue(fixture.vault.used.isEmpty())
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `closing the Windows Hello prompt tells Windows the user dismissed the request`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred<Unit>().apply {
            completeExceptionally(WindowsPasskeyException(WindowsPasskeyException.DISMISSED))
        }
        fixture.serve(testSignInRequest())
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertEquals(WindowsPasskeyFailure.DISMISSED, fixture.bridge.failure(1))
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `a response Windows cannot receive is reported and its use is not recorded`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        fixture.bridge.rejectsResponses = true
        fixture.serve(testSignInRequest())
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertTrue(fixture.vault.used.isEmpty())
        assertEquals(1, fixture.failures)
    }

    @Test
    fun `a failed Windows Hello is reported and cancels the request`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        fixture.bridge.hello = CompletableDeferred<Unit>().apply {
            completeExceptionally(WindowsPasskeyException(WindowsPasskeyException.FAILED))
        }
        fixture.serve(testSignInRequest())
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        // Not a reason the site should learn.
        assertNull(fixture.bridge.failure(1))
        assertEquals(1, fixture.failures)
    }

    @Test
    fun `a site asking for passkeys the vault lacks learns that none was found`() = runTest {
        val other = testPasskey(credentialId = "00000000-0000-0000-0000-000000000000")
        val cases = listOf(
            testCipher() to testSignInRequest(),
            testCipher(passkey.credential) to testSignInRequest(allowed = listOf(other.descriptor())),
        )
        for ((index, case) in cases.withIndex()) {
            val (cipher, request) = case
            val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
            fixture.serve(request)
            runCurrent()
            assertNull(fixture.bridge.answer(1), "case $index")
            assertEquals(WindowsPasskeyFailure.NOT_FOUND, fixture.bridge.failure(1), "case $index")
            assertTrue(fixture.bridge.verifications.isEmpty(), "case $index")
            // The site shows its own error.
            assertEquals(0, fixture.failures, "case $index")
        }
    }

    @Test
    fun `a new passkey is stored in the picked item and then returned`() = runTest {
        val cipher = testCipher()
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.serve(testRegistrationRequest())
        runCurrent()
        val mode = assertIs<AppMode.SavePasskey>(fixture.ceremony.ui.value?.mode)
        assertTrue(fixture.bridge.verifications.isEmpty())

        mode.onComplete(cipher)
        runCurrent()
        assertEquals(listOf(Verification(1, TEST_WINDOW, "alice", TEST_RP_ID)), fixture.bridge.verifications)
        assertEquals(cipher.id, fixture.vault.added.single().cipherId)
        val answer = assertNotNull(fixture.bridge.answer(1))
        assertNull(answer.signature)
    }

    @Test
    fun `a passkey the vault does not store is never returned`() = runTest {
        val cipher = testCipher()
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.vault.storesCredentials = false
        fixture.serve(testRegistrationRequest())
        runCurrent()
        assertIs<AppMode.SavePasskey>(fixture.ceremony.ui.value?.mode).onComplete(cipher)
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertEquals(1, fixture.failures)
    }

    @Test
    fun `a read-only vault or collection gets no new passkey`() = runTest {
        val collection = DCollection(
            id = "collection", externalId = null, organizationId = null, accountId = "account",
            revisionDate = TEST_NOW, name = "Shared", readOnly = true, hidePasswords = false,
        )
        val cases = listOf<(Fixture) -> Unit>(
            { it.vault.canWrite.value = false },
            { it.vault.collections.value = listOf(collection) },
        )
        for ((index, case) in cases.withIndex()) {
            val cipher = testCipher().copy(collectionIds = setOf(collection.id))
            val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
            case(fixture)
            fixture.serve(testRegistrationRequest())
            runCurrent()
            assertIs<AppMode.SavePasskey>(fixture.ceremony.ui.value?.mode).onComplete(cipher)
            runCurrent()
            assertNull(fixture.bridge.answer(1), "case $index")
            assertTrue(fixture.vault.added.isEmpty(), "case $index")
        }
    }

    @Test
    fun `a site that excludes an existing passkey gets no new one`() = runTest {
        val cipher = testCipher(passkey.credential)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.serve(testRegistrationRequest(excluded = listOf(passkey.credential.descriptor())))
        runCurrent()
        assertIs<AppMode.SavePasskey>(fixture.ceremony.ui.value?.mode).onComplete(cipher)
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertEquals(WindowsPasskeyFailure.EXCLUDED, fixture.bridge.failure(1))
        assertTrue(fixture.vault.added.isEmpty())
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `a hidden profile's passkey never signs in but still excludes a new one`() = runTest {
        val key = testMasterSessionKey(Persisted)
        val hidden = testCipher(passkey.credential)
        val signIn = Fixture(backgroundScope, key, hidden)
        signIn.vault.profiles.value = listOf(testHiddenProfile(hidden.accountId))
        // The Windows index reads the same items.
        assertTrue(signIn.vault.access.get(key).getVisibleCiphers().first().isEmpty())
        signIn.serve(testSignInRequest())
        runCurrent()
        assertEquals(WindowsPasskeyFailure.NOT_FOUND, signIn.bridge.failure(1))
        assertTrue(signIn.bridge.verifications.isEmpty())

        val target = testCipher().copy(id = "target", accountId = "visible")
        val register = Fixture(backgroundScope, testMasterSessionKey(Persisted), hidden, target)
        register.vault.profiles.value = listOf(testHiddenProfile(hidden.accountId))
        register.serve(testRegistrationRequest(excluded = listOf(passkey.credential.descriptor())))
        runCurrent()
        assertIs<AppMode.SavePasskey>(register.ceremony.ui.value?.mode).onComplete(target)
        runCurrent()
        assertEquals(WindowsPasskeyFailure.EXCLUDED, register.bridge.failure(1))
        assertTrue(register.vault.added.isEmpty())
    }

    @Test
    fun `a request keeps the process busy until its use is recorded`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        val busy = mutableListOf<Boolean>()
        backgroundScope.launch { fixture.ceremony.busy.collect { busy += it } }
        fixture.bridge.hello = CompletableDeferred()
        fixture.serve(testSignInRequest())
        runCurrent()
        assertEquals(listOf(false, true), busy)

        fixture.bridge.hello.complete(Unit)
        runCurrent()
        assertEquals(1, fixture.vault.used.size)
        assertEquals(listOf(false, true, false), busy)
    }
}
