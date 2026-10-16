package com.artemchep.keyguard.common.service.crypto

enum class GpgUserIdMutationError {
    EmptyPrivateKey,
    MalformedKey,
    FingerprintMismatch,
    TargetNotFound,
    LastUserId,
    TargetInactive,
    InvalidNewUserId,
    SameIdentity,
    DuplicateIdentity,
    PreviouslyRevokedIdentity,
    AmbiguousPrimary,
    UnsupportedKeyVersion,
    ProtectedSecretKey,
    MissingSelfSignature,
    NonRevocable,
    UnsupportedTemplate,

    /** Authenticated certificate policy is ambiguous and cannot be resolved by retrying later. */
    PolicyConflict,

    /** Timestamp ordering failed. Retry only when the result includes an exact retry time. */
    TimeConflict,
    SignatureVerificationFailed,
    MetadataResolutionFailed,
    InternalFailure,
    CertificateRevoked,
    UnresolvedRevocationAuthority,
    UnsupportedSigningHash,
    UnsupportedPlatform,
}
