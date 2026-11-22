package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.MasterSession.Key.Persisted
import com.artemchep.keyguard.common.service.passkey.FakeWindowsPasskeyBridge.Verification
import com.artemchep.keyguard.common.service.session.testMasterSessionKey
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyException
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyFailure
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyPrf
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyPrfCredentialValues
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyPrfValues
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyRequest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
@Suppress("TooManyFunctions", "LargeClass") // Ceremony scenarios share the same fixture.
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
        val prompts get() = ceremony.prompts

        init {
            // As the service does.
            scope.launch { prompts.denyWhenSessionEnds(sessions()) }
        }

        /** The one prompt waiting for the user. */
        fun prompt() = prompts.pending.value.single()

        /** The choices of the one sign-in prompt waiting for the user. */
        fun choices() = assertIs<PasskeyApprovalOperation.SignIn>(prompt().operation).choices

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
        // The approval window asks to unlock; a sign-in never opens the vault window.
        assertEquals(PasskeyApprovalOperation.Unlock, fixture.prompt().operation)
        assertEquals(TEST_RP_ID, fixture.prompt().rpId)
        assertNull(fixture.ceremony.ui.value)

        fixture.sessions.state.value = testMasterSessionKey(MasterSession.Key.Authenticated)
        runCurrent()
        assertTrue(fixture.prompts.pending.value.isEmpty())
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
        assertEquals(TEST_NOW + TEST_EXPIRES_IN_MS.milliseconds, fixture.prompt().expiresAt)

        // The request ends as one Windows stopped waiting on: no reason, no error.
        advanceTimeBy(TEST_EXPIRES_IN_MS.milliseconds + 1.seconds)
        runCurrent()
        assertTrue(fixture.prompts.pending.value.isEmpty())
        assertTrue(fixture.bridge.answered(1))
        assertNull(fixture.bridge.answer(1))
        assertNull(fixture.bridge.failure(1))
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `denying the unlock tells Windows the user dismissed the request`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty(), testCipher(passkey.credential))
        fixture.serve(testSignInRequest())
        runCurrent()

        assertTrue(fixture.prompts.respond(fixture.prompt().id, approved = false))
        runCurrent()
        assertEquals(WindowsPasskeyFailure.DISMISSED, fixture.bridge.failure(1))
        assertTrue(fixture.bridge.verifications.isEmpty())
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `a locked registration still unlocks in the vault window`() = runTest {
        val fixture = Fixture(backgroundScope, MasterSession.Empty(), testCipher())
        fixture.serve(testRegistrationRequest())
        runCurrent()
        assertEquals(1L, fixture.ceremony.ui.value?.request?.id)
        assertNull(fixture.ceremony.ui.value?.mode)
        assertTrue(fixture.prompts.pending.value.isEmpty())
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
        // Approving without confirming the identity denies the prompt.
        val unconfirmed = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        unconfirmed.serve(testSignInRequest())
        runCurrent()
        assertEquals(listOf(true), unconfirmed.choices().map { it.reprompt })
        assertTrue(unconfirmed.bridge.verifications.isEmpty())
        assertTrue(unconfirmed.prompts.respond(unconfirmed.prompt().id, approved = true))
        runCurrent()
        assertEquals(WindowsPasskeyFailure.DISMISSED, unconfirmed.bridge.failure(1))
        assertTrue(unconfirmed.bridge.verifications.isEmpty())

        val confirmed = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        confirmed.serve(testSignInRequest())
        runCurrent()
        val id = confirmed.prompt().id
        assertTrue(confirmed.prompts.confirmIdentity(id) { true })
        assertTrue(confirmed.prompts.respond(id, approved = true))
        runCurrent()
        assertNull(confirmed.ceremony.ui.value)
        assertEquals(1, confirmed.bridge.verifications.size)
        assertTrue(passkey.signed(assertNotNull(confirmed.bridge.answer(1))))
    }

    @Test
    fun `several matches are offered most recently used first`() = runTest {
        val other = SigningPasskey(authenticator)
        val first = testCipher(passkey.credential).copy(id = "first", name = "First")
        val second = testCipher(other.credential).copy(id = "second", name = "Second")
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), first, second)
        fixture.vault.lastUsed.value = mapOf("second" to TEST_NOW)
        fixture.serve(testSignInRequest())
        runCurrent()
        assertEquals(
            listOf(
                PasskeyApprovalChoice(name = "Second", username = "alice", accountName = null, reprompt = false),
                PasskeyApprovalChoice(name = "First", username = "alice", accountName = null, reprompt = false),
            ),
            fixture.choices(),
        )
        assertEquals(TEST_NOW + TEST_EXPIRES_IN_MS.milliseconds, fixture.prompt().expiresAt)
        assertNull(fixture.ceremony.ui.value)

        // An unreadable history keeps the vault order.
        val unordered = Fixture(backgroundScope, testMasterSessionKey(Persisted), first, second)
        unordered.vault.lastUsed.value = mapOf("second" to TEST_NOW)
        unordered.vault.lastUsedFails = true
        unordered.serve(testSignInRequest())
        runCurrent()
        assertEquals(listOf("First", "Second"), unordered.choices().map { it.name })
    }

    @Test
    fun `the selected passkey signs in`() = runTest {
        val other = SigningPasskey(authenticator)
        val first = testCipher(passkey.credential).copy(id = "first")
        val second = testCipher(other.credential).copy(id = "second")
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), first, second)
        fixture.serve(testSignInRequest())
        runCurrent()
        val id = fixture.prompt().id
        assertFalse(fixture.prompts.selectPasskey(id, 2))
        assertTrue(fixture.prompts.selectPasskey(id, 1))
        assertTrue(fixture.prompts.respond(id, approved = true))
        runCurrent()
        assertTrue(other.signed(assertNotNull(fixture.bridge.answer(1))))
        assertEquals("second", fixture.vault.used.single().cipherId)
        assertFalse(fixture.prompts.selectPasskey(id, 0))
    }

    @Test
    fun `a confirmed identity holds only for the passkey it was confirmed for`() = runTest {
        val other = SigningPasskey(authenticator)
        val first = testCipher(passkey.credential).copy(id = "first", reprompt = true)
        val second = testCipher(other.credential).copy(id = "second", reprompt = true)
        // Confirmed, then another passkey is selected.
        val changed = Fixture(backgroundScope, testMasterSessionKey(Persisted), first, second)
        changed.serve(testSignInRequest())
        runCurrent()
        val id = changed.prompt().id
        assertTrue(changed.prompts.confirmIdentity(id) { true })
        assertTrue(changed.prompts.selectPasskey(id, 1))
        assertTrue(changed.prompts.respond(id, approved = true))
        runCurrent()
        assertEquals(WindowsPasskeyFailure.DISMISSED, changed.bridge.failure(1))

        // Another passkey is selected while the user confirms their identity.
        val racing = Fixture(backgroundScope, testMasterSessionKey(Persisted), first, second)
        racing.serve(testSignInRequest())
        runCurrent()
        val racingId = racing.prompt().id
        assertFalse(racing.prompts.confirmIdentity(racingId) { racing.prompts.selectPasskey(racingId, 1) })
        assertTrue(racing.prompts.respond(racingId, approved = true))
        runCurrent()
        assertEquals(WindowsPasskeyFailure.DISMISSED, racing.bridge.failure(1))
        assertTrue(racing.bridge.verifications.isEmpty())
    }

    @Test
    fun `denying the sign-in tells Windows the user dismissed the request`() = runTest {
        val other = SigningPasskey(authenticator)
        val fixture = Fixture(
            backgroundScope,
            testMasterSessionKey(Persisted),
            testCipher(passkey.credential).copy(id = "first"),
            testCipher(other.credential).copy(id = "second"),
        )
        fixture.serve(testSignInRequest())
        runCurrent()
        assertTrue(fixture.prompts.respond(fixture.prompt().id, approved = false))
        runCurrent()
        assertNull(fixture.bridge.answer(1))
        assertEquals(WindowsPasskeyFailure.DISMISSED, fixture.bridge.failure(1))
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `a sign-in prompt expires silently at Windows' deadline`() = runTest {
        val cipher = testCipher(passkey.credential).copy(reprompt = true)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.serve(testSignInRequest())
        runCurrent()
        advanceTimeBy(TEST_EXPIRES_IN_MS.milliseconds - 1.seconds)
        runCurrent()
        assertFalse(fixture.bridge.answered(1))

        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(fixture.prompts.pending.value.isEmpty())
        assertTrue(fixture.bridge.answered(1))
        assertNull(fixture.bridge.failure(1))
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `locking the vault while the sign-in prompt is open cancels the request`() = runTest {
        val cipher = testCipher(passkey.credential).copy(reprompt = true)
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        fixture.serve(testSignInRequest())
        runCurrent()
        assertEquals(1, fixture.prompts.pending.value.size)

        fixture.sessions.state.value = MasterSession.Empty()
        runCurrent()
        assertTrue(fixture.prompts.pending.value.isEmpty())
        assertTrue(fixture.bridge.answered(1))
        assertNull(fixture.bridge.answer(1))
        // Not a dismissal: the site may retry once the vault is unlocked.
        assertNull(fixture.bridge.failure(1))
        assertEquals(0, fixture.failures)
    }

    @Test
    fun `the approval window steps aside only while Windows Hello is open`() = runTest {
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(passkey.credential))
        val verifying = mutableListOf<Boolean>()
        backgroundScope.launch { fixture.ceremony.verifying.collect { verifying += it } }
        fixture.bridge.hello = CompletableDeferred()
        fixture.serve(testSignInRequest())
        runCurrent()
        assertEquals(listOf(false, true), verifying)

        fixture.bridge.hello.complete(Unit)
        runCurrent()
        assertNotNull(fixture.bridge.answer(1))
        assertEquals(listOf(false, true, false), verifying)

        // An unlock during the request verified the user: no Hello prompt is shown.
        val unlocked = Fixture(backgroundScope, MasterSession.Empty(), testCipher(passkey.credential))
        val unlockedVerifying = mutableListOf<Boolean>()
        backgroundScope.launch { unlocked.ceremony.verifying.collect { unlockedVerifying += it } }
        unlocked.serve(testSignInRequest())
        runCurrent()
        unlocked.sessions.state.value = testMasterSessionKey(MasterSession.Key.Authenticated)
        runCurrent()
        assertNotNull(unlocked.bridge.answer(1))
        assertEquals(listOf(false), unlockedVerifying)
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
    fun `a registration stores a PRF secret and evaluates the salts Windows sends`() = runTest {
        val cipher = testCipher()
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), cipher)
        val salt = ByteArray(32) { 9 }
        fixture.serve(testRegistrationRequest().copy(prf = WindowsPasskeyPrf(eval = WindowsPasskeyPrfValues(salt))))
        runCurrent()
        assertIs<AppMode.SavePasskey>(fixture.ceremony.ui.value?.mode).onComplete(cipher)
        runCurrent()
        val data = assertIs<AddCredentialCipherRequestPasskeyData>(fixture.vault.added.single().data)
        val secret = PasskeyBase64.decode(assertNotNull(data.prfSecret))
        val answer = assertNotNull(fixture.bridge.answer(1))
        assertTrue(answer.prfEnabled)
        // Windows already derived the salt from the site's input: the output is its HMAC.
        assertContentEquals(hmacSha256(secret, salt), answer.prfResults?.first)
    }

    @Test
    fun `a sign-in evaluates the salts Windows sends for the passkey`() = runTest {
        val secret = ByteArray(32) { 4 }
        val credential = passkey.credential.copy(prfSecret = PasskeyBase64.encodeToString(secret))
        val fixture = Fixture(backgroundScope, testMasterSessionKey(Persisted), testCipher(credential))
        val own = ByteArray(32) { 2 }
        val prf = WindowsPasskeyPrf(
            eval = WindowsPasskeyPrfValues(ByteArray(32) { 1 }),
            evalByCredential = listOf(
                WindowsPasskeyPrfCredentialValues(credential.descriptor().id, WindowsPasskeyPrfValues(own)),
            ),
        )
        fixture.serve(testSignInRequest().copy(prf = prf))
        runCurrent()
        val answer = assertNotNull(fixture.bridge.answer(1))
        assertTrue(passkey.signed(answer))
        assertFalse(answer.prfEnabled)
        assertContentEquals(hmacSha256(secret, own), answer.prfResults?.first)
        assertNull(answer.prfResults?.second)
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

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }
}
