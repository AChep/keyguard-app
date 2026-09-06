package com.artemchep.keyguard.feature.gpgkey.replacement

import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementError
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_unresolved_revocation_message
import com.artemchep.keyguard.res.gpg_key_expiry_unsupported_signing_hash_message
import com.artemchep.keyguard.res.gpg_user_id_mutation_key_revoked_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_duplicate_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_invalid_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_private_key_required
import com.artemchep.keyguard.res.gpg_user_id_replacement_retired_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_same_identity_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_target_missing
import com.artemchep.keyguard.res.gpg_user_id_replacement_unavailable
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgUserIdReplacementMessagesTest {
    @Test
    fun `actionable replacement failures retain specific messages`() {
        mapOf(
            GpgUserIdReplacementError.EmptyPrivateKey to
                Res.string.gpg_user_id_replacement_private_key_required,
            GpgUserIdReplacementError.TargetNotFound to
                Res.string.gpg_user_id_replacement_target_missing,
            GpgUserIdReplacementError.InvalidNewUserId to
                Res.string.gpg_user_id_replacement_invalid_message,
            GpgUserIdReplacementError.SameIdentity to
                Res.string.gpg_user_id_replacement_same_identity_message,
            GpgUserIdReplacementError.DuplicateIdentity to
                Res.string.gpg_user_id_replacement_duplicate_message,
            GpgUserIdReplacementError.PreviouslyRevokedIdentity to
                Res.string.gpg_user_id_replacement_retired_message,
            GpgUserIdReplacementError.UnresolvedRevocationAuthority to
                Res.string.gpg_key_expiry_unresolved_revocation_message,
            GpgUserIdReplacementError.UnsupportedSigningHash to
                Res.string.gpg_key_expiry_unsupported_signing_hash_message,
            GpgUserIdReplacementError.CertificateRevoked to
                Res.string.gpg_user_id_mutation_key_revoked_message,
            GpgUserIdReplacementError.UnsupportedPlatform to
                Res.string.gpg_user_id_replacement_unavailable,
        ).forEach { (reason, expected) ->
            assertEquals(expected, gpgUserIdReplacementFailureMessage(reason))
        }
    }
}
