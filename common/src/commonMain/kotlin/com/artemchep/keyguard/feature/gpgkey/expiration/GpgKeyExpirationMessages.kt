package com.artemchep.keyguard.feature.gpgkey.expiration

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

internal suspend fun TranslatorScope.createLocalizedGpgKeyExpirationFailureToast(
    reason: GpgKeyExpirationError? = null,
): ToastMessage = ToastMessage(
    type = ToastMessage.Type.ERROR,
    title = translate(Res.string.gpg_key_expiry_failed_title),
    text = translate(gpgKeyExpirationFailureMessage(reason)),
)

/**
 * The user-facing explanation for one renewal failure.
 *
 * Kept apart from the toast so the mapping can be read, and tested, without a
 * translator.
 */
internal fun gpgKeyExpirationFailureMessage(
    reason: GpgKeyExpirationError?,
): StringResource = when (reason) {
    GpgKeyExpirationError.EmptyPrivateKey,
    GpgKeyExpirationError.MissingSecretKey,
    -> Res.string.gpg_key_expiry_private_key_required

    GpgKeyExpirationError.NoComponentsSelected ->
        Res.string.gpg_key_expiry_no_components_message

    GpgKeyExpirationError.RevokedComponent ->
        Res.string.gpg_key_expiry_revoked_message

    GpgKeyExpirationError.UnresolvedRevocationAuthority ->
        Res.string.gpg_key_expiry_unresolved_revocation_message

    GpgKeyExpirationError.UnsupportedSigningHash ->
        Res.string.gpg_key_expiry_unsupported_signing_hash_message

    GpgKeyExpirationError.InvalidExpiration ->
        Res.string.gpg_key_expiry_invalid_message

    GpgKeyExpirationError.UnsupportedPlatform ->
        Res.string.gpg_key_expiry_unavailable

    // The key carries no self-signature that verifies, so there is nothing for a
    // renewal to reissue. This is a dead key, not a transient failure, and the
    // generic "try again" wording would send the user in circles.
    GpgKeyExpirationError.MissingSelfSignature,
    GpgKeyExpirationError.SignatureVerificationFailed,
    -> Res.string.gpg_key_expiry_missing_self_signature_message

    GpgKeyExpirationError.MalformedKey,
    GpgKeyExpirationError.FingerprintMismatch,
    GpgKeyExpirationError.ComponentNotFound,
    GpgKeyExpirationError.UnsupportedKeyVersion,
    GpgKeyExpirationError.ProtectedSecretKey,
    GpgKeyExpirationError.TimeConflict,
    GpgKeyExpirationError.MetadataResolutionFailed,
    GpgKeyExpirationError.InternalFailure,
    null,
    -> Res.string.gpg_key_expiry_failed_message
}
