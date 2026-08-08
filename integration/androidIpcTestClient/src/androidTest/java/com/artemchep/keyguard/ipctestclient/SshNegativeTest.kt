package com.artemchep.keyguard.ipctestclient

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.SshOperation
import com.artemchep.keyguard.ipctestclient.ipc.SshRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.interactionPendingIntent
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.assertSshError
import com.artemchep.keyguard.ipctestclient.support.failWithExchangeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.SshAuthenticationApiError

@RunWith(AndroidJUnit4::class)
class SshNegativeTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    /** Exactly one API version is supported, so both neighbours must fail. */
    @Test
    fun anyApiVersionOtherThanOneIsRejected() {
        listOf(SshAuthenticationApi.API_VERSION - 1, SshAuthenticationApi.API_VERSION + 1, null)
            .forEach { version ->
                send(SshRequestSpec(SshOperation.SELECT_KEY, apiVersion = version))
                    .assertSshError(SshAuthenticationApiError.INCOMPATIBLE_API_VERSIONS)
            }
    }

    @Test
    fun unknownAndMissingActionsAreRejected() {
        listOf(
            SshRequestSpec(SshOperation.UNKNOWN),
            SshRequestSpec(SshOperation.SELECT_KEY, omitAction = true),
        ).forEach {
            send(it).assertSshError(SshAuthenticationApiError.UNKNOWN_ACTION)
        }
    }

    @Test
    fun aMissingOrUnusableKeyIdIsRejected() {
        listOf(
            null,
            "   ",
            "k".repeat(SshOperation.MAX_KEY_ID_LENGTH + 1),
        ).forEach { keyId ->
            send(
                SshRequestSpec(
                    operation = SshOperation.GET_PUBLIC_KEY,
                    keyId = keyId,
                    useLibraryBuilders = false,
                ),
            ).assertSshError(SshAuthenticationApiError.NO_KEY_ID)
        }
    }

    /**
     * The challenge and hash are validated before the caller is admitted, so
     * neither case needs a real key or an approval.
     */
    @Test
    fun anEmptyChallengeIsRejected() {
        send(
            SshRequestSpec(
                operation = SshOperation.SIGN,
                keyId = ANY_KEY_ID,
                challenge = ByteArray(0),
            ),
        ).assertSshError(
            errorCode = SshAuthenticationApiError.GENERIC_ERROR,
            messageContains = "A challenge of at most",
        )
    }

    @Test
    fun unknownHashAlgorithmsAreRejected() {
        SshOperation.UNKNOWN_HASH_ALGORITHMS.forEach { hash ->
            send(
                SshRequestSpec(
                    operation = SshOperation.SIGN,
                    keyId = ANY_KEY_ID,
                    challenge = CHALLENGE,
                    hashAlgorithm = hash,
                ),
            ).assertSshError(
                errorCode = SshAuthenticationApiError.INVALID_HASH_ALGORITHM,
                messageContains = "known SSH Authentication API hash algorithm",
            )
        }
    }

    @Test
    fun aMissingHashAlgorithmIsRejected() {
        send(
            SshRequestSpec(
                operation = SshOperation.SIGN,
                keyId = ANY_KEY_ID,
                challenge = CHALLENGE,
                omitHashAlgorithm = true,
                useLibraryBuilders = false,
            ),
        ).assertSshError(
            errorCode = SshAuthenticationApiError.INVALID_HASH_ALGORITHM,
            messageContains = "known SSH Authentication API hash algorithm",
        )
    }

    @Test
    fun anUnknownKeyIdIsRejected() {
        state.sshKeyId()
        send(
            SshRequestSpec(
                operation = SshOperation.GET_SSH_PUBLIC_KEY,
                keyId = "there-is-no-such-key",
            ),
        ).assertSshError(SshAuthenticationApiError.NO_SUCH_KEY)
    }

    @Test
    fun denyingAKeySelectionReturnsNoRetryIntent() {
        val pendingIntent = provider
            .sshRunner()
            .runOnce(SshRequestSpec(SshOperation.SELECT_KEY))
            .legs
            .first()
            .interactionPendingIntent(SshAuthenticationApi.EXTRA_PENDING_INTENT)
            ?: failWithExchangeLog("The provider did not ask for approval")
        val outcome = provider.robot().perform(pendingIntent, ApprovalRobot.Action.DENY)
        assertEquals(Activity.RESULT_CANCELED, outcome.resultCode)
        assertFalse("A denied approval handed out a token", outcome.hasAuthorizationToken)
    }

    private fun send(spec: SshRequestSpec) = provider.sshRunner().runOnce(spec)

    private companion object {
        const val ANY_KEY_ID = "validated-before-admission"
        val CHALLENGE = "challenge".encodeToByteArray()
    }
}
