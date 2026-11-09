package com.artemchep.keyguard.feature.auth.keyguard

import com.artemchep.keyguard.common.model.P2pGroupId
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetCanAddAccount
import com.artemchep.keyguard.core.store.bitwarden.P2pNetwork
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingMode
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStep
import com.artemchep.keyguard.feature.p2psync.crypto.P2pEncoding
import com.artemchep.keyguard.feature.p2psync.enroll.DisplayMode
import com.artemchep.keyguard.feature.p2psync.enroll.EnrollmentRole
import com.artemchep.keyguard.feature.p2psync.model.PairingCode
import com.artemchep.keyguard.feature.p2psync.model.SyncScope
import com.artemchep.keyguard.provider.keyguard.enroll.EnrollmentStep
import com.artemchep.keyguard.provider.keyguard.enroll.KeyguardPairingError
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_as
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val DEVICE_LABEL = "Computer"

internal val TEST_EXPIRES_AT = Instant.fromEpochMilliseconds(1_700_000_180_000L)

/** Returns the text of a pairing code; [member] tells who shows it. */
internal fun testPairingCode(member: Boolean): String = PairingCode(
    psk = ByteArray(PairingCode.KEY_SIZE) { 1 },
    pub = ByteArray(PairingCode.KEY_SIZE) { 2 },
    member = member,
    expiresAt = TEST_EXPIRES_AT.epochSeconds,
    scope = SyncScope.APP,
).encode()

// One function per scenario.
@Suppress("TooManyFunctions")
class KeyguardPairStateProducerTest {
    @Test
    fun `starts pairing at once in the picked way, as this device`() = runTest {
        val harness = PairHarness(this, mode = PairingMode.SHOW_CODE)
        val states = harness.states()
        runCurrent()

        val state = states.ok()
        assertFalse(state.premiumRequired)
        assertEquals(TEST_TRANSLATED, state.pairingAsText)
        assertTrue(Res.string.app_link_pairing_as in harness.screen.translated)
        val pairing = harness.launcher.last
        assertEquals(listOf("showCode"), pairing.calls)
        assertEquals(DEVICE_LABEL, pairing.label)
        assertEquals(PairingStep.Starting, state.step)
    }

    @Test
    fun `without premium the pairing does not start`() = runTest {
        val harness = PairHarness(this, canAddAccount = false)
        val states = harness.states()
        runCurrent()

        val state = states.ok()
        assertTrue(state.premiumRequired)
        assertNull(state.step)
        assertTrue(harness.launcher.pairings.isEmpty())
    }

    @Test
    fun `showing a code offers a new code once it expires, as this device is by then`() = runTest {
        val harness = PairHarness(this, mode = PairingMode.SHOW_CODE)
        harness.identity.value = KeyguardPairingIdentity(
            label = "Laptop",
            network = P2pNetwork(relayUrl = "wss://relay.example.com", allowLan = false),
        )
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        assertEquals("Laptop", pairing.label)
        assertEquals(
            P2pNetwork(relayUrl = "wss://relay.example.com", allowLan = false),
            pairing.network,
        )

        val code = testPairingCode(member = false)
        pairing.steps.value = EnrollmentStep.ShowCode(code = code, expiresAt = TEST_EXPIRES_AT)
        runCurrent()
        val shown = assertIs<PairingStep.ShowCode>(states.ok().step)
        assertEquals(code, shown.code)
        assertFalse(shown.expired)
        assertNull(shown.onNewCode)
        shown.onCopy()
        assertEquals(listOf(code), harness.screen.copied)

        pairing.steps.value = EnrollmentStep.Failed(KeyguardPairingError.EXPIRED)
        runCurrent()
        val expired = assertIs<PairingStep.ShowCode>(states.ok().step)
        assertTrue(expired.expired)
        assertEquals(KeyguardPairingError.EXPIRED, expired.error)
        assertNull(expired.message)

        // Renamed on the linked devices screen in the meantime.
        harness.identity.value = harness.identity.value.copy(label = "Work laptop")
        runCurrent()
        assertNotNull(expired.onNewCode).invoke()
        runCurrent()
        assertEquals(2, harness.launcher.pairings.size)
        assertTrue("cancel" in pairing.calls)
        assertEquals(listOf("showCode"), harness.launcher.last.calls)
        assertEquals("Work laptop", harness.launcher.last.label)
        assertEquals(PairingStep.Starting, states.ok().step)
    }

