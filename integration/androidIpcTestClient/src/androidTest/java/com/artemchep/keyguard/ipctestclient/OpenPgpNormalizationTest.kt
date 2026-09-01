package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.ONE_SHOT_AUTHORIZATION_EXTRA
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.dumpExtras
import com.artemchep.keyguard.ipctestclient.ipc.interactionPendingIntent
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.failWithExchangeLog
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.util.OpenPgpApi

/**
 * How the provider rewrites a request before binding a grant to it.
 *
 * The retry intent is the canonical form of the request. Its extras are what the
 * approval digest is computed over, which is why a client has to replay it
 * verbatim rather than rebuild its own equivalent.
 */
@RunWith(AndroidJUnit4::class)
class OpenPgpNormalizationTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun theRetryIntentSpellsOutTheBooleanExtrasTheRequestOmitted() {
        val retry = approvedRetryIntent(OpenPgpRequestSpec(OpenPgpOperation.GET_SIGN_KEY_ID))
        val dump = retry.dumpExtras()
        listOf(
            OpenPgpApi.EXTRA_API_VERSION,
            OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR,
            OpenPgpApi.EXTRA_ENABLE_COMPRESSION,
            OpenPgpApi.EXTRA_OPPORTUNISTIC_ENCRYPTION,
        ).forEach {
            assertTrue("The retry intent has no $it\n$dump", retry.hasExtra(it))
        }
        assertTrue(
            "The retry intent carries no one-shot token\n$dump",
            retry.hasExtra(ONE_SHOT_AUTHORIZATION_EXTRA),
        )
        assertEquals(OpenPgpApi.ACTION_GET_SIGN_KEY_ID, retry.action)
    }

    /**
     * `key_ids_selected` is folded into `key_ids`; the old name does not survive.
     *
     * The unresolvable id is what forces the prompt: a request whose key ids all
     * resolve is selected automatically and never produces a retry intent.
     */
    @Test
    fun selectedKeyIdsAreMergedIntoKeyIds() {
        val keyIds = state.encryptionKeyIds() + UNKNOWN_KEY_ID
        val retry = approvedRetryIntent(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.GET_KEY_IDS,
                selectedKeyIds = keyIds,
            ),
            ApprovalRobot.Action.APPROVE_ALL_CANDIDATES,
        )
        val merged = retry.getLongArrayExtra(OpenPgpApi.EXTRA_KEY_IDS)?.toList().orEmpty()
        assertTrue(
            "key_ids $merged does not contain the selected ids $keyIds",
            merged.containsAll(keyIds),
        )
        assertFalse(
            "key_ids_selected survived normalization",
            retry.hasExtra(OpenPgpApi.EXTRA_KEY_IDS_SELECTED),
        )
    }

    /** The singular `user_id` is folded into the plural `user_ids`. */
    @Test
    fun aSingleUserIdIsCanonicalizedToTheArrayForm() {
        val retry = approvedRetryIntent(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.GET_SIGN_KEY_ID,
                singleUserId = RECIPIENT,
            ),
        )
        assertEquals(
            listOf(RECIPIENT),
            retry.getStringArrayExtra(OpenPgpApi.EXTRA_USER_IDS).orEmpty().toList(),
        )
        assertFalse("user_id survived normalization", retry.hasExtra(OpenPgpApi.EXTRA_USER_ID))
    }

    @Test
    fun senderAddressIsCanonicalizedAndPreservedInTheRetryIntent() {
        val retry = approvedRetryIntent(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.GET_SIGN_KEY_ID,
                senderAddress = " Sender@Example.com ",
            ),
        )
        assertEquals(
            "sender@example.com",
            retry.getStringExtra(OpenPgpApi.EXTRA_SENDER_ADDRESS),
        )
    }

    private fun approvedRetryIntent(
        spec: OpenPgpRequestSpec,
        action: ApprovalRobot.Action = ApprovalRobot.Action.APPROVE,
    ): android.content.Intent {
        state.ensureRegistered()
        val runner = provider.openPgpRunner()
        val pendingIntent = runner
            .runOnce(spec)
            .legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
            ?: failWithExchangeLog("The provider did not ask for approval")
        val retry = provider
            .robot()
            .perform(pendingIntent, action)
            .retryIntent
            ?: failWithExchangeLog("The approval returned no retry intent")
        // The canonical form has to be a request the provider still accepts.
        runner.runIntent(spec, android.content.Intent(retry)).requireOpenPgpSuccess()
        return retry
    }

    private companion object {
        const val RECIPIENT = "missing-recipient@example.invalid"
        const val UNKNOWN_KEY_ID = 0x0123_4567_89AB_CDEFL
    }
}
