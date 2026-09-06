package com.artemchep.keyguard.feature.gpgkey.revocation

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationError
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_unresolved_revocation_message
import com.artemchep.keyguard.res.gpg_key_expiry_unsupported_signing_hash_message
import com.artemchep.keyguard.res.gpg_user_id_mutation_key_revoked_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_failed_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_failed_title
import com.artemchep.keyguard.res.gpg_user_id_revocation_last_identity_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_private_key_required
import com.artemchep.keyguard.res.gpg_user_id_revocation_target_missing
import com.artemchep.keyguard.res.gpg_user_id_revocation_unavailable
import org.jetbrains.compose.resources.StringResource

internal suspend fun TranslatorScope.createLocalizedGpgUserIdRevocationFailureToast(
    reason: GpgUserIdRevocationError? = null,
): ToastMessage = ToastMessage(
    type = ToastMessage.Type.ERROR,
    title = translate(Res.string.gpg_user_id_revocation_failed_title),
    text = translate(gpgUserIdRevocationFailureMessage(reason)),
)

internal fun gpgUserIdRevocationFailureMessage(
    reason: GpgUserIdRevocationError?,
): StringResource = when (reason) {
    GpgUserIdRevocationError.EmptyPrivateKey ->
        Res.string.gpg_user_id_revocation_private_key_required

    GpgUserIdRevocationError.TargetNotFound,
    GpgUserIdRevocationError.MissingSelfSignature,
    -> Res.string.gpg_user_id_revocation_target_missing

    GpgUserIdRevocationError.LastUserId ->
        Res.string.gpg_user_id_revocation_last_identity_message

    GpgUserIdRevocationError.UnsupportedPlatform ->
        Res.string.gpg_user_id_revocation_unavailable

    GpgUserIdRevocationError.UnresolvedRevocationAuthority ->
        Res.string.gpg_key_expiry_unresolved_revocation_message

    GpgUserIdRevocationError.UnsupportedSigningHash ->
        Res.string.gpg_key_expiry_unsupported_signing_hash_message

    GpgUserIdRevocationError.CertificateRevoked ->
        Res.string.gpg_user_id_mutation_key_revoked_message

    GpgUserIdRevocationError.MalformedKey,
    GpgUserIdRevocationError.FingerprintMismatch,
    GpgUserIdRevocationError.UnsupportedKeyVersion,
    GpgUserIdRevocationError.ProtectedSecretKey,
    GpgUserIdRevocationError.NonRevocable,
    GpgUserIdRevocationError.TimeConflict,
    GpgUserIdRevocationError.SignatureVerificationFailed,
    GpgUserIdRevocationError.MetadataResolutionFailed,
    GpgUserIdRevocationError.InternalFailure,
    null,
    -> Res.string.gpg_user_id_revocation_failed_message
}
