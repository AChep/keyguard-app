package com.artemchep.keyguard.feature.gpgkey.replacement

import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.nativecrypto.OPEN_PGP_MAX_USER_ID_UTF8_BYTES
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_unresolved_revocation_message
import com.artemchep.keyguard.res.gpg_key_expiry_unsupported_signing_hash_message
import com.artemchep.keyguard.res.gpg_user_id_mutation_key_revoked_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_duplicate_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_invalid_message_with_limit
import com.artemchep.keyguard.res.gpg_user_id_replacement_retired_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_same_identity_message
import com.artemchep.keyguard.res.gpg_user_id_replacement_target_missing
import com.artemchep.keyguard.res.gpg_user_ids_failed
import com.artemchep.keyguard.res.gpg_user_ids_private_required
import com.artemchep.keyguard.res.gpg_user_ids_unavailable
import org.jetbrains.compose.resources.StringResource

internal suspend fun TranslatorScope.translateGpgUserIdReplacementMessage(
    resource: StringResource,
): String = if (resource == Res.string.gpg_user_id_replacement_invalid_message_with_limit) {
    translate(resource, OPEN_PGP_MAX_USER_ID_UTF8_BYTES)
} else {
    translate(resource)
}

internal fun gpgUserIdReplacementFailureMessage(
    reason: GpgUserIdMutationError?,
): StringResource = when (reason) {
    GpgUserIdMutationError.EmptyPrivateKey ->
        Res.string.gpg_user_ids_private_required

    GpgUserIdMutationError.TargetNotFound,
    GpgUserIdMutationError.TargetInactive,
    GpgUserIdMutationError.MissingSelfSignature,
    -> Res.string.gpg_user_id_replacement_target_missing

    GpgUserIdMutationError.InvalidNewUserId ->
        Res.string.gpg_user_id_replacement_invalid_message_with_limit

    GpgUserIdMutationError.SameIdentity ->
        Res.string.gpg_user_id_replacement_same_identity_message

    GpgUserIdMutationError.DuplicateIdentity ->
        Res.string.gpg_user_id_replacement_duplicate_message

    GpgUserIdMutationError.PreviouslyRevokedIdentity ->
        Res.string.gpg_user_id_replacement_retired_message

    GpgUserIdMutationError.UnsupportedPlatform ->
        Res.string.gpg_user_ids_unavailable

    GpgUserIdMutationError.UnresolvedRevocationAuthority ->
        Res.string.gpg_key_expiry_unresolved_revocation_message

    GpgUserIdMutationError.UnsupportedSigningHash ->
        Res.string.gpg_key_expiry_unsupported_signing_hash_message

    GpgUserIdMutationError.CertificateRevoked ->
        Res.string.gpg_user_id_mutation_key_revoked_message

    GpgUserIdMutationError.LastUserId,
    GpgUserIdMutationError.MalformedKey,
    GpgUserIdMutationError.FingerprintMismatch,
    GpgUserIdMutationError.AmbiguousPrimary,
    GpgUserIdMutationError.UnsupportedKeyVersion,
    GpgUserIdMutationError.ProtectedSecretKey,
    GpgUserIdMutationError.NonRevocable,
    GpgUserIdMutationError.UnsupportedTemplate,
    GpgUserIdMutationError.PolicyConflict,
    GpgUserIdMutationError.TimeConflict,
    GpgUserIdMutationError.SignatureVerificationFailed,
    GpgUserIdMutationError.MetadataResolutionFailed,
    GpgUserIdMutationError.InternalFailure,
    null,
    -> Res.string.gpg_user_ids_failed
}
