package com.artemchep.keyguard.feature.gpgagent.tools

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptionWarning
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_tools_warning_elgamal_decryption_key
import com.artemchep.keyguard.res.gpg_tools_warning_weak_rsa_decryption_key
import com.artemchep.keyguard.ui.SimpleNote
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgToolsVerificationNoteTest {
    @Test
    fun `valid verification with a warning is not shown as an unconditional success`() {
        assertEquals(SimpleNote.Type.OK, verification().verificationNoteType())
        listOf(
            GpgOpenPgpVerificationWarning.KEY_REVOKED,
            GpgOpenPgpVerificationWarning.KEY_EXPIRED,
            GpgOpenPgpVerificationWarning.POLICY_CONFLICT,
        ).forEach { warning ->
            assertEquals(
                SimpleNote.Type.WARNING,
                verification(warning).verificationNoteType(),
                warning.name,
            )
        }
    }

    @Test
    fun `invalid verification remains an error when warnings are present`() {
        assertEquals(
            SimpleNote.Type.ERROR,
            verification(
                GpgOpenPgpVerificationWarning.POLICY_CONFLICT,
                status = GpgOpenPgpVerificationStatus.INVALID,
            ).verificationNoteType(),
        )
    }

    @Test
    fun `multi signature verification exposes every leaf in packet order`() {
        val valid = verification()
        val missing = verification(status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY)
        val invalid = verification(status = GpgOpenPgpVerificationStatus.INVALID)
        val aggregate = verification(
            signatures = listOf(valid, missing, invalid),
        )

        assertEquals(listOf(valid, missing, invalid), aggregate.verificationLeaves())
    }

    @Test
    fun `decryption warnings have dedicated user facing messages`() {
        assertEquals(
            Res.string.gpg_tools_warning_weak_rsa_decryption_key,
            GpgOpenPgpDecryptionWarning.WEAK_RSA_KEY.decryptionWarningStringResource(),
        )
        assertEquals(
            Res.string.gpg_tools_warning_elgamal_decryption_key,
            GpgOpenPgpDecryptionWarning.ELGAMAL_KEY.decryptionWarningStringResource(),
        )
    }

    private fun verification(
        vararg warnings: GpgOpenPgpVerificationWarning,
        status: GpgOpenPgpVerificationStatus = GpgOpenPgpVerificationStatus.VALID,
        signatures: List<GpgOpenPgpVerification> = emptyList(),
    ) = GpgOpenPgpVerification(
        status = status,
        keyId = "0123456789ABCDEF",
        fingerprint = "00112233445566778899AABB0123456789ABCDEF",
        userIds = emptyList(),
        createdAt = null,
        warnings = warnings.toList(),
        signatures = signatures,
    )
}
