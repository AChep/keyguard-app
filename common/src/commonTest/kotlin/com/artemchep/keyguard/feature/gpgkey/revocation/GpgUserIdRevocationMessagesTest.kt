package com.artemchep.keyguard.feature.gpgkey.revocation

import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationError
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_unresolved_revocation_message
import com.artemchep.keyguard.res.gpg_key_expiry_unsupported_signing_hash_message
import com.artemchep.keyguard.res.gpg_user_id_mutation_key_revoked_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_last_identity_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_private_key_required
import com.artemchep.keyguard.res.gpg_user_id_revocation_target_missing
import com.artemchep.keyguard.res.gpg_user_id_revocation_unavailable
import kotlin.test.Test
import kotlin.test.assertEquals

class GpgUserIdRevocationMessagesTest {
    @Test
    fun `actionable revocation failures retain specific messages`() {
        mapOf(
            GpgUserIdRevocationError.EmptyPrivateKey to
                Res.string.gpg_user_id_revocation_private_key_required,
            GpgUserIdRevocationError.TargetNotFound to
                Res.string.gpg_user_id_revocation_target_missing,
            GpgUserIdRevocationError.LastUserId to
                Res.string.gpg_user_id_revocation_last_identity_message,
            GpgUserIdRevocationError.UnresolvedRevocationAuthority to
                Res.string.gpg_key_expiry_unresolved_revocation_message,
            GpgUserIdRevocationError.UnsupportedSigningHash to
                Res.string.gpg_key_expiry_unsupported_signing_hash_message,
            GpgUserIdRevocationError.CertificateRevoked to
                Res.string.gpg_user_id_mutation_key_revoked_message,
            GpgUserIdRevocationError.UnsupportedPlatform to
                Res.string.gpg_user_id_revocation_unavailable,
        ).forEach { (reason, expected) ->
            assertEquals(expected, gpgUserIdRevocationFailureMessage(reason))
        }
    }
}
