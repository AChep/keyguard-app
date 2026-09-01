package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.openPgpDecryptionStatusName
import com.artemchep.keyguard.ipctestclient.ipc.openPgpSignatureStatusName
import com.artemchep.keyguard.ipctestclient.ipc.orEmptyBytes
import com.artemchep.keyguard.ipctestclient.support.ApprovalRobot
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.requireOpenPgpSuccess
import com.artemchep.keyguard.ipctestclient.support.requireSignatureResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpMetadata
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi

@RunWith(AndroidJUnit4::class)
class OpenPgpEncryptionRoundTripTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun encryptThenDecryptReturnsThePlaintext() {
        val ciphertext = encrypt(OpenPgpOperation.ENCRYPT)
        val exchange = decrypt(ciphertext, OpenPgpOperation.DECRYPT_VERIFY)
        val result = exchange.requireOpenPgpSuccess()
        assertArrayEquals(PAYLOAD, exchange.output)
        @Suppress("DEPRECATION")
        val decryption = requireNotNull(
            result.getParcelableExtra<OpenPgpDecryptionResult>(OpenPgpApi.RESULT_DECRYPTION),
        )
        assertEquals(
            openPgpDecryptionStatusName(OpenPgpDecryptionResult.RESULT_ENCRYPTED),
            openPgpDecryptionStatusName(decryption.result),
        )
    }

    @Test
    fun signAndEncryptThenDecryptReportsTheSenderIdentityStatus() {
        state.signKeyId()
        val ciphertext = encrypt(OpenPgpOperation.SIGN_AND_ENCRYPT)
        val result = decrypt(
            ciphertext = ciphertext,
            operation = OpenPgpOperation.DECRYPT_VERIFY,
            senderAddress = state.signingEmail(),
        )
            .requireOpenPgpSuccess()
        val signature = result.requireSignatureResult()
        assertEquals(
            openPgpSignatureStatusName(OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED),
            openPgpSignatureStatusName(signature.result),
        )
        assertTrue(signature.confirmedUserIds.isNotEmpty())
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_CONFIRMED,
            signature.senderStatusResult,
        )

        val mismatchedResult = decrypt(
            ciphertext = ciphertext,
            operation = OpenPgpOperation.DECRYPT_VERIFY,
            senderAddress = "not-the-signer@example.invalid",
        ).requireOpenPgpSuccess()
        val mismatchedSignature = mismatchedResult.requireSignatureResult()
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_MISSING,
            mismatchedSignature.senderStatusResult,
        )
    }

    /** Compression is on unless the client says otherwise; both must round trip. */
    @Test
    fun encryptWithCompressionDisabledStillRoundTrips() {
        val ciphertext = encrypt(OpenPgpOperation.ENCRYPT, compression = false)
        val exchange = decrypt(ciphertext, OpenPgpOperation.DECRYPT_VERIFY)
        exchange.requireOpenPgpSuccess()
        assertArrayEquals(PAYLOAD, exchange.output)
    }

    @Test
    fun encryptCarriesTheOriginalFilenameIntoTheMetadata() {
        val ciphertext = encrypt(OpenPgpOperation.ENCRYPT, filename = FILENAME)
        val result = decrypt(ciphertext, OpenPgpOperation.DECRYPT_VERIFY)
            .requireOpenPgpSuccess()
        @Suppress("DEPRECATION")
        val metadata = requireNotNull(
            result.getParcelableExtra<OpenPgpMetadata>(OpenPgpApi.RESULT_METADATA),
        )
        assertEquals(FILENAME, metadata.filename)
    }

    /** The metadata action reads the message but must never emit the plaintext. */
    @Test
    fun decryptMetadataDiscardsThePlaintextEvenWithAPipe() {
        val ciphertext = encrypt(OpenPgpOperation.ENCRYPT)
        val exchange = decrypt(ciphertext, OpenPgpOperation.DECRYPT_METADATA)
        val result = exchange.requireOpenPgpSuccess()
        assertTrue(
            "DECRYPT_METADATA leaked ${exchange.output?.size} bytes of plaintext",
            exchange.output.orEmptyBytes().isEmpty(),
        )
        assertTrue(result.hasExtra(OpenPgpApi.RESULT_METADATA))
    }

    @Test
    fun decryptVerifyWithoutAnOutputPipeStillReportsTheSignature() {
        val ciphertext = encrypt(OpenPgpOperation.ENCRYPT)
        val exchange = provider.openPgpRunner().run(
            OpenPgpRequestSpec(
                operation = OpenPgpOperation.DECRYPT_VERIFY,
                payload = ciphertext,
                omitOutputPipe = true,
            ),
        )
        val result = exchange.requireOpenPgpSuccess()
        assertNull(exchange.output)
        assertTrue(result.hasExtra(OpenPgpApi.RESULT_METADATA))
    }

    /** `decryption` was added in API 8, so version 7 must not receive it. */
    @Test
    fun apiVersionSevenOmitsTheDecryptionResult() {
        val ciphertext = encrypt(OpenPgpOperation.ENCRYPT)
        val result = provider
            .openPgpRunner()
            .run(
                OpenPgpRequestSpec(
                    operation = OpenPgpOperation.DECRYPT_VERIFY,
                    apiVersion = OpenPgpOperation.MIN_API_VERSION,
                    payload = ciphertext,
                ),
            )
            .requireOpenPgpSuccess()
        assertFalse(result.hasExtra(OpenPgpApi.RESULT_DECRYPTION))
    }

    private fun encrypt(
        operation: OpenPgpOperation,
        compression: Boolean? = null,
        filename: String? = null,
    ): ByteArray {
        val exchange = provider
            .openPgpRunner(ApprovalRobot.Action.APPROVE_ALL_CANDIDATES)
            .run(
                OpenPgpRequestSpec(
                    operation = operation,
                    payload = PAYLOAD,
                    keyIds = state.encryptionKeyIds(),
                    enableCompression = compression,
                    originalFilename = filename,
                ),
            )
        exchange.requireOpenPgpSuccess()
        val ciphertext = exchange.output.orEmptyBytes()
        assertTrue("The ciphertext stream was empty", ciphertext.isNotEmpty())
        return ciphertext
    }

    private fun decrypt(
        ciphertext: ByteArray,
        operation: OpenPgpOperation,
        senderAddress: String? = null,
    ) = provider
        .openPgpRunner()
        .run(
            OpenPgpRequestSpec(
                operation = operation,
                payload = ciphertext,
                senderAddress = senderAddress,
            ),
        )

    private companion object {
        val PAYLOAD = "round trip through the OpenPGP provider".encodeToByteArray()
        const val FILENAME = "message.txt"
    }
}
