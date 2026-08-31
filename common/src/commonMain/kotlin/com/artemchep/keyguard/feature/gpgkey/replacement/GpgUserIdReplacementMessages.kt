package com.artemchep.keyguard.feature.gpgkey.replacement

import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementError
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_unresolved_revocation_message
import com.artemchep.keyguard.res.gpg_key_expiry_unsupported_signing_hash_message
import com.artemchep.keyguard.res.gpg_user_id_mutation_key_revoked_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_duplicate_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_failed_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_failed_title
import com.artemchep.keyguard.res.gpg_user_id_replacement_invalid_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_private_key_required
import com.artemchep.keyguard.res.gpg_user_id_replacement_retired_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_same_identity_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_target_missing
import com.artemchep.keyguard.res.gpg_user_id_replacement_unavailable
import org.jetbrains.compose.resources.StringResource

internal suspend fun TranslatorScope.createLocalizedGpgUserIdReplacementFailureToast(
    reason: GpgUserIdReplacementError? = null,
): ToastMessage = ToastMessage(
    type = ToastMessage.Type.ERROR,
    title = translate(Res.string.gpg_user_id_replacement_failed_title),
    text = translate(gpgUserIdReplacementFailureMessage(reason)),
)

internal fun gpgUserIdReplacementFailureMessage(
    reason: GpgUserIdReplacementError?,
): StringResource = when (reason) {
    GpgUserIdReplacementError.EmptyPrivateKey ->
        Res.string.gpg_user_id_replacement_private_key_required

    GpgUserIdReplacementError.TargetNotFound,
    GpgUserIdReplacementError.TargetInactive,
    GpgUserIdReplacementError.MissingSelfSignature,
    -> Res.string.gpg_user_id_replacement_target_missing

    GpgUserIdReplacementError.InvalidNewUserId ->
        Res.string.gpg_user_id_replacement_invalid_message

    GpgUserIdReplacementError.SameIdentity ->
        Res.string.gpg_user_id_replacement_same_identity_message

    GpgUserIdReplacementError.DuplicateIdentity ->
        Res.string.gpg_user_id_replacement_duplicate_message

    GpgUserIdReplacementError.PreviouslyRevokedIdentity ->
        Res.string.gpg_user_id_replacement_retired_message

    GpgUserIdReplacementError.UnsupportedPlatform ->
        Res.string.gpg_user_id_replacement_unavailable

    GpgUserIdReplacementError.UnresolvedRevocationAuthority ->
        Res.string.gpg_key_expiry_unresolved_revocation_message

    GpgUserIdReplacementError.UnsupportedSigningHash ->
        Res.string.gpg_key_expiry_unsupported_signing_hash_message

    GpgUserIdReplacementError.CertificateRevoked ->
        Res.string.gpg_user_id_mutation_key_revoked_message

    GpgUserIdReplacementError.MalformedKey,
    GpgUserIdReplacementError.FingerprintMismatch,
    GpgUserIdReplacementError.AmbiguousPrimary,
    GpgUserIdReplacementError.UnsupportedKeyVersion,
    GpgUserIdReplacementError.ProtectedSecretKey,
    GpgUserIdReplacementError.NonRevocable,
    GpgUserIdReplacementError.UnsupportedTemplate,
    GpgUserIdReplacementError.PolicyConflict,
    GpgUserIdReplacementError.TimeConflict,
    GpgUserIdReplacementError.SignatureVerificationFailed,
    GpgUserIdReplacementError.MetadataResolutionFailed,
    GpgUserIdReplacementError.InternalFailure,
    null,
    -> Res.string.gpg_user_id_replacement_failed_message
}