    @Test
    fun `scanning hands over only pairing codes`() = runTest {
        val harness = PairHarness(this)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        assertEquals(listOf("scanCode"), pairing.calls)
        pairing.steps.value = EnrollmentStep.Scanning
        runCurrent()

        val empty = assertIs<PairingStep.Scan>(states.ok().step)
        assertNull(empty.onSubmit)
        assertNull(empty.code.error)

        // A malformed code would end the pairing, so it is not handed over.
        empty.code.onChange!!("hello")
        runCurrent()
        val invalid = assertIs<PairingStep.Scan>(states.ok().step)
        assertNull(invalid.onSubmit)
        assertNotNull(invalid.code.error)
        invalid.onCode("not a code either")
        runCurrent()
        assertEquals(listOf("scanCode"), pairing.calls)

        val code = testPairingCode(member = true)
        assertIs<PairingStep.Scan>(states.ok().step).onCode(" $code ")
        runCurrent()
        assertEquals(listOf("scanCode", "submitCode:$code"), pairing.calls)
        val scanned = assertIs<PairingStep.Scan>(states.ok().step)
        assertEquals(code, scanned.code.text)
        assertNull(scanned.code.error)

        assertNotNull(scanned.onSubmit).invoke()
        assertEquals("submitCode:$code", pairing.calls.last())
    }

    @Test
    fun `a code of another version is handed over, so the pairing asks for an update`() = runTest {
        val harness = PairHarness(this)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        pairing.steps.value = EnrollmentStep.Scanning
        runCurrent()

        // A code of a newer version: its layout after the version is unknown.
        val newer = PairingCode.PREFIX + P2pEncoding.base32UpperCase(byteArrayOf(PairingCode.MAGIC.toByte(), 2, 7))
        assertIs<PairingStep.Scan>(states.ok().step).onCode(newer)
        runCurrent()
        assertEquals("submitCode:$newer", pairing.calls.last())

        pairing.steps.value = EnrollmentStep.Failed(KeyguardPairingError.UPDATE_REQUIRED)
        runCurrent()
        val failed = assertIs<PairingStep.Failed>(states.ok().step)
        assertEquals(KeyguardPairingError.UPDATE_REQUIRED, failed.error)
    }

    @Test
    fun `the scanner shows the digits, and its user confirms that they match`() = runTest {
        val harness = PairHarness(this)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        pairing.steps.value = EnrollmentStep.Connecting
        runCurrent()
        val verifying = EnrollmentStep.Verify(
            sas = "123456",
            peerLabel = "Pixel",
            role = EnrollmentRole.JOINER,
            display = DisplayMode.SCANNER,
        )
        pairing.steps.value = verifying
        runCurrent()
        val verify = assertIs<PairingStep.Verify>(states.ok().step)
        assertEquals("123456", verify.sas)
        assertFalse(verify.confirmed)
        assertFalse(verify.entered)
        assertNotNull(verify.onApprove).invoke()
        assertEquals("approve", pairing.calls.last())

        // Confirmed: it waits for the other device, and may only be cancelled.
        pairing.steps.value = verifying.copy(confirmed = true)
        runCurrent()
        val waiting = assertIs<PairingStep.Verify>(states.ok().step)
        assertTrue(waiting.confirmed)
        assertNull(waiting.onApprove)
        waiting.onReject()
        assertEquals("cancel", pairing.calls.last())
    }

