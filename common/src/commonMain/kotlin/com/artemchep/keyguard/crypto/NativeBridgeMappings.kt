package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCertificateMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyComponentMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyComponentRole
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentLegacyDesignatedRevoker
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgRenewalAuthorization
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpAgentOperation
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyComponentRole
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyMaterial
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpMetadataResolution
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPolicyUse
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpRenewalAuthorization
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpRevocationStatus
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType

internal fun NativeOpenPgpMetadataResolution.toDomain(): GpgAgentMetadataResolution {
    val metadata = GpgAgentKeyMetadata(
        certificates = certificates.map { certificate ->
            certificate.index.toCertificateMetadata()
        },
    )
    return GpgAgentMetadataResolution(
        metadata = metadata,
        authorization = GpgAgentAuthorizationSnapshot(
            evaluatedAtEpochSeconds = evaluatedAtEpochSeconds,
            policyRevision = policyRevision,
            keys = toAuthorizedAgentKeys(),
            renewals = certificates
                .asSequence()
                .flatMap { certificate -> certificate.policy.asSequence() }
                .associate { policy ->
                    val renewal = if (
                        policyRevision == GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION &&
                        policy.revocationStatus == NativeOpenPgpRevocationStatus.NOT_REVOKED
                    ) {
                        policy.renewal.toDomain()
                    } else {
                        GpgRenewalAuthorization.NONE
                    }
                    policy.fingerprint.normalizeGpgFingerprint() to renewal
                },
            revocations = certificates
                .asSequence()
                .flatMap { certificate ->
                    certificate.index.components.asSequence().map { component ->
                        val policy = certificate.policy.firstOrNull { policy ->
                            policy.fingerprint == component.fingerprint
                        }
                        val status = if (policyRevision == GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION) {
                            policy?.revocationStatus?.toDomain() ?: GpgRevocationStatus.INDETERMINATE
                        } else {
                            GpgRevocationStatus.INDETERMINATE
                        }
                        component.fingerprint.normalizeGpgFingerprint() to status
                    }
                }
                .toMap(),
        ),
    )
}

private fun NativeOpenPgpMetadataResolution.toAuthorizedAgentKeys(): List<GpgAgentKeyMetadataKey> =
    certificates.flatMap { certificate ->
        certificate.index.components.mapNotNull { component ->
            if (!component.storedSecretMaterial) {
                return@mapNotNull null
            }
            val policy = certificate.policy.firstOrNull { policy ->
                policy.fingerprint == component.fingerprint
            } ?: return@mapNotNull null
            val canAuthorize =
                policyRevision == GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION &&
                    policy.revocationStatus == NativeOpenPgpRevocationStatus.NOT_REVOKED
            val capabilities = buildSet {
                if (
                    canAuthorize && NativeOpenPgpAgentOperation.SIGN in component.agentOperations &&
                    NativeOpenPgpPolicyUse.SIGN_NEW_DATA in policy.allowedNewDataUses
                ) {
                    add("sign")
                }
                if (
                    canAuthorize && NativeOpenPgpAgentOperation.DECRYPT in component.agentOperations &&
                    NativeOpenPgpPolicyUse.ENCRYPT_NEW_DATA in
                    policy.allowedNewDataUses
                ) {
                    add("decrypt")
                }
            }
            val keygrip = component.keygrips.firstOrNull()
                ?: return@mapNotNull null
            GpgAgentKeyMetadataKey(
                keygrip = keygrip,
                fingerprint = component.fingerprint,
                algorithm = component.algorithm,
                capabilities = capabilities,
            )
        }
    }

internal fun NativeOpenPgpCertificateIndex.toDomain(): GpgAgentKeyMetadata =
    GpgAgentKeyMetadata(
        certificates = listOf(toCertificateMetadata()),
    )

