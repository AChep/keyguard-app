package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.IpcExchange
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.PgpArmor
import com.artemchep.keyguard.ipctestclient.ipc.openPgpSignatureStatusName
import com.artemchep.keyguard.ipctestclient.ipc.orEmptyBytes
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.LocalCrypto
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi

@RunWith(AndroidJUnit4::class)
class OpenPgpSigningTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun detachedSignReturnsAnArmoredSignatureAndTheSigningKeyId() {
        val signKeyId = state.signKeyId()
        val result = detachedSign(armored = true).requireOpenPgpSuccess()
        val signature = result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE)
        assertNotNull("No detached_signature in the result", signature)
        assertTrue(
            "Expected an armored signature",
            signature!!.decodeToString().trimStart().startsWith(PgpArmor.SIGNATURE),
        )
        assertEquals(signKeyId, result.getLongExtra(OpenPgpApi.RESULT_SIGN_KEY_ID, 0L))
    }

    /** The MIC algorithm is what a PGP/MIME client puts in the multipart header. */
    @Test
    fun detachedSignReportsABinarySignatureAndItsMicAlgorithm() {
        val result = detachedSign(armored = false).requireOpenPgpSuccess()
        val signature = requireNotNull(
            result.getByteArrayExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE),
        )
        assertEquals(
            "Expected an OpenPGP signature packet",
            LocalCrypto.TAG_SIGNATURE,
            LocalCrypto.packetTag(signature),
        )
        assertEquals(
            "pgp-sha256",
            result.getStringExtra(OpenPgpApi.RESULT_SIGNATURE_MICALG),
        )
    }

    @Test
    fun signProducesAClearSignedMessage() {
        assertClearSigned(OpenPgpOperation.SIGN)
    }

    @Test
    fun cleartextSignProducesAClearSignedMessage() {
        assertClearSigned(OpenPgpOperation.CLEARTEXT_SIGN)
    }

    /** Round trip: whatever the provider signs, it must also verify. */
    @Test
    fun decryptVerifyAcceptsTheProvidersOwnClearSignedMessage() {
        val signKeyId = state.signKeyId()
        val signed = clearSign(OpenPgpOperation.CLEARTEXT_SIGN)
        val exchange = provider
            .openPgpRunner()
            .run(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.DECRYPT_VERIFY,
                    payload = signed,
                ),
            )
        val result = exchange.requireOpenPgpSuccess()
        @Suppress("DEPRECATION")
        val signature = requireNotNull(
            result.getParcelableExtra<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE),
        )
        assertEquals(
            openPgpSignatureStatusName(OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED),
            openPgpSignatureStatusName(signature.result),
        )
        assertEquals(signKeyId, signature.keyId)
        assertTrue(
            "The verified body does not contain the payload",
            exchange.output.orEmptyBytes().decodeToString().contains(PAYLOAD.decodeToString()),
        )
    }

    private fun detachedSign(armored: Boolean): IpcExchange {
        state.signKeyId()
        return provider.openPgpRunner().run(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.DETACHED_SIGN,
                payload = PAYLOAD,
                asciiArmor = armored,
            ),
        )
    }

    private fun clearSign(operation: OpenPgpOperation): ByteArray {
        state.signKeyId()
        val exchange = provider.openPgpRunner().run(
            OpenPgpRequestSpec(operation = operation, payload = PAYLOAD),
        )
        exchange.requireOpenPgpSuccess()
        return exchange.output.orEmptyBytes()
    }

    private fun assertClearSigned(operation: OpenPgpOperation) {
        val output = clearSign(operation).decodeToString().trimStart()
        assertTrue(
            "Expected a clear-signed message, got \"${output.take(64)}\"",
            output.startsWith(PgpArmor.SIGNED_MESSAGE),
        )
    }

    private companion object {
        val PAYLOAD = "cross-package OpenPGP IPC".encodeToByteArray()
    }
}
