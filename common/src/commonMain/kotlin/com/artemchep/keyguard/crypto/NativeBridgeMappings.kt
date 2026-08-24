package com.artemchep.keyguard.crypto

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
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpAgentOperation
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpCertificateIndex
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpKeyComponentRole
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpMetadataResolution
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPolicyUse
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpRenewalAuthorization
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType

internal fun NativeOpenPgpMetadataResolution.toDomain(): GpgAgentMetadataResolution {
    val metadata = GpgAgentKeyMetadata(
        certificates = certificates.map { certificate ->
            certificate.index.toCertificateMetadata()
        },
    )
    val keys = certificates.flatMap { certificate ->
        certificate.index.components.mapNotNull { component ->
            if (!component.storedSecretMaterial) {
                return@mapNotNull null
            }
            val policy = certificate.policy.firstOrNull { policy ->
                policy.fingerprint == component.fingerprint
            } ?: return@mapNotNull null
            val capabilities = buildSet {
                if (
                    NativeOpenPgpAgentOperation.SIGN in component.agentOperations &&
                    NativeOpenPgpPolicyUse.SIGN_NEW_DATA in policy.allowedNewDataUses
                ) {
                    add("sign")
                }
                if (
                    NativeOpenPgpAgentOperation.DECRYPT in component.agentOperations &&
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
    return GpgAgentMetadataResolution(
        metadata = metadata,
        authorization = GpgAgentAuthorizationSnapshot(
            evaluatedAtEpochSeconds = evaluatedAtEpochSeconds,
            policyRevision = policyRevision,
            keys = keys,
            renewals = certificates
                .asSequence()
                .flatMap { certificate -> certificate.policy.asSequence() }
                .associate { policy ->
                    policy.fingerprint.normalizeGpgFingerprint() to policy.renewal.toDomain()
                },
        ),
    )
}

internal fun NativeOpenPgpCertificateIndex.toDomain(): GpgAgentKeyMetadata =
    GpgAgentKeyMetadata(
        certificates = listOf(toCertificateMetadata()),
    )

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

internal fun NativeSshKeyType.toDomain(): KeyPair.Type = when (this) {
    NativeSshKeyType.RSA -> KeyPair.Type.RSA
    NativeSshKeyType.ED25519 -> KeyPair.Type.ED25519
}

internal fun KeyPair.Type.toNative(): NativeSshKeyType = when (this) {
    KeyPair.Type.RSA -> NativeSshKeyType.RSA
    KeyPair.Type.ED25519 -> NativeSshKeyType.ED25519
}
