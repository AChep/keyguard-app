package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import org.openintents.openpgp.OpenPgpSignatureResult
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenPgpSenderStatusContractTest {
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
}
