package com.artemchep.keyguard.feature.gpgkey.revocation

import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_unresolved_revocation_message
import com.artemchep.keyguard.res.gpg_key_expiry_unsupported_signing_hash_message
import com.artemchep.keyguard.res.gpg_user_id_mutation_key_revoked_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_failed_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_last_identity_message
import com.artemchep.keyguard.res.gpg_user_id_revocation_private_key_required
import com.artemchep.keyguard.res.gpg_user_id_revocation_target_missing
import com.artemchep.keyguard.res.gpg_user_id_revocation_unavailable
import org.jetbrains.compose.resources.StringResource

internal fun gpgUserIdRevocationFailureMessage(
    reason: GpgUserIdMutationError?,
): StringResource = when (reason) {
    GpgUserIdMutationError.EmptyPrivateKey ->
        Res.string.gpg_user_id_revocation_private_key_required

    GpgUserIdMutationError.TargetNotFound,
    GpgUserIdMutationError.MissingSelfSignature,
    -> Res.string.gpg_user_id_revocation_target_missing

    GpgUserIdMutationError.LastUserId ->
        Res.string.gpg_user_id_revocation_last_identity_message

    GpgUserIdMutationError.UnsupportedPlatform ->
        Res.string.gpg_user_id_revocation_unavailable

    GpgUserIdMutationError.UnresolvedRevocationAuthority ->
        Res.string.gpg_key_expiry_unresolved_revocation_message

    GpgUserIdMutationError.UnsupportedSigningHash ->
        Res.string.gpg_key_expiry_unsupported_signing_hash_message

    GpgUserIdMutationError.CertificateRevoked ->
        Res.string.gpg_user_id_mutation_key_revoked_message

    GpgUserIdMutationError.TargetInactive,
    GpgUserIdMutationError.InvalidNewUserId,
    GpgUserIdMutationError.SameIdentity,
    GpgUserIdMutationError.DuplicateIdentity,
    GpgUserIdMutationError.PreviouslyRevokedIdentity,
    GpgUserIdMutationError.AmbiguousPrimary,
    GpgUserIdMutationError.UnsupportedTemplate,
    GpgUserIdMutationError.PolicyConflict,
    GpgUserIdMutationError.MalformedKey,
    GpgUserIdMutationError.FingerprintMismatch,
    GpgUserIdMutationError.UnsupportedKeyVersion,
    GpgUserIdMutationError.ProtectedSecretKey,
    GpgUserIdMutationError.NonRevocable,
    GpgUserIdMutationError.TimeConflict,
    GpgUserIdMutationError.SignatureVerificationFailed,
    GpgUserIdMutationError.MetadataResolutionFailed,
    GpgUserIdMutationError.InternalFailure,
    null,
    -> Res.string.gpg_user_id_revocation_failed_message
}
