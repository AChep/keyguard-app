package com.artemchep.keyguard.feature.gpgkey.expiration

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

internal suspend fun TranslatorScope.createLocalizedGpgKeyExpirationFailureToast(
    reason: GpgKeyExpirationError? = null,
): ToastMessage = ToastMessage(
    type = ToastMessage.Type.ERROR,
    title = translate(Res.string.gpg_key_expiry_failed_title),
    text = translate(
        when (reason) {
            GpgKeyExpirationError.EmptyPrivateKey,
            GpgKeyExpirationError.MissingSecretKey,
            -> Res.string.gpg_key_expiry_private_key_required

            GpgKeyExpirationError.NoComponentsSelected ->
                Res.string.gpg_key_expiry_no_components_message

            GpgKeyExpirationError.RevokedComponent ->
                Res.string.gpg_key_expiry_revoked_message

            GpgKeyExpirationError.UnresolvedRevocationAuthority ->
                Res.string.gpg_key_expiry_unresolved_revocation_message

            GpgKeyExpirationError.InvalidExpiration ->
                Res.string.gpg_key_expiry_invalid_message

            GpgKeyExpirationError.UnsupportedPlatform ->
                Res.string.gpg_key_expiry_unavailable

            GpgKeyExpirationError.MalformedKey,
            GpgKeyExpirationError.FingerprintMismatch,
            GpgKeyExpirationError.ComponentNotFound,
            GpgKeyExpirationError.UnsupportedKeyVersion,
            GpgKeyExpirationError.ProtectedSecretKey,
            GpgKeyExpirationError.MissingSelfSignature,
            GpgKeyExpirationError.TimeConflict,
            GpgKeyExpirationError.SignatureVerificationFailed,
            GpgKeyExpirationError.MetadataResolutionFailed,
            GpgKeyExpirationError.InternalFailure,
            null,
            -> Res.string.gpg_key_expiry_failed_message
        },
    ),
)
