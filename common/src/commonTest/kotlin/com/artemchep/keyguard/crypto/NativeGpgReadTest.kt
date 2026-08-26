package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerification
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerificationStatus
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpVerificationWarning
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeGpgReadTest {
    @Test
    fun `policy conflict warning maps to the domain without changing validity`() {
        val verification = NativeOpenPgpVerification(
            status = NativeOpenPgpVerificationStatus.VALID,
            keyId = "0123456789ABCDEF",
            fingerprint = PRIMARY_FINGERPRINT,
            userIds = emptyList(),
            createdAtEpochSeconds = 1_700_000_000L,
            warnings = listOf(NativeOpenPgpVerificationWarning.POLICY_CONFLICT),
        ).toDomain()

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
        assertEquals(
            listOf(GpgOpenPgpVerificationWarning.POLICY_CONFLICT),
            verification.warnings,
        )
    }

    @Test
    fun `resource limited public key returns malformed`() {
        val oversizedInput = "A".repeat(NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES + 1)

        assertEquals(
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed),
            NativeGpgPublicKeyParser.parse(oversizedInput),
        )
    }

    @Test
    fun `metadata resolver does not repair a non-hex fingerprint`() {
        assertNull(
            NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = "not parsed",
                publicKeyArmored = null,
                fingerprint = "0123456789ABCDEG",
                candidateRevocationKeys = emptyList(),
            ),
        )
    }

    @Test
    fun `oversized detached signature returns stable error and closes both sources`() {
        val input = TrackingRawSource(0)
        val signature = TrackingRawSource(
            NativeCrypto.MAX_CONTROL_ENVELOPE_BYTES.toLong() / 2L + 1L,
        )

        val failure = assertFailsWith<NativeCryptoException> {
            NativeGpgOpenPgpVerifier.verifyFile(
                GpgOpenPgpVerifyFileRequest(
                    input = input.buffered(),
                    signatureInput = signature.buffered(),
                    publicKeys = emptyList(),
                ),
            )
        }

        assertEquals("open_pgp_detached_verify.stream_open", failure.operation)
        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
        assertTrue(input.closed)
        assertTrue(signature.closed)
    }

    @Test
    fun `successful streamed verification closes both sources`() {
        val input = TrackingRawSource(DETACHED_BODY.encodeToByteArray())
        val signature = TrackingRawSource(DETACHED_SIGNATURE.encodeToByteArray())

        val verification = NativeGpgOpenPgpVerifier.verifyFile(
            GpgOpenPgpVerifyFileRequest(
                input = input.buffered(),
                signatureInput = signature.buffered(),
                publicKeys = listOf(GpgOpenPgpPublicKey(PUBLIC_KEY)),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
        assertEquals(PRIMARY_FINGERPRINT, verification.fingerprint)
        assertTrue(input.closed)
        assertTrue(signature.closed)
    }

    @Test
    fun `tampered streamed verification returns invalid and closes both sources`() {
        val tampered = DETACHED_BODY.encodeToByteArray().also { body ->
            body[0] = 'X'.code.toByte()
        }
        val input = TrackingRawSource(tampered)
        val signature = TrackingRawSource(DETACHED_SIGNATURE.encodeToByteArray())

        val verification = NativeGpgOpenPgpVerifier.verifyFile(
            GpgOpenPgpVerifyFileRequest(
                input = input.buffered(),
                signatureInput = signature.buffered(),
                publicKeys = listOf(GpgOpenPgpPublicKey(PUBLIC_KEY)),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.INVALID, verification.status)
        assertTrue(input.closed)
        assertTrue(signature.closed)
    }

    private companion object {
        const val PRIMARY_FINGERPRINT = GPG_TEST_CV25519_PRIMARY_FINGERPRINT
        val PUBLIC_KEY = GPG_TEST_CV25519_PUBLIC_KEY
        val DETACHED_BODY = """
            Independent OpenPGP verification fixture.
            Second line.
        """.trimIndent() + "\n"
        val DETACHED_SIGNATURE = """
            -----BEGIN PGP SIGNATURE-----

            iJEEABYKADkWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCalbNgBsUgAAAAAAEAA5t
            YW51MiwyLjUrMS4xMiwwLDMACgkQ+D2UfSnv7Pe4sQEAowtp7N4njm4eBEi+bgC1
            VxGYWoE70RB//wCTrwaVtggBAL3MVySwcv/iU0y9pM+91TaerHhzhSNnDjcJTS4d
            SOEL
            =6B1K
            -----END PGP SIGNATURE-----
        """.trimIndent() + "\n"
    }
}

private class TrackingRawSource(
    private var remaining: Long,
    private val contents: ByteArray? = null,
) : RawSource {
    private val chunk = ByteArray(64 * 1024)
    private var offset = 0

    constructor(contents: ByteArray) : this(
        remaining = contents.size.toLong(),
        contents = contents,
    )

    var closed: Boolean = false
        private set

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        check(!closed)
        if (remaining == 0L) return -1L
        val count = minOf(remaining, byteCount, chunk.size.toLong()).toInt()
        val source = contents ?: chunk
        val startIndex = if (contents != null) offset else 0
        sink.write(source, startIndex = startIndex, endIndex = startIndex + count)
        offset += count
        remaining -= count
        return count.toLong()
    }

    override fun close() {
        closed = true
    }
}
