package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.PgpArmor
import com.artemchep.keyguard.ipctestclient.ipc.interactionPendingIntent
import com.artemchep.keyguard.ipctestclient.ipc.orEmptyBytes
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.LocalCrypto
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.assertOpenPgpError
import com.artemchep.keyguard.ipctestclient.support.failWithExchangeLog
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpUserInteraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi

@RunWith(AndroidJUnit4::class)
class OpenPgpKeyQueryTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun theLegacySignKeyIdActionResolvesTheSameKey() {
        val expected = state.signKeyId()
        val legacy = provider
            .openPgpRunner()
            .run(OpenPgpRequestSpec(OpenPgpOperation.GET_SIGN_KEY_ID_LEGACY))
            .requireOpenPgpSuccess()
        assertEquals(expected, legacy.getLongExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, 0L))
    }

    @Test
    fun keyCreationTimeIsEpochMillisecondsInThePast() {
        state.signKeyId()
        val result = provider
            .openPgpRunner()
            .run(OpenPgpRequestSpec(OpenPgpOperation.GET_SIGN_KEY_ID))
            .requireOpenPgpSuccess()
        val createdAt = result.getLongExtra(OpenPgpApi.RESULT_KEY_CREATION_TIME, 0L)
        assertTrue("key_creation_time was $createdAt", createdAt > 0L)
        assertTrue(
            "key_creation_time $createdAt is not in the past; is it seconds, not ms?",
            createdAt < System.currentTimeMillis(),
        )
    }

    @Test
    fun k9ShapedRequestUsesInteractivePreselectedKey() {
        val expected = state.signKeyId()
        val userId = "Alice <${state.signingEmail()}>"
        val spec = OpenPgpRequestSpec(
            operation = OpenPgpOperation.GET_SIGN_KEY_ID,
            singleUserId = userId,
            preselectKeyId = expected,
        )
        val runner = provider.openPgpRunner()
        val first = runner.runOnce(spec)
        first.requireOpenPgpUserInteraction()
        val pendingIntent = first
            .legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
            ?: failWithExchangeLog("The K-9-shaped request did not provide an approval intent")
        val retry = provider
            .robot()
            .perform(pendingIntent, ApprovalRobot.Action.APPROVE)
            .retryIntent
            ?: failWithExchangeLog("The approval returned no retry intent")
        // K-9's OpenPgpKeyPreference re-stamps its own request onto the returned
        // intent before replaying it, and adds the Autocrypt hint on top.
        retry.apply {
            action = spec.action
            putExtras(spec.toIntent())
            putExtra(OpenPgpApi.EXTRA_SHOW_AUTOCRYPT_HINT, true)
        }
        val result = runner
            .runIntent(spec, retry)
            .requireOpenPgpSuccess()
        assertEquals(expected, result.getLongExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, 0L))

        val second = runner.runOnce(spec)
        second.requireOpenPgpUserInteraction()
        val secondPendingIntent = second
            .legs
            .first()
            .interactionPendingIntent(OpenPgpApi.RESULT_INTENT)
            ?: failWithExchangeLog("The second request did not provide an approval intent")
        provider
            .robot()
            .perform(secondPendingIntent, ApprovalRobot.Action.DENY)
    }

    @Test
    fun getKeyIdsReturnsTheApprovedEncryptionKeys() {
        assertTrue(state.encryptionKeyIds().isNotEmpty())
    }

    @Test
    fun getKeyExportsAnArmoredCertificate() {
        val output = exportKey(armored = true)
        val text = output.decodeToString().trimStart()
        assertTrue(
            "Expected an armored public key block, got \"${text.take(64)}\"",
            text.startsWith(PgpArmor.PUBLIC_KEY),
        )
    }

    @Test
    fun getKeyExportsABinaryCertificate() {
        val output = exportKey(armored = false)
        assertEquals(
            "Expected an OpenPGP public key packet",
            LocalCrypto.TAG_PUBLIC_KEY,
            LocalCrypto.packetTag(output),
        )
    }

    @Test
    fun getKeyWithoutAKeyIdIsRejected() {
        provider
            .openPgpRunner()
            .runOnce(OpenPgpRequestSpec(OpenPgpOperation.GET_KEY))
            .assertOpenPgpError(
                errorId = OpenPgpError.GENERIC_ERROR,
                messageContains = "extras for another action",
            )
    }

    private fun exportKey(armored: Boolean): ByteArray {
        val keyId = state.signKeyId()
        val exchange = provider
            .openPgpRunner()
            .run(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.GET_KEY,
                    keyId = keyId,
                    asciiArmor = armored,
                ),
            )
        exchange.requireOpenPgpSuccess()
        val output = exchange.output.orEmptyBytes()
        assertTrue("The certificate stream was empty", output.isNotEmpty())
        return output
    }
}