/** Decodes the owned armored buffers into strings, zeroizing the buffers afterwards. */
internal inline fun <T> NativeOpenPgpKeyMaterial.useArmoredStrings(
    block: (privateKeyArmored: String, publicKeyArmored: String) -> T,
): T = try {
    block(
        privateKeyArmored.decodeToString(throwOnInvalidSequence = true),
        publicKeyArmored.decodeToString(throwOnInvalidSequence = true),
    )
} finally {
    privateKeyArmored.fill(0)
    publicKeyArmored.fill(0)
}

internal inline fun <T> NativeOpenPgpKeyMaterial.useAsGpgKeyMaterial(
    certificateIndex: NativeOpenPgpCertificateIndex,
    block: (key: GpgKeyMaterial) -> T,
): T = useArmoredStrings { privateKeyArmored, publicKeyArmored ->
    block(
        GpgKeyMaterial(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
            metadata = certificateIndex.toDomain(),
        ),
    )
}

private fun NativeOpenPgpCertificateIndex.toCertificateMetadata(): GpgAgentCertificateMetadata =
    GpgAgentCertificateMetadata(
        primaryFingerprint = primaryFingerprint,
        components = components.map { component ->
            GpgAgentKeyComponentMetadata(
                fingerprint = component.fingerprint,
                role = when (component.role) {
                    NativeOpenPgpKeyComponentRole.PRIMARY -> GpgAgentKeyComponentRole.PRIMARY
                    NativeOpenPgpKeyComponentRole.SUBKEY -> GpgAgentKeyComponentRole.SUBKEY
                },
                publicKeyAlgorithmId = component.publicKeyAlgorithmId,
                algorithm = component.algorithm,
                keygrips = component.keygrips,
                storedSecretMaterial = component.storedSecretMaterial,
                agentOperations = component.agentOperations.mapTo(mutableSetOf()) { operation ->
                    when (operation) {
                        NativeOpenPgpAgentOperation.SIGN -> GpgAgentOperation.SIGN
                        NativeOpenPgpAgentOperation.DECRYPT -> GpgAgentOperation.DECRYPT
                    }
                },
            )
        },
        legacyDesignatedRevokers = legacyDesignatedRevokers.map { revoker ->
            GpgAgentLegacyDesignatedRevoker(
                publicKeyAlgorithmId = revoker.publicKeyAlgorithmId,
                fingerprint = revoker.fingerprint,
                keyClass = revoker.keyClass,
                sensitive = revoker.sensitive,
            )
        },
    )

internal fun NativeOpenPgpRenewalAuthorization.toDomain(): GpgRenewalAuthorization = when (this) {
    NativeOpenPgpRenewalAuthorization.AUTHENTICATED -> GpgRenewalAuthorization.AUTHENTICATED
    NativeOpenPgpRenewalAuthorization.TEMPLATE_ONLY -> GpgRenewalAuthorization.TEMPLATE_ONLY
    NativeOpenPgpRenewalAuthorization.NONE -> GpgRenewalAuthorization.NONE
}

internal fun NativeOpenPgpRevocationStatus.toDomain(): GpgRevocationStatus = when (this) {
    NativeOpenPgpRevocationStatus.NOT_REVOKED -> GpgRevocationStatus.NOT_REVOKED
    NativeOpenPgpRevocationStatus.REVOKED -> GpgRevocationStatus.REVOKED
    NativeOpenPgpRevocationStatus.INDETERMINATE -> GpgRevocationStatus.INDETERMINATE
}

internal fun NativeSshKeyType.toDomain(): KeyPair.Type = when (this) {
    NativeSshKeyType.RSA -> KeyPair.Type.RSA
    NativeSshKeyType.ED25519 -> KeyPair.Type.ED25519
}

internal fun KeyPair.Type.toNative(): NativeSshKeyType = when (this) {
    KeyPair.Type.RSA -> NativeSshKeyType.RSA
    KeyPair.Type.ED25519 -> NativeSshKeyType.ED25519
}