    @Test
    fun `the displayer types the digits of the other device, again after a wrong entry`() = runTest {
        val harness = PairHarness(this, mode = PairingMode.SHOW_CODE)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        val verifying = EnrollmentStep.Verify(
            sas = "123456",
            peerLabel = "Pixel",
            role = EnrollmentRole.JOINER,
            display = DisplayMode.DISPLAYER,
        )
        pairing.steps.value = verifying
        runCurrent()
        val empty = assertIs<PairingStep.EnterSas>(states.ok().step)
        assertEquals("Pixel", empty.peerLabel)
        assertEquals("", empty.digits.text)
        assertNull(empty.onSubmit)
        assertNull(empty.digits.error)

        // Five digits are not checked yet.
        assertNotNull(empty.digits.onChange).invoke("65432")
        runCurrent()
        val partial = assertIs<PairingStep.EnterSas>(states.ok().step)
        assertNull(partial.onSubmit)
        assertEquals(listOf("showCode"), pairing.calls)

        // Six are, at once.
        assertNotNull(partial.digits.onChange).invoke("654321")
        runCurrent()
        assertEquals("enterDigits:654321", pairing.calls.last())
        pairing.steps.value = verifying.copy(misses = 1)
        runCurrent()
        val wrong = assertIs<PairingStep.EnterSas>(states.ok().step)
        assertEquals(TEST_TRANSLATED, wrong.digits.error)
        // The same digits again count again.
        assertNotNull(wrong.onSubmit).invoke()
        assertEquals("enterDigits:654321", pairing.calls.last())
        assertEquals(3, pairing.calls.size)

        // Typing again clears the error, and checks the new digits.
        assertNotNull(wrong.digits.onChange).invoke("12345")
        runCurrent()
        assertNull(assertIs<PairingStep.EnterSas>(states.ok().step).digits.error)
        assertNotNull(assertIs<PairingStep.EnterSas>(states.ok().step).digits.onChange).invoke("123456")
        runCurrent()
        assertEquals("enterDigits:123456", pairing.calls.last())

        pairing.steps.value = verifying.copy(misses = 2, confirmed = true)
        runCurrent()
        val matched = assertIs<PairingStep.Verify>(states.ok().step)
        assertEquals("123456", matched.sas)
        assertTrue(matched.confirmed)
        assertTrue(matched.entered)
        assertNull(matched.onApprove)
    }

    @Test
    fun `the displayer may cancel instead of typing the digits`() = runTest {
        val harness = PairHarness(this, mode = PairingMode.SHOW_CODE)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        pairing.steps.value = EnrollmentStep.Verify(
            sas = "123456",
            peerLabel = "Pixel",
            role = EnrollmentRole.JOINER,
            display = DisplayMode.DISPLAYER,
        )
        runCurrent()
        assertIs<PairingStep.EnterSas>(states.ok().step).onCancel()
        assertEquals("cancel", pairing.calls.last())
    }

    @Test
    fun `the scanner says the digits differ`() = runTest {
        val harness = PairHarness(this)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        pairing.steps.value = EnrollmentStep.Verify(
            sas = "123456",
            peerLabel = "Pixel",
            role = EnrollmentRole.JOINER,
            display = DisplayMode.SCANNER,
        )
        runCurrent()
        val verify = assertIs<PairingStep.Verify>(states.ok().step)

        verify.onReject()
        assertEquals("reject", pairing.calls.last())
        // The coordinator reports a rejection of this device as cancelled.
        pairing.steps.value = EnrollmentStep.Failed(KeyguardPairingError.CANCELLED)
        runCurrent()
        val failed = assertIs<PairingStep.Failed>(states.ok().step)
        assertEquals(KeyguardPairingError.MISMATCH, failed.error)

        assertNotNull(failed.onRetry).invoke()
        runCurrent()
        assertEquals(2, harness.launcher.pairings.size)
        assertEquals(listOf("scanCode"), harness.launcher.last.calls)
    }

