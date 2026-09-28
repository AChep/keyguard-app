package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpSignatureResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenPgpSignatureResultContractTest {
    @Test
    fun `v4 signature results prefer the verified fingerprint over a forged issuer key ID`() {
        val result = verification().copy(
            keyId = "A5A5A5A5A5A5A5A5",
        ).toApiResult()
        assertEquals(0x0123456789ABCDEFL, result.keyId)
    }

    @Test
    fun `v4 signature results derive the verified key ID when the issuer hint is absent`() {
        val result = verification().copy(
            keyId = "0000000000000000",
        ).toApiResult()
        assertEquals(0x0123456789ABCDEFL, result.keyId)
    }

    @Test
    fun `v6 signature results use the verified high-order key ID regardless of issuer hints`() {
        listOf(
            "FEDCBA9876543210",
            "A5A5A5A5A5A5A5A5",
            "0000000000000000",
        ).forEach { issuerKeyId ->
            val result = verification().copy(
                fingerprint = "FEDCBA9876543210" + "A".repeat(48),
                keyId = issuerKeyId,
            ).toApiResult()
            assertEquals(0xFEDCBA9876543210uL.toLong(), result.keyId)
        }
    }

    @Test
    fun `signature results fall back to the issuer key ID without a verified fingerprint`() {
        val result = verification().copy(
            fingerprint = null,
            keyId = "FEDCBA9876543210",
        ).toApiResult()
        assertEquals(0xFEDCBA9876543210uL.toLong(), result.keyId)
    }

    @Test
    fun `valid and missing signature statuses map to official API results`() {
        assertEquals(
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
            verification().toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED,
            verification(GpgOpenPgpVerificationWarning.KEY_REVOKED)
                .toApiResult()
                .result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
            verification(GpgOpenPgpVerificationWarning.KEY_EXPIRED)
                .toApiResult()
                .result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
            verification(GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED)
                .toApiResult()
                .result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
            verification(GpgOpenPgpVerificationWarning.POLICY_CONFLICT)
                .toApiResult()
                .result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_KEY_MISSING,
            verification(status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY)
                .toApiResult()
                .result,
        )
    }

    @Test
    fun `invalid signature statuses map to official API results`() {
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE,
            verification(status = GpgOpenPgpVerificationStatus.INVALID)
                .toApiResult()
                .result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE,
            verification(
                GpgOpenPgpVerificationWarning.POLICY_CONFLICT,
                status = GpgOpenPgpVerificationStatus.INVALID,
            ).toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED,
            verification(
                GpgOpenPgpVerificationWarning.KEY_REVOKED,
                status = GpgOpenPgpVerificationStatus.INVALID,
            ).toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
            verification(
                GpgOpenPgpVerificationWarning.KEY_EXPIRED,
                status = GpgOpenPgpVerificationStatus.INVALID,
            ).toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
            verification(
                GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED,
                status = GpgOpenPgpVerificationStatus.INVALID,
            ).toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED,
            verification(
                GpgOpenPgpVerificationWarning.KEY_EXPIRED,
                GpgOpenPgpVerificationWarning.KEY_REVOKED,
                status = GpgOpenPgpVerificationStatus.INVALID,
            ).toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_KEY_MISSING,
            verification(
                GpgOpenPgpVerificationWarning.KEY_REVOKED,
                status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY,
            ).toApiResult().result,
        )
        // The native core never reports a weak-digest (SHA-1/MD5) data signature as
        // valid, so the warning always arrives alongside an INVALID status.
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE,
            verification(
                GpgOpenPgpVerificationWarning.WEAK_DIGEST,
                status = GpgOpenPgpVerificationStatus.INVALID,
            ).toApiResult().result,
        )
    }

    @Test
    fun `mixed multi signature results fail closed regardless of packet order`() {
        val valid = verification()
        val failures = listOf(
            verification(status = GpgOpenPgpVerificationStatus.INVALID) to
                    OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE,
            verification(status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY) to
                    OpenPgpSignatureResult.RESULT_KEY_MISSING,
            verification(GpgOpenPgpVerificationWarning.KEY_REVOKED) to
                    OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED,
            verification(GpgOpenPgpVerificationWarning.KEY_EXPIRED) to
                    OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
            verification(GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED) to
                    OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
        )

        failures.forEach { (failure, expectedResult) ->
            assertEquals(
                expectedResult,
                verification(signatures = listOf(valid, failure)).toApiResult().result,
            )
            assertEquals(
                expectedResult,
                verification(signatures = listOf(failure, valid)).toApiResult().result,
            )
        }
    }

    @Test
    fun `multi signature reduction applies conservative failure precedence`() {
        val invalid = verification(status = GpgOpenPgpVerificationStatus.INVALID)
        val revoked = verification(GpgOpenPgpVerificationWarning.KEY_REVOKED)
        val firstExpired = verification(GpgOpenPgpVerificationWarning.KEY_EXPIRED).copy(
            keyId = "1",
            fingerprint = null,
        )
        val secondExpired = firstExpired.copy(keyId = "2")
        val missing = verification(status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY)

        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE,
            verification(
                signatures = listOf(missing, revoked, firstExpired, invalid),
            ).toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED,
            verification(
                signatures = listOf(missing, firstExpired, revoked),
            ).toApiResult().result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_KEY_EXPIRED,
            verification(
                signatures = listOf(missing, firstExpired),
            ).toApiResult().result,
        )
        assertEquals(
            1L,
            verification(
                signatures = listOf(firstExpired, secondExpired),
            ).toApiResult().keyId,
        )
    }

    @Test
    fun `API v7 uses legacy result fallbacks`() {
        val noSignature = openPgpCompatibilityResults(
            apiVersion = 7,
            encrypted = false,
            verification = null,
            senderAddress = null,
        )
        assertNull(noSignature.decryptionResult)
        assertNull(noSignature.signature)

        val signedOnly = openPgpCompatibilityResults(
            apiVersion = 7,
            encrypted = false,
            verification = verification(),
            senderAddress = null,
        )
        assertNull(signedOnly.decryptionResult)
        assertEquals(
            OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED,
            signedOnly.signature?.result,
        )

        val modern = openPgpCompatibilityResults(
            apiVersion = 8,
            encrypted = true,
            verification = null,
            senderAddress = null,
        )
        assertEquals(
            OpenPgpDecryptionResult.RESULT_ENCRYPTED,
            modern.decryptionResult,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_NO_SIGNATURE,
            modern.signature?.result,
        )
    }
}
