package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.IpcProviders
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.interactionPendingIntent
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.util.OpenPgpApi

@RunWith(AndroidJUnit4::class)
class OpenPgpDiscoveryTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    @Test
    fun exactlyOneKeyguardOpenPgpServiceIsPublished() {
        val services = IpcProviders
            .resolveAll(provider.context, IpcProviders.OPENPGP_SERVICE_ACTION)
            .filter {
                it.component.packageName.startsWith(IpcProviders.KEYGUARD_PACKAGE_PREFIX)
            }
        assumeTrue(
            "The OpenPGP provider is not published; enable it in Keyguard.",
            services.isNotEmpty(),
        )
        assertEquals(
            "More than one Keyguard build answers the OpenPGP action: $services",
            1,
            services.size,
        )
    }

    /**
     * The v1 interface has no output pipe and no per-request caller
     * attribution, so publishing it would give any app a way around the
     * approval flow.
     */
    @Test
    fun legacyOpenPgpServiceIsNotPublished() {
        val services = IpcProviders
            .resolveAll(provider.context, IpcProviders.LEGACY_OPENPGP_SERVICE_ACTION)
            .filter {
                it.component.packageName.startsWith(IpcProviders.KEYGUARD_PACKAGE_PREFIX)
            }
        assertTrue("Keyguard must not publish IOpenPgpService v1: $services", services.isEmpty())
    }

    @Test
    fun checkPermissionSucceedsThroughTheApprovalRetry() {
        provider
            .openPgpRunner()
            .run(OpenPgpRequestSpec(OpenPgpOperation.CHECK_PERMISSION))
            .requireOpenPgpSuccess()
    }

    /**
     * K-9 Mail issues a brand new request after an approval instead of
     * replaying the approved intent and its one-shot token, so registration has
     * to be committed when the user approves. Anything else loops forever.
     */
    @Test
    fun registrationSurvivesAFreshRequestInsteadOfAReplay() {
        val runner = provider.openPgpRunner()
        val spec = OpenPgpRequestSpec(OpenPgpOperation.CHECK_PERMISSION)
        val first = runner.runOnce(spec)
        first.legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
            ?.let { provider.robot().perform(it, ApprovalRobot.Action.APPROVE) }
        runner.runOnce(spec).requireOpenPgpSuccess()
    }
}
