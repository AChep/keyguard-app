package com.artemchep.keyguard.ipctestclient

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.SshOperation
import com.artemchep.keyguard.ipctestclient.ipc.SshRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.interactionPendingIntent
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.failWithExchangeLog
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import com.artemchep.keyguard.ipctestclient.support.requireSshSuccess
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.ssh.authentication.SshAuthenticationApi

/**
 * Approval continuation state belongs to the provider process, not to either
 * bound service instance. The other protocol remains bound in every test so
 * service destruction is tested independently from fail-closed process death.
 */
@RunWith(AndroidJUnit4::class)
class ProviderApprovalLifecycleTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun openPgpPendingRequestSurvivesServiceDestruction() {
        val pendingIntent = openPgpPendingIntent()
        provider.sshService()

        provider.disconnectOpenPgp()
        provider.awaitServiceStopped(provider.openPgpComponent())

        val retry = approve(pendingIntent)
        provider
            .openPgpRunner()
            .runIntent(OPENPGP_REQUEST, retry)
            .requireOpenPgpSuccess()
    }

    @Test
    fun openPgpGrantSurvivesServiceDestruction() {
        val retry = approve(openPgpPendingIntent())
        provider.sshService()

        provider.disconnectOpenPgp()
        provider.awaitServiceStopped(provider.openPgpComponent())

        provider
            .openPgpRunner()
            .runIntent(OPENPGP_REQUEST, retry)
            .requireOpenPgpSuccess()
    }

    @Test
    fun sshPendingRequestSurvivesServiceDestruction() {
        val pendingIntent = sshPendingIntent()
        provider.openPgpService()

        provider.disconnectSsh()
        provider.awaitServiceStopped(provider.sshComponent())

        provider
            .sshRunner()
            .runIntent(approve(pendingIntent))
            .requireSshSuccess()
    }

    @Test
    fun sshGrantSurvivesServiceDestruction() {
        val retry = approve(sshPendingIntent())
        provider.openPgpService()

        provider.disconnectSsh()
        provider.awaitServiceStopped(provider.sshComponent())

        provider.sshRunner().runIntent(retry).requireSshSuccess()
    }

    private fun openPgpPendingIntent(): PendingIntent {
        state.ensureRegistered()
        return provider
            .openPgpRunner()
            .runOnce(OPENPGP_REQUEST)
            .legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
            ?: failWithExchangeLog("The OpenPGP provider did not ask for approval")
    }

    private fun sshPendingIntent(): PendingIntent {
        state.ensureRegistered()
        return provider
            .sshRunner()
            .runOnce(SSH_REQUEST)
            .legs
            .first()
            .interactionPendingIntent(SshAuthenticationApi.EXTRA_PENDING_INTENT)
            ?: failWithExchangeLog("The SSH provider did not ask for approval")
    }

    private fun approve(pendingIntent: PendingIntent): Intent = provider
        .robot()
        .perform(pendingIntent, ApprovalRobot.Action.APPROVE)
        .retryIntent
        ?: failWithExchangeLog("The approval returned no retry intent")

    private companion object {
        val OPENPGP_REQUEST = OpenPgpRequestSpec(OpenPgpOperation.GET_SIGN_KEY_ID)
        val SSH_REQUEST = SshRequestSpec(SshOperation.SELECT_KEY)
    }
}