    @Test
    fun `a failure says what went wrong`() = runTest {
        val harness = PairHarness(this)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        pairing.steps.value = EnrollmentStep.Failed(
            reason = KeyguardPairingError.FAILED,
            cause = IllegalStateException("The disk is full."),
        )
        runCurrent()

        val failed = assertIs<PairingStep.Failed>(states.ok().step)
        assertEquals(KeyguardPairingError.FAILED, failed.error)
        assertNotNull(failed.message)
    }

    @Test
    fun `a rejection by the other device is reported as such`() = runTest {
        val harness = PairHarness(this)
        val states = harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        pairing.steps.value = EnrollmentStep.Verify(
            sas = "123456",
            peerLabel = "Pixel",
            role = EnrollmentRole.JOINER,
            display = DisplayMode.SCANNER,
        )
        runCurrent()
        pairing.steps.value = EnrollmentStep.Failed(KeyguardPairingError.REJECTED)
        runCurrent()

        val failed = assertIs<PairingStep.Failed>(states.ok().step)
        assertEquals(KeyguardPairingError.REJECTED, failed.error)
        assertNull(failed.message)
    }

    @Test
    fun `reports success once the link is on this device`() = runTest {
        val harness = PairHarness(this, mode = PairingMode.SHOW_CODE)
        val states = harness.states()
        runCurrent()
        val successes = mutableListOf<Unit>()
        backgroundScope.launch {
            states.ok().sideEffects.onSuccessFlow.collect { successes += it }
        }

        val pairing = harness.launcher.last
        pairing.steps.value = EnrollmentStep.Transferring
        runCurrent()
        assertEquals(PairingStep.Transferring, states.ok().step)

        pairing.steps.value = EnrollmentStep.Done(P2pGroupId.AppLink)
        runCurrent()
        assertEquals(1, successes.size)
        assertTrue(harness.screen.errors.isEmpty(), harness.screen.errors.toString())
    }

    @Test
    fun `once paired, pairs with one more device in the same way`() = runTest {
        val harness = PairHarness(this, mode = PairingMode.SCAN_CODE)
        val states = harness.states()
        runCurrent()
        assertNull(states.ok().onAddAnother)

        harness.launcher.last.steps.value = EnrollmentStep.Done(P2pGroupId.AppLink)
        runCurrent()
        val onAddAnother = assertNotNull(states.ok().onAddAnother)

        harness.identity.value = harness.identity.value.copy(label = "Work laptop")
        runCurrent()
        onAddAnother()
        runCurrent()
        assertEquals(2, harness.launcher.pairings.size)
        assertEquals(listOf("scanCode"), harness.launcher.last.calls)
        assertEquals("Work laptop", harness.launcher.last.label)
        assertEquals(PairingStep.Starting, states.ok().step)
        assertNull(states.ok().onAddAnother)
    }

    @Test
    fun `closing the screen cancels the pairing`() = runTest {
        val harness = PairHarness(this)
        harness.states()
        runCurrent()

        val pairing = harness.launcher.last
        harness.screen.scope.coroutineContext[Job]!!.cancel()
        runCurrent()
        assertEquals("cancel", pairing.calls.last())
    }
}

private fun StateFlow<Loadable<KeyguardPairState>?>.ok() =
    assertNotNull(value?.getOrNull())

private class PairHarness(
    testScope: TestScope,
    canAddAccount: Boolean = true,
    private val mode: PairingMode = PairingMode.SCAN_CODE,
) {
    val screen = KeyguardProducerTestScope(testScope)

    val launcher = FakePairingLauncher()

    val identity = MutableStateFlow(
        KeyguardPairingIdentity(
            label = DEVICE_LABEL,
            network = P2pNetwork(),
        ),
    )

    private val getCanAddAccount = object : GetCanAddAccount {
        override fun invoke() = MutableStateFlow(canAddAccount)
    }

    fun states(): StateFlow<Loadable<KeyguardPairState>?> = screen.create()
        .keyguardPairStateProducer(
            mode = mode,
            pairingLauncher = launcher,
            password = { "password" },
            getIdentity = { identity },
            getCanPair = getCanAddAccount,
        )
        .stateIn(screen.scope, SharingStarted.Eagerly, null)
}
