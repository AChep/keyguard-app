package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.gpgagent.isCanonical
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint

/**
 * Merges a key imported into the editor with the key the editor already
 * holds, rebuilding every derived field from the reconciled material.
 */
class GpgKeyEditorImportReconciler(
    private val materialReconciler: GpgCertificateMaterialReconciler,
    private val metadataResolver: GpgKeyMetadataResolver,
    private val publicKeyParser: GpgPublicKeyParser,
) {
    operator fun invoke(
        existing: GeneratedGpgKey,
        incoming: GeneratedGpgKey,
    ): GpgKeyEditorImportResult = runCatchingNonFatal {
        reconcile(existing, incoming)
    }.getOrElse {
        GpgKeyEditorImportResult.Error(GpgKeyEditorImportError.UnexpectedFailure)
    }

    private fun reconcile(
        existing: GeneratedGpgKey,
        incoming: GeneratedGpgKey,
    ): GpgKeyEditorImportResult {
        val expectedFingerprint = incoming.fingerprint.normalizeGpgFingerprint()
        val existingSecret = existing.privateKeyArmored.nonBlankCertificateMaterialOrNull()
        val incomingSecret = incoming.privateKeyArmored.nonBlankCertificateMaterialOrNull()
        val result = materialReconciler.reconcile(
            expectedPrimaryFingerprint = expectedFingerprint,
            existingPublicCertificate = existing.publicKeyArmored
                .nonBlankCertificateMaterialOrNull(),
            existingSecretCertificate = existingSecret,
            incomingPublicCertificate = incoming.publicKeyArmored
                .nonBlankCertificateMaterialOrNull(),
            incomingSecretCertificate = incomingSecret,
        )
        val success = result.validSuccessOrNull(expectedFingerprint)
        val secretMaterial = success?.localSecretMaterial.nonBlankCertificateMaterialOrNull()
        return when {
            result is GpgCertificateMaterialReconcileResult.Error ->
                GpgKeyEditorImportResult.Error(result.failure.toEditorError())

            success == null ||
                (secretMaterial != null) != (existingSecret != null || incomingSecret != null) ->
                GpgKeyEditorImportResult.Error(GpgKeyEditorImportError.InvalidRebuiltMaterial)

            else -> reconcileSuccessfulImport(success, secretMaterial)
        }
    }

    private fun reconcileSuccessfulImport(
        success: GpgCertificateMaterialReconcileResult.Success,
        secretMaterial: String?,
    ): GpgKeyEditorImportResult {
        val metadata = metadataResolver.resolve(
            privateKeyArmored = secretMaterial,
            publicKeyArmored = success.localPublicMaterial,
            fingerprint = success.primaryFingerprint,
        )?.metadata
            ?.takeIf { it.isCanonical }
        return if (metadata == null) {
            GpgKeyEditorImportResult.Error(GpgKeyEditorImportError.MetadataRebuildFailed)
        } else {
            val publicInfo = publicKeyParser.parsePrimaryKeyInfo(
                armored = success.localPublicMaterial,
                fingerprint = success.primaryFingerprint,
            )
            if (publicInfo == null) {
                GpgKeyEditorImportResult.Error(GpgKeyEditorImportError.InvalidRebuiltMaterial)
            } else {
                GpgKeyEditorImportResult.Success(
                    GeneratedGpgKey(
                        privateKeyArmored = secretMaterial.orEmpty(),
                        publicKeyArmored = success.localPublicMaterial,
                        fingerprint = success.primaryFingerprint,
                        metadata = metadata,
                        userId = publicInfo.userIds.firstOrNull().orEmpty(),
                        typeLabel = publicInfo.algorithm,
                    ),
                )
            }
        }
    }
}

sealed interface GpgKeyEditorImportResult {
    data class Success(
        val key: GeneratedGpgKey,
    ) : GpgKeyEditorImportResult

    data class Error(
        val reason: GpgKeyEditorImportError,
    ) : GpgKeyEditorImportResult
}

enum class GpgKeyEditorImportError {
    ExistingMaterialInvalid,
    IncomingMaterialInvalid,
    UnsupportedMaterial,
    FingerprintMismatch,
    ConflictingSecretMaterial,
    ResourceLimit,
    InvalidRebuiltMaterial,
    MetadataRebuildFailed,
    UnexpectedFailure,
}

// When several inputs are invalid at once, the user is told about the most
// actionable problem first.
private val INVALID_INPUT_PRIORITY = listOf(
    GpgKeyEditorImportError.ResourceLimit,
    GpgKeyEditorImportError.FingerprintMismatch,
    GpgKeyEditorImportError.ExistingMaterialInvalid,
    GpgKeyEditorImportError.UnsupportedMaterial,
    GpgKeyEditorImportError.IncomingMaterialInvalid,
)

private fun GpgCertificateMaterialReconcileFailure.toEditorError(): GpgKeyEditorImportError =
    when (this) {
        is GpgCertificateMaterialReconcileFailure.InvalidInputs -> {
            val slotErrors = listOfNotNull(
                existingPublic?.toEditorError(existingSlot = true),
                existingSecret?.toEditorError(existingSlot = true),
                incomingPublic?.toEditorError(existingSlot = false),
                incomingSecret?.toEditorError(existingSlot = false),
            )
            INVALID_INPUT_PRIORITY.firstOrNull { it in slotErrors }
                ?: GpgKeyEditorImportError.IncomingMaterialInvalid
        }

        is GpgCertificateMaterialReconcileFailure.Pair -> when (reason) {
            GpgCertificateMaterialPairError.MissingMaterial ->
                GpgKeyEditorImportError.IncomingMaterialInvalid

            GpgCertificateMaterialPairError.FingerprintMismatch ->
                GpgKeyEditorImportError.FingerprintMismatch

            GpgCertificateMaterialPairError.ComponentCollision,
            GpgCertificateMaterialPairError.InvalidRebuiltOutput,
            ->
                GpgKeyEditorImportError.InvalidRebuiltMaterial

            GpgCertificateMaterialPairError.ResourceLimit ->
                GpgKeyEditorImportError.ResourceLimit

            GpgCertificateMaterialPairError.ConflictingSecretMaterial ->
                GpgKeyEditorImportError.ConflictingSecretMaterial
        }

        is GpgCertificateMaterialReconcileFailure.Operational -> when (reason) {
            GpgCertificateMaterialOperationalError.ResourceLimit ->
                GpgKeyEditorImportError.ResourceLimit
        }
    }

private fun GpgCertificateMaterialInputError.toEditorError(
    existingSlot: Boolean,
): GpgKeyEditorImportError = when (this) {
    GpgCertificateMaterialInputError.ResourceLimit ->
        GpgKeyEditorImportError.ResourceLimit

    GpgCertificateMaterialInputError.FingerprintMismatch ->
        GpgKeyEditorImportError.FingerprintMismatch

    GpgCertificateMaterialInputError.UnsupportedKeyVersion,
    GpgCertificateMaterialInputError.UnsupportedTskLayout,
    -> if (existingSlot) {
        GpgKeyEditorImportError.ExistingMaterialInvalid
    } else {
        GpgKeyEditorImportError.UnsupportedMaterial
    }

    GpgCertificateMaterialInputError.EmptyCertificate,
    GpgCertificateMaterialInputError.MalformedCertificate,
    GpgCertificateMaterialInputError.ComponentCollision,
    -> if (existingSlot) {
        GpgKeyEditorImportError.ExistingMaterialInvalid
    } else {
        GpgKeyEditorImportError.IncomingMaterialInvalid
    }
}
