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
            userIds = arrayOf(" ALICE@example.com "),
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
            userIds = arrayOf(" alice@example.com "),
            preselectKeyId = 42L,
            senderAddress = " Alice@Example.com ",
        )
        assertNotNull(first)
        // Feed back exactly what the retry intent would carry.
        val retried = normalizeOpenPgpExtras(
            apiVersion = 12,
            asciiArmor = first.asciiArmor,
            compression = first.compression,
            opportunistic = first.opportunistic,
            originalFilename = first.originalFilename,
            userIds = first.requestedEmails.toTypedArray(),
            keyIds = first.keyIds,
            signKeyId = first.signKeyId,
            preselectKeyId = first.preselectKeyId,
            keyId = first.keyId,
            senderAddress = first.senderAddress,
            detachedSignature = first.detachedSignature,
        )
        assertNotNull(retried)
        assertEquals(first.digestParts, retried.digestParts)
    }

    @Test
    fun `sender address is canonicalized and bound to the digest`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            senderAddress = " Alice@Example.com ",
        )
        assertNotNull(normalized)
        assertEquals("alice@example.com", normalized.senderAddress)

        val absent = normalizeOpenPgpExtras(apiVersion = 12)
        val suppliedEmpty = normalizeOpenPgpExtras(apiVersion = 12, senderAddress = "")
        val different = normalizeOpenPgpExtras(
            apiVersion = 12,
            senderAddress = "bob@example.com",
        )
        assertNotNull(absent)
        assertNotNull(suppliedEmpty)
        assertNotNull(different)
        assertNotEquals(absent.digestParts, suppliedEmpty.digestParts)
        assertNotEquals(normalized.digestParts, different.digestParts)
    }

    @Test
    fun `API user id extras accept conventional OpenPGP identity syntax`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            userIds = arrayOf("Alice <ALICE@example.com>"),
            allowUserIdSyntax = true,
        )
        assertNotNull(normalized)
        assertEquals(listOf("alice@example.com"), normalized.requestedEmails)
        assertTrue("user_id=alice@example.com" in normalized.digestParts)

        val addressOnly = normalizeOpenPgpExtras(
            apiVersion = 12,
            userIds = arrayOf("<ALICE@example.com>"),
            allowUserIdSyntax = true,
        )
        assertNotNull(addressOnly)
        assertEquals(listOf("alice@example.com"), addressOnly.requestedEmails)

        val bareMailbox = normalizeOpenPgpExtras(
            apiVersion = 12,
            userIds = arrayOf("ALICE@example.com"),
            allowUserIdSyntax = true,
        )
        assertNotNull(bareMailbox)
        assertEquals(normalized.requestedEmails, bareMailbox.requestedEmails)

        assertNull(
            normalizeOpenPgpExtras(
                apiVersion = 12,
                userIds = arrayOf("Alice <alice@example.com>"),
            ),
        )

        val sender = normalizeOpenPgpExtras(
            apiVersion = 12,
            senderAddress = "Alice <alice@example.com>",
        )
        assertNotNull(sender)
        assertEquals("", sender.senderAddress)
    }

    @Test
    fun `preselected signing key remains a chooser hint`() {
        val normalized = normalizeOpenPgpExtras(
            apiVersion = 12,
            preselectKeyId = 42L,
        )
        assertNotNull(normalized)
        assertNull(normalized.signKeyId)
        assertEquals(42L, normalized.preselectKeyId)
        assertTrue("preselect_key_id=42" in normalized.digestParts)
        assertEquals(emptyList(), normalized.approvalConstraintKeyIds)
    }

    @Test
    fun `approval selection honors exactly one preselected candidate`() {
        fun candidate(
            id: String,
            preselected: Boolean = false,
        ) = AndroidIpcApprovalCoordinator.Candidate(
            id = id,
            name = id,
            description = "",
            preselected = preselected,
        )

        assertEquals(
            setOf("second"),
            initialAndroidIpcApprovalSelection(
                listOf(candidate("first"), candidate("second", preselected = true)),
            ),
        )
        assertEquals(
            emptySet(),
            initialAndroidIpcApprovalSelection(
                listOf(
                    candidate("first", preselected = true),
                    candidate("second", preselected = true),
                ),
            ),
        )
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
    fun `certified identities map to confirmed signature and sender results`() {
        val alice = "Alice <alice@example.com>"
        val secondary = "Secondary <secondary@example.com>"
        val verification = verification(
            userIds = listOf(alice, secondary),
            confirmedUserIds = listOf(alice),
        )

        val aliceResult = verification.toApiResult("ALICE@EXAMPLE.COM")
        assertEquals(OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED, aliceResult.result)
        assertEquals(listOf(alice), aliceResult.confirmedUserIds)
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_CONFIRMED,
            aliceResult.senderStatusResult,
        )
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_UNCONFIRMED,
            verification.toApiResult("secondary@example.com").senderStatusResult,
        )
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_CONFIRMED,
            verification(
                userIds = listOf("attacker@example.com <alice@example.com>"),
                confirmedUserIds = listOf("attacker@example.com <alice@example.com>"),
            ).toApiResult("alice@example.com").senderStatusResult,
        )
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_MISSING,
            verification(
                userIds = listOf("<bad<alice@example.com>"),
                confirmedUserIds = listOf("<bad<alice@example.com>"),
            ).toApiResult("alice@example.com").senderStatusResult,
        )
    }

    @Test
    fun `policy conflict never maps certified identities to confirmed results`() {
        val alice = "Alice <alice@example.com>"
        val result = verification(
            GpgOpenPgpVerificationWarning.POLICY_CONFLICT,
            confirmedUserIds = listOf(alice),
        ).toApiResult("alice@example.com")

        assertEquals(OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED, result.result)
        assertEquals(emptyList(), result.confirmedUserIds)
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_UNCONFIRMED,
            result.senderStatusResult,
        )
    }

    @Test
    fun `policy conflict is not hidden by a confirmed valid sibling signature`() {
        val alice = "Alice <alice@example.com>"
        val confirmed = verification(confirmedUserIds = listOf(alice))
        val conflicted = verification(
            GpgOpenPgpVerificationWarning.POLICY_CONFLICT,
            confirmedUserIds = listOf(alice),
        )

        listOf(
            listOf(confirmed, conflicted),
            listOf(conflicted, confirmed),
        ).forEach { signatures ->
            val result = verification(signatures = signatures)
                .toApiResult("alice@example.com")
            assertEquals(OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED, result.result)
            assertEquals(
                OpenPgpSignatureResult.SenderStatusResult.USER_ID_UNCONFIRMED,
                result.senderStatusResult,
            )
        }
    }

    @Test
    fun `known signatures always report the sender identity status`() {
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.UNKNOWN,
            verification().toApiResult().senderStatusResult,
        )
        listOf(
            "alice@example.com",
            "ALICE@EXAMPLE.COM",
            "secondary@example.com",
        ).forEach { senderAddress ->
            assertEquals(
                OpenPgpSignatureResult.SenderStatusResult.USER_ID_UNCONFIRMED,
                verification(
                    userIds = listOf(
                        "Alice <alice@example.com>",
                        "Secondary <secondary@example.com>",
                    ),
                ).toApiResult(senderAddress).senderStatusResult,
            )
        }
        listOf("mallory@example.com", "", "not-an-address").forEach { senderAddress ->
            assertEquals(
                OpenPgpSignatureResult.SenderStatusResult.USER_ID_MISSING,
                verification().toApiResult(senderAddress).senderStatusResult,
            )
        }
        listOf(
            GpgOpenPgpVerificationWarning.KEY_REVOKED,
            GpgOpenPgpVerificationWarning.KEY_EXPIRED,
        ).forEach { warning ->
            assertEquals(
                OpenPgpSignatureResult.SenderStatusResult.USER_ID_UNCONFIRMED,
                verification(warning)
                    .toApiResult("alice@example.com")
                    .senderStatusResult,
            )
        }
    }

    @Test
    fun `sender status uses the selected signature identities`() {
        val selected = verification(
            GpgOpenPgpVerificationWarning.KEY_REVOKED,
            userIds = listOf("Bob <bob@example.com>"),
        )
        val result = verification(
            userIds = listOf("Alice <alice@example.com>"),
            signatures = listOf(selected),
        ).toApiResult("bob@example.com")

        assertEquals(OpenPgpSignatureResult.RESULT_INVALID_KEY_REVOKED, result.result)
        assertEquals(
            OpenPgpSignatureResult.SenderStatusResult.USER_ID_UNCONFIRMED,
            result.senderStatusResult,
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

    @Test
    fun `sender status is populated for every supported API version`() {
        (MIN_API_VERSION..MAX_API_VERSION).forEach { apiVersion ->
            val result = openPgpCompatibilityResults(
                apiVersion = apiVersion,
                encrypted = apiVersion % 2 == 0,
                verification = verification(),
                senderAddress = "alice@example.com",
            )
            assertEquals(
                OpenPgpSignatureResult.SenderStatusResult.USER_ID_UNCONFIRMED,
                result.signature?.senderStatusResult,
            )
        }
    }

    /** The production overload requires the caller to decide on a sender address. */
    private fun GpgOpenPgpVerification?.toApiResult() = toApiResult(senderAddress = null)

    private fun verification(
        vararg warnings: GpgOpenPgpVerificationWarning,
        status: GpgOpenPgpVerificationStatus = GpgOpenPgpVerificationStatus.VALID,
        signatures: List<GpgOpenPgpVerification> = emptyList(),
        userIds: List<String> = listOf("Alice <alice@example.com>"),
        confirmedUserIds: List<String> = emptyList(),
    ) = GpgOpenPgpVerification(
        status = status,
        keyId = "0123456789ABCDEF",
        fingerprint = "00112233445566778899AABB0123456789ABCDEF",
        userIds = userIds,
        createdAt = Instant.fromEpochSeconds(1_700_000_000L),
        warnings = warnings.toList(),
        confirmedUserIds = confirmedUserIds,
        signatures = signatures,
    )
}
