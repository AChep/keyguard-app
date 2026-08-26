package com.artemchep.keyguard.common.service.crypto

sealed interface GpgCertificateMaterialReconcileResult {
    data class Success(
        val localPublicMaterial: String,
        val localSecretMaterial: String?,
        val transferablePublicCertificate: String?,
        val transferableSecretKey: String?,
        val primaryFingerprint: String,
        val contributions: GpgCertificateMaterialContributions,
        val withheldReasons: Set<GpgCertificateMaterialWithheldReason>,
    ) : GpgCertificateMaterialReconcileResult

    data class Error(
        val failure: GpgCertificateMaterialReconcileFailure,
    ) : GpgCertificateMaterialReconcileResult
}

data class GpgCertificateMaterialContributions(
    val existingPublic: GpgCertificateMaterialInputContribution,
    val incomingPublic: GpgCertificateMaterialInputContribution,
    val existingSecret: GpgCertificateMaterialInputContribution,
    val incomingSecret: GpgCertificateMaterialInputContribution,
)

data class GpgCertificateMaterialInputContribution(
    val present: Boolean,
    val uniquePublicEvidence: Boolean,
    val uniqueSecretCapability: Boolean,
)

enum class GpgCertificateMaterialWithheldReason {
    NoTransferablePublicCertificate,
    LocalPublicEvidence,
    SecretMaterialNotTransferable,
}

sealed interface GpgCertificateMaterialReconcileFailure {
    data class InvalidInputs(
        val existingPublic: GpgCertificateMaterialInputError?,
        val incomingPublic: GpgCertificateMaterialInputError?,
        val existingSecret: GpgCertificateMaterialInputError?,
        val incomingSecret: GpgCertificateMaterialInputError?,
    ) : GpgCertificateMaterialReconcileFailure

    data class Pair(
        val reason: GpgCertificateMaterialPairError,
    ) : GpgCertificateMaterialReconcileFailure

    data class Operational(
        val reason: GpgCertificateMaterialOperationalError,
    ) : GpgCertificateMaterialReconcileFailure
}

enum class GpgCertificateMaterialInputError {
    EmptyCertificate,
    MalformedCertificate,
    UnsupportedKeyVersion,
    FingerprintMismatch,
    ComponentCollision,
    ResourceLimit,
    UnsupportedTskLayout,
}

enum class GpgCertificateMaterialPairError {
    MissingMaterial,
    FingerprintMismatch,
    ComponentCollision,
    ResourceLimit,
    InvalidRebuiltOutput,
    ConflictingSecretMaterial,
}

enum class GpgCertificateMaterialOperationalError {
    ResourceLimit,
}
