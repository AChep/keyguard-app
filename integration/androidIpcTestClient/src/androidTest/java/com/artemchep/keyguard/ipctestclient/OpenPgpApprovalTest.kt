package com.artemchep.keyguard.ipctestclient

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.ONE_SHOT_AUTHORIZATION_EXTRA
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.interactionPendingIntent
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.assertOpenPgpError
import com.artemchep.keyguard.ipctestclient.support.failWithExchangeLog
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi

/**
 * The one-shot authorization token.
 *
 * The grant is bound to a digest over the request's canonicalized extras and is
 * consumed on first use, so it is not a capability a client can keep, widen or
 * point at a different action.
 */
@RunWith(AndroidJUnit4::class)
class OpenPgpApprovalTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun denyingReturnsNoRetryIntentAndNoToken() {
        state.ensureRegistered()
        val runner = provider.openPgpRunner()
        val pendingIntent = runner
            .runOnce(PROMPTING_REQUEST)
            .legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
            ?: failWithExchangeLog("The provider did not ask for approval")
        val outcome = provider.robot().perform(pendingIntent, ApprovalRobot.Action.DENY)
        assertEquals(Activity.RESULT_CANCELED, outcome.resultCode)
        assertFalse("A denied approval handed out a token", outcome.hasAuthorizationToken)
    }

    @Test
    fun editingAnyExtraOfTheRetryIntentInvalidatesTheToken() {
        val retry = approvedRetryIntent()
        retry.putExtra(OpenPgpApi.EXTRA_ORIGINAL_FILENAME, "not-in-the-digest.txt")
        provider
            .openPgpRunner()
            .runIntent(PROMPTING_REQUEST, retry)
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "one-shot authorization is invalid or expired",
            )
    }

    @Test
    fun theRetryIntentCannotBeReplayedTwice() {
        val runner = provider.openPgpRunner()
        val retry = approvedRetryIntent()
        runner.runIntent(PROMPTING_REQUEST, retry).requireOpenPgpSuccess()
        runner
            .runIntent(PROMPTING_REQUEST, retry)
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "one-shot authorization is invalid or expired",
            )
    }

    @Test
    fun theTokenCannotBeMovedToAnotherAction() {
        val token = approvedRetryIntent().getStringExtra(ONE_SHOT_AUTHORIZATION_EXTRA)
        assertTrue("The approval returned no token", !token.isNullOrBlank())
        val other = OpenPgpRequestSpec(OpenPgpOperation.GET_KEY_IDS)
        val request = other.toIntent().putExtra(ONE_SHOT_AUTHORIZATION_EXTRA, token)
        provider
            .openPgpRunner()
            .runIntent(other, request)
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "one-shot authorization is invalid or expired",
            )
    }

    /**
     * Drives one approval to completion and hands back the retry intent without
     * sending it, so a test can tamper with it first.
     */
    private fun approvedRetryIntent(): android.content.Intent {
        state.ensureRegistered()
        val pendingIntent = provider
            .openPgpRunner()
            .runOnce(PROMPTING_REQUEST)
            .legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
            ?: failWithExchangeLog("The provider did not ask for approval")
        val outcome = provider.robot().perform(pendingIntent, ApprovalRobot.Action.APPROVE)
        return outcome.retryIntent
            ?: failWithExchangeLog("The approval returned no retry intent")
    }

    private companion object {
        /**
         * Key selection with nothing preselected always needs approval: grants
         * are single-use, so no earlier run can satisfy this one.
         */
        val PROMPTING_REQUEST = OpenPgpRequestSpec(OpenPgpOperation.GET_SIGN_KEY_ID)
    }
}
