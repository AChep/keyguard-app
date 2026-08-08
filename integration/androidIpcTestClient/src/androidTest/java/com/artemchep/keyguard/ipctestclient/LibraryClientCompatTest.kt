package com.artemchep.keyguard.ipctestclient

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.IpcProviders
import com.artemchep.keyguard.ipctestclient.ipc.pendingIntentExtra
import com.artemchep.keyguard.ipctestclient.ipc.orEmptyBytes
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.failWithExchangeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.request.SigningRequest
import org.openintents.ssh.authentication.response.SigningResponse
import org.openintents.ssh.authentication.util.SshAuthenticationApiUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The vanilla third-party client path.
 *
 * The rest of the suite drives the binder directly, because
 * [OpenPgpApi.executeApi] and [SshAuthenticationApi.executeApi] overwrite the
 * API version extra with their own compiled-in constants and so cannot express
 * a version probe. That makes the wrappers the one thing the suite would
 * otherwise never exercise - and they are what a real client uses, output pipes
 * and transfer threads included.
 */
@RunWith(AndroidJUnit4::class)
class LibraryClientCompatTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun theOfficialOpenPgpWrapperSignsThroughItsOwnPipes() {
        state.signKeyId()
        val api = OpenPgpApi(provider.context, provider.openPgpService())
        var result = api.executeApi(
            Intent(OpenPgpApi.ACTION_DETACHED_SIGN),
            ByteArrayInputStream(PAYLOAD),
            ByteArrayOutputStream(),
        )
        var approvals = 0
        while (approvals < MAX_APPROVALS && result.needsInteraction()) {
            approvals++
            result = api.executeApi(
                approve(result, OpenPgpApi.RESULT_INTENT),
                ByteArrayInputStream(PAYLOAD),
                ByteArrayOutputStream(),
            )
        }
        assertEquals(
            OpenPgpApi.RESULT_CODE_SUCCESS,
            result.getIntExtra(OpenPgpApi.RESULT_CODE, Int.MIN_VALUE),
        )
        assertTrue(
            "The wrapper produced no detached signature",
            result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE)
                .orEmptyBytes()
                .isNotEmpty(),
        )
    }

    /** Provider discovery the way the SSH library does it, by package name. */
    @Test
    fun theOfficialSshUtilitiesDiscoverKeyguard() {
        provider.keyguardPackage()
        val packages = SshAuthenticationApiUtils
            .getAuthenticationProviderPackageNames(provider.context)
        assertTrue(
            "The SSH library did not discover Keyguard: $packages",
            packages.any { it.startsWith(IpcProviders.KEYGUARD_PACKAGE_PREFIX) },
        )
    }

    @Test
    fun theOfficialSshWrapperSignsThroughItsRequestClasses() {
        val api = SshAuthenticationApi(provider.context, provider.sshService())
        val request = SigningRequest(
            CHALLENGE,
            state.sshKeyId(),
            SshAuthenticationApi.SHA256,
        ).toIntent()
        var result = api.executeApi(request)
        var approvals = 0
        while (approvals < MAX_APPROVALS && result.needsSshInteraction()) {
            approvals++
            result = api.executeApi(approve(result, SshAuthenticationApi.EXTRA_PENDING_INTENT))
        }
        val response = SigningResponse(result)
        assertEquals(SigningResponse.RESULT_CODE_SUCCESS, response.resultCode)
        assertTrue("The wrapper produced no signature", response.signature.isNotEmpty())
    }

    private fun approve(result: Intent, intentExtra: String): Intent = provider
        .robot()
        .perform(
            result.pendingIntentExtra(intentExtra)
                ?: failWithExchangeLog("No approval intent in $intentExtra"),
            ApprovalRobot.Action.APPROVE,
        )
        .retryIntent
        ?: failWithExchangeLog("The approval returned no retry intent")

    private fun Intent.needsInteraction(): Boolean =
        getIntExtra(OpenPgpApi.RESULT_CODE, Int.MIN_VALUE) ==
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED

    private fun Intent.needsSshInteraction(): Boolean =
        getIntExtra(SshAuthenticationApi.EXTRA_RESULT_CODE, Int.MIN_VALUE) ==
            SshAuthenticationApi.RESULT_CODE_USER_INTERACTION_REQUIRED

    private companion object {
        const val MAX_APPROVALS = 2
        val PAYLOAD = "vanilla client".encodeToByteArray()
        val CHALLENGE = "vanilla client challenge".encodeToByteArray()
    }
}
