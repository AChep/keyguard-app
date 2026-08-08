package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import org.openintents.openpgp.OpenPgpDecryptionResult
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OpenPgpContractTest {
    @Test
    fun `API versions seven through twelve are accepted`() {
        (7..12).forEach {
            assertTrue(isSupportedOpenPgpApiVersion(it))
        }
        assertFalse(isSupportedOpenPgpApiVersion(6))
        assertFalse(isSupportedOpenPgpApiVersion(13))
        assertFalse(isSupportedOpenPgpApiVersion(Int.MIN_VALUE))
    }

    @Test
    fun `K-9 Autocrypt recipient status action is supported`() {
        assertTrue(
            OpenPgpApi.ACTION_QUERY_AUTOCRYPT_STATUS in
                    OpenPgpService.SUPPORTED_ACTIONS,
        )
    }

    @Test
    fun `request normalization rejects unsupported extras and binds detached signature bytes`() {
        val first = normalizeOpenPgpExtras(
            apiVersion = 12,
            detachedSignature = byteArrayOf(1, 2, 3),
        )
        val second = normalizeOpenPgpExtras(
            apiVersion = 12,
            detachedSignature = byteArrayOf(3, 2, 1),
        )

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first.digestParts, second.digestParts)
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                minimize = true,
            ),
        )
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                hasCustomHeaders = true,
            ),
        )
    }

    @Test
    fun `request normalization limits collections and canonicalizes selected key ids`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            keyIds = longArrayOf(3L, 1L, 3L),
            selectedKeyIds = longArrayOf(2L, 1L),
            userIds = arrayOf(" Alice <ALICE@example.com> "),
        )
        assertNotNull(normalized)
        assertEquals(listOf(3L, 1L, 2L), normalized.keyIds.toList())
        assertTrue("user_id=alice@example.com" in normalized.digestParts)
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                keyIds = LongArray(65),
            ),
        )
    }

    @Test
    fun `emitted retry extras re-normalize to the same digest`() {
        val first = normalizeOpenPgpExtras(
            apiVersion = 12,
            keyIds = longArrayOf(3L, 1L),
            selectedKeyIds = longArrayOf(2L, 1L),
            userIds = arrayOf(" Alice <alice@example.com> "),
        )
        assertNotNull(first)
        // Feed back exactly what the retry intent would carry.
        val retried = normalizeOpenPgpExtras(
            apiVersion = 12,
            asciiArmor = first.asciiArmor,
            compression = first.compression,
            opportunistic = first.opportunistic,
            originalFilename = first.originalFilename,
            userIds = first.userIds.toTypedArray(),
            keyIds = first.keyIds,
            signKeyId = first.signKeyId,
            keyId = first.keyId,
            detachedSignature = first.detachedSignature,
        )
        assertNotNull(retried)
        assertEquals(first.digestParts, retried.digestParts)
    }

    @Test
    fun `combined key ids beyond the cap are rejected at admission`() {
        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                keyIds = LongArray(40) { it.toLong() },
                selectedKeyIds = LongArray(40) { (it + 100).toLong() },
            ),
        )
        // Overlapping ids dedupe below the cap and stay valid.
        assertNotNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                keyIds = LongArray(40) { it.toLong() },
                selectedKeyIds = LongArray(40) { it.toLong() },
            ),
        )
    }

    @Test
    fun `action-specific extras are validated before approval`() {
        assertFalse(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_GET_KEY,
                keyId = null,
                hasDetachedSignature = false,
            ),
        )
        assertTrue(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_GET_KEY,
                keyId = 42L,
                hasDetachedSignature = false,
            ),
        )
        assertTrue(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_DECRYPT_VERIFY,
                keyId = null,
                hasDetachedSignature = true,
            ),
        )
        assertFalse(
            hasValidOpenPgpActionExtras(
                action = OpenPgpApi.ACTION_ENCRYPT,
                keyId = null,
                hasDetachedSignature = true,
            ),
        )
    }

    @Test
    fun `armor charset accepts installed canonical charset names`() {
        assertEquals("UTF-8", normalizeArmorCharset(" utf-8 "))
        assertEquals("ISO-8859-1", normalizeArmorCharset("ISO-8859-1"))
        assertNull(normalizeArmorCharset(""))
        assertNull(normalizeArmorCharset("not-a-real-charset"))
    }

    @Test
    fun `signature statuses map to official API results`() {
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
            OpenPgpSignatureResult.RESULT_KEY_MISSING,
            verification(status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY)
                .toApiResult()
                .result,
        )
        assertEquals(
            OpenPgpSignatureResult.RESULT_INVALID_SIGNATURE,
            verification(status = GpgOpenPgpVerificationStatus.INVALID)
                .toApiResult()
                .result,
        )
    }

    @Test
    fun `API v7 uses legacy result fallbacks`() {
        val noSignature = openPgpCompatibilityResults(
            apiVersion = 7,
            encrypted = false,
            verification = null,
        )
        assertNull(noSignature.decryptionResult)
        assertNull(noSignature.signature)

        val signedOnly = openPgpCompatibilityResults(
            apiVersion = 7,
            encrypted = false,
            verification = verification(),
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

    private fun verification(
        vararg warnings: GpgOpenPgpVerificationWarning,
        status: GpgOpenPgpVerificationStatus = GpgOpenPgpVerificationStatus.VALID,
    ) = GpgOpenPgpVerification(
        status = status,
        keyId = "0123456789ABCDEF",
        fingerprint = "00112233445566778899AABB0123456789ABCDEF",
        userIds = listOf("Alice <alice@example.com>"),
        createdAt = Instant.fromEpochSeconds(1_700_000_000L),
        warnings = warnings.toList(),
    )
}
