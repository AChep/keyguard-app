package com.artemchep.keyguard.feature.gpgkey.expiration

import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A renewal failure the user can act on must not read like one they cannot, and
 * the reverse: a key with no verifiable self-signature is unrepairable, and the
 * generic "the original key was left unchanged" wording invites a pointless retry.
 */
class GpgKeyExpirationMessagesTest {
    @Test
    fun `a key with no verifiable self-signature says so instead of failing generically`() {
        listOf(
            GpgKeyExpirationError.MissingSelfSignature,
            GpgKeyExpirationError.SignatureVerificationFailed,
        ).forEach { reason ->
            assertEquals(
                Res.string.gpg_key_expiry_missing_self_signature_message,
                gpgKeyExpirationFailureMessage(reason),
                "$reason must report an unrepairable key",
            )
        }
    }

    @Test
    fun `the remaining opaque reasons keep the generic message`() {
        listOf(
            GpgKeyExpirationError.MalformedKey,
            GpgKeyExpirationError.FingerprintMismatch,
            GpgKeyExpirationError.ComponentNotFound,
            GpgKeyExpirationError.UnsupportedKeyVersion,
            GpgKeyExpirationError.ProtectedSecretKey,
            GpgKeyExpirationError.TimeConflict,
            GpgKeyExpirationError.MetadataResolutionFailed,
            GpgKeyExpirationError.InternalFailure,
            null,
        ).forEach { reason ->
            assertEquals(
                Res.string.gpg_key_expiry_failed_message,
                gpgKeyExpirationFailureMessage(reason),
                "$reason must keep the generic message",
            )
        }
    }

    @Test
    fun `every specific reason keeps a message of its own`() {
        mapOf(
            GpgKeyExpirationError.EmptyPrivateKey to Res.string.gpg_key_expiry_private_key_required,
            GpgKeyExpirationError.MissingSecretKey to Res.string.gpg_key_expiry_private_key_required,
            GpgKeyExpirationError.NoComponentsSelected to
                Res.string.gpg_key_expiry_no_components_message,
            GpgKeyExpirationError.RevokedComponent to Res.string.gpg_key_expiry_revoked_message,
            GpgKeyExpirationError.UnresolvedRevocationAuthority to
                Res.string.gpg_key_expiry_unresolved_revocation_message,
            GpgKeyExpirationError.UnsupportedSigningHash to
                Res.string.gpg_key_expiry_unsupported_signing_hash_message,
            GpgKeyExpirationError.InvalidExpiration to Res.string.gpg_key_expiry_invalid_message,
            GpgKeyExpirationError.UnsupportedPlatform to Res.string.gpg_key_expiry_unavailable,
        ).forEach { (reason, expected) ->
            assertEquals(expected, gpgKeyExpirationFailureMessage(reason))
            assertNotEquals(Res.string.gpg_key_expiry_failed_message, expected)
        }
    }
}
