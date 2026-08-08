package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.openPgpResultCodeName
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.assertOpenPgpError
import com.artemchep.keyguard.ipctestclient.support.requireResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi

/**
 * What happens when a request names a recipient the vault cannot resolve.
 *
 * `NO_USER_IDS` and `OPPORTUNISTIC_MISSING_KEYS` are deliberately absent: both
 * need an approved selection that contains no encryption-capable key, and the
 * approval dialog only offers encryption-capable keys for these actions, so a
 * client cannot steer the provider into either state. What a client can observe
 * is covered here.
 */
@RunWith(AndroidJUnit4::class)
class OpenPgpRecipientErrorTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    /**
     * An unresolvable recipient must never quietly encrypt to something else;
     * the user has to be asked which key was meant.
     */
    @Test
    fun encryptingToAnUnknownRecipientAsksTheUser() {
        state.ensureRegistered()
        val exchange = provider.openPgpRunner().runOnce(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.ENCRYPT,
                payload = PAYLOAD,
                userIds = listOf(UNKNOWN_RECIPIENT),
            ),
        )
        val code = exchange
            .requireResult()
            .getIntExtra(OpenPgpApi.RESULT_CODE, Int.MIN_VALUE)
        assertEquals(
            openPgpResultCodeName(OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED),
            openPgpResultCodeName(code),
        )
    }

    /**
     * Approving a key does not make a bogus `key_ids` entry go away: the
     * selection is re-checked against the request after the approval.
     */
    @Test
    fun encryptingToAnUnknownKeyIdIsRejectedEvenAfterApproval() {
        state.ensureRegistered()
        provider
            .openPgpRunner(ApprovalRobot.Action.APPROVE_ALL_CANDIDATES)
            .run(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.ENCRYPT,
                    payload = PAYLOAD,
                    keyIds = listOf(UNKNOWN_KEY_ID),
                ),
            )
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "missing or ambiguous",
            )
    }

    private companion object {
        const val UNKNOWN_RECIPIENT = "missing-recipient@example.invalid"
        const val UNKNOWN_KEY_ID = 0x0123_4567_89AB_CDEFL
        val PAYLOAD = "no recipient for this".encodeToByteArray()
    }
}
