package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.IpcExchange
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.interactionPendingIntent
import com.artemchep.keyguard.ipctestclient.ipc.openPgpAutocryptStatusName
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.util.OpenPgpApi

/**
 * Autocrypt status, which Keyguard answers deliberately pessimistically.
 *
 * The action always succeeds - it is a hint, not an operation - and never
 * claims more than `DISCOURAGE`, because Keyguard has no notion of a peer
 * having advertised a key, and never reports a key as confirmed.
 */
@RunWith(AndroidJUnit4::class)
class OpenPgpAutocryptTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun anUnknownRecipientIsUnavailable() {
        val result = query(UNKNOWN_RECIPIENT).requireOpenPgpSuccess()
        assertEquals(
            openPgpAutocryptStatusName(OpenPgpApi.AUTOCRYPT_STATUS_UNAVAILABLE),
            openPgpAutocryptStatusName(status(result)),
        )
        assertFalse(result.getBooleanExtra(OpenPgpApi.RESULT_KEYS_CONFIRMED, true))
    }

    @Test
    fun aKnownRecipientIsDiscouraged() {
        val email = state.signingEmail()
        val result = query(email).requireOpenPgpSuccess()
        assertEquals(
            openPgpAutocryptStatusName(OpenPgpApi.AUTOCRYPT_STATUS_DISCOURAGE),
            openPgpAutocryptStatusName(status(result)),
        )
    }

    /** Keyguard never certifies a peer, so these two values must never appear. */
    @Test
    fun availableAndMutualAreNeverReported() {
        listOf(UNKNOWN_RECIPIENT, state.signingEmail()).forEach { recipient ->
            val result = query(recipient).requireOpenPgpSuccess()
            assertTrue(
                "Autocrypt reported ${openPgpAutocryptStatusName(status(result))}",
                status(result) in ALLOWED_STATUSES,
            )
            assertFalse(result.getBooleanExtra(OpenPgpApi.RESULT_KEYS_CONFIRMED, true))
        }
    }

    /**
     * An ambiguous recipient is offered a disambiguation intent while the call
     * still succeeds, and K-9 does not replay that intent - it issues a fresh
     * request - so this is the one action where a tokenless retry has to work.
     *
     * Needs two keys sharing a user id e-mail; skipped when the vault has none.
     */
    @Test
    fun anAmbiguousRecipientCanBeResolvedWithoutReplayingTheToken() {
        val email = state.signingEmail()
        val exchange = query(email)
        val result = exchange.requireOpenPgpSuccess()
        val pendingIntent = exchange
            .legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
        assumeTrue(
            "The vault has no two keys sharing the e-mail $email.",
            result.hasExtra(OpenPgpApi.RESULT_INTENT) && pendingIntent != null,
        )
        provider.robot().perform(pendingIntent!!, ApprovalRobot.Action.APPROVE)
        query(email).requireOpenPgpSuccess()
    }

    private fun query(recipient: String): IpcExchange {
        state.ensureRegistered()
        return provider.openPgpRunner().runOnce(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.QUERY_AUTOCRYPT_STATUS,
                userIds = listOf(recipient),
            ),
        )
    }

    private fun status(result: android.content.Intent) = result.getIntExtra(
        OpenPgpApi.RESULT_AUTOCRYPT_STATUS,
        IpcExchange.UNKNOWN_RESULT_CODE,
    )

    private companion object {
        const val UNKNOWN_RECIPIENT = "missing-recipient@example.invalid"
        val ALLOWED_STATUSES = setOf(
            OpenPgpApi.AUTOCRYPT_STATUS_UNAVAILABLE,
            OpenPgpApi.AUTOCRYPT_STATUS_DISCOURAGE,
        )
    }
}
