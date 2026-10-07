package com.artemchep.keyguard.feature.gpgkey.replacement

import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementError as ReplacementFailure
import com.artemchep.keyguard.feature.gpgkey.RecordingGpgTranslator
import com.artemchep.keyguard.nativecrypto.OPEN_PGP_MAX_USER_ID_UTF8_BYTES
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_unresolved_revocation_message
import com.artemchep.keyguard.res.gpg_key_expiry_unsupported_signing_hash_message
import com.artemchep.keyguard.res.gpg_user_id_mutation_key_revoked_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_duplicate_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_invalid_message_with_limit
import com.artemchep.keyguard.res.gpg_user_id_replacement_private_key_required
import com.artemchep.keyguard.res.gpg_user_id_replacement_retired_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_same_identity_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_target_missing
import com.artemchep.keyguard.res.gpg_user_id_replacement_unavailable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgUserIdReplacementMessagesTest {
    @Test
    fun `inline and failure messages receive the validator byte limit`() = runTest {
        val inlineTranslator = RecordingGpgTranslator()
        inlineTranslator.translateGpgUserIdReplacementMessage(
            gpgUserIdReplacementErrorResource(
                GpgUserIdReplacementError.InvalidFormat,
            ),
        )
        val failureTranslator = RecordingGpgTranslator()
        failureTranslator.createLocalizedGpgUserIdReplacementFailureToast(
            ReplacementFailure.InvalidNewUserId,
        )
        val expected = Res.string.gpg_user_id_replacement_invalid_message_with_limit to
            listOf<Any>(OPEN_PGP_MAX_USER_ID_UTF8_BYTES)
        assertEquals(expected, inlineTranslator.calls.single())
        assertEquals(expected, failureTranslator.calls.last())
    }

    @Test
    fun `actionable replacement failures retain specific messages`() {
        mapOf(
            ReplacementFailure.EmptyPrivateKey to
                Res.string.gpg_user_id_replacement_private_key_required,
            ReplacementFailure.TargetNotFound to
                Res.string.gpg_user_id_replacement_target_missing,
            ReplacementFailure.InvalidNewUserId to
                Res.string.gpg_user_id_replacement_invalid_message_with_limit,
            ReplacementFailure.SameIdentity to
                Res.string.gpg_user_id_replacement_same_identity_message,
            ReplacementFailure.DuplicateIdentity to
                Res.string.gpg_user_id_replacement_duplicate_message,
            ReplacementFailure.PreviouslyRevokedIdentity to
                Res.string.gpg_user_id_replacement_retired_message,
            ReplacementFailure.UnresolvedRevocationAuthority to
                Res.string.gpg_key_expiry_unresolved_revocation_message,
            ReplacementFailure.UnsupportedSigningHash to
                Res.string.gpg_key_expiry_unsupported_signing_hash_message,
            ReplacementFailure.CertificateRevoked to
                Res.string.gpg_user_id_mutation_key_revoked_message,
            ReplacementFailure.UnsupportedPlatform to
                Res.string.gpg_user_id_replacement_unavailable,
        ).forEach { (reason, expected) ->
            assertEquals(expected, gpgUserIdReplacementFailureMessage(reason))
        }
    }
}
