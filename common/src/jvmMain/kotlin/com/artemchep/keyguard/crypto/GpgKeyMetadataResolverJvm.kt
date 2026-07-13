package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.gpgAlgorithmName
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.kodein.di.DirectDI

class GpgKeyMetadataResolverJvm() : GpgKeyMetadataResolver {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgAgentKeyMetadata? {
        val externalRevocationKeys = candidateRevocationKeys.parseGpgPublicKeyCandidates()
        return privateKeyArmored
            ?.takeIf { it.isNotBlank() }
            ?.let { parsePrivateKeyMetadataOrNull(it, fingerprint, externalRevocationKeys) }
            ?: publicKeyArmored
                ?.takeIf { it.isNotBlank() }
                ?.let { parsePublicKeyMetadataOrNull(it, fingerprint, externalRevocationKeys) }
    }

    private fun parsePrivateKeyMetadataOrNull(
        armored: String,
        fingerprint: String?,
        externalRevocationKeys: List<PGPPublicKey>,
    ): GpgAgentKeyMetadata? = runCatching {
        val collection = parseGpgSecretKeyRingCollection(armored)
        val allRings = collection.keyRings
            .asSequence()
            .toList()
        val candidateRevocationKeys = buildList {
            allRings.forEach { ring -> ring.publicKeys.asSequence().forEach(::add) }
            addAll(externalRevocationKeys)
        }
        val rings = allRings
            .asSequence()
            .selectSecretRingsByFingerprint(fingerprint)
        rings.secretRingsToMetadataOrNull(candidateRevocationKeys)
    }.getOrNull()

    private fun parsePublicKeyMetadataOrNull(
        armored: String,
        fingerprint: String?,
        externalRevocationKeys: List<PGPPublicKey>,
    ): GpgAgentKeyMetadata? = runCatching {
        val collection = parseGpgPublicKeyRingCollection(armored)
        val allRings = collection.keyRings
            .asSequence()
            .toList()
        val candidateRevocationKeys = buildList {
            allRings.forEach { ring -> ring.publicKeys.asSequence().forEach(::add) }
            addAll(externalRevocationKeys)
        }
        val rings = allRings
            .asSequence()
            .selectPublicRingsByFingerprint(fingerprint)
        rings.publicRingsToMetadataOrNull(candidateRevocationKeys)
    }.getOrNull()

    private fun Sequence<PGPSecretKeyRing>.selectSecretRingsByFingerprint(
        fingerprint: String?,
    ): Sequence<PGPSecretKeyRing> {
        val normalized = fingerprint
            ?.normalizeGpgFingerprint()
            ?.takeIf { it.isNotEmpty() }
            ?: return this
        return filter { ring ->
            ring.publicKeys
                .asSequence()
                .any { key -> key.fingerprintHex().normalizeGpgFingerprint() == normalized }
        }
    }

    private fun Sequence<PGPPublicKeyRing>.selectPublicRingsByFingerprint(
        fingerprint: String?,
    ): Sequence<PGPPublicKeyRing> {
        val normalized = fingerprint
            ?.normalizeGpgFingerprint()
            ?.takeIf { it.isNotEmpty() }
            ?: return this
        return filter { ring ->
            ring.publicKeys
                .asSequence()
                .any { key -> key.fingerprintHex().normalizeGpgFingerprint() == normalized }
        }
    }

    private fun Sequence<PGPSecretKeyRing>.secretRingsToMetadataOrNull(
        candidateRevocationKeys: List<PGPPublicKey>,
    ): GpgAgentKeyMetadata? =
        mapNotNull { ring ->
            GpgCertificateInspectorJvm.inspect(
                ring = ring.toCertificate(),
                candidateRevocationKeys = candidateRevocationKeys,
            )
        }
            .flatMap { inspector -> inspector.keysWithPrimaryMarker() }
            .publicKeysToMetadataOrNull()

    private fun Sequence<PGPPublicKeyRing>.publicRingsToMetadataOrNull(
        candidateRevocationKeys: List<PGPPublicKey>,
    ): GpgAgentKeyMetadata? =
        mapNotNull { ring ->
            GpgCertificateInspectorJvm.inspect(
                ring = ring,
                candidateRevocationKeys = candidateRevocationKeys,
            )
        }
            .flatMap { inspector -> inspector.keysWithPrimaryMarker() }
            .publicKeysToMetadataOrNull()

    private fun GpgCertificateInspectorJvm.keysWithPrimaryMarker(): Sequence<MetadataKey> =
        authenticatedKeys
        .asSequence()
        .map { key ->
            MetadataKey(
                key = key,
                primary = key === primary,
                certificateRevoked = primary.revoked,
            )
        }

    private fun Sequence<MetadataKey>.publicKeysToMetadataOrNull(): GpgAgentKeyMetadata? {
        val keys = mapNotNull { inspected ->
            inspected.key.toMetadataKeyOrNull(
                isPrimary = inspected.primary,
                includeWithoutCapabilities = inspected.primary,
                certificateRevoked = inspected.certificateRevoked,
            )
        }
            .toList()
        return GpgAgentKeyMetadata(
            version = 1,
            keys = keys,
        ).takeIf { keys.isNotEmpty() }
    }

    private fun GpgVerifiedCertificateKeyJvm.toMetadataKeyOrNull(
        isPrimary: Boolean,
        includeWithoutCapabilities: Boolean,
        certificateRevoked: Boolean,
    ): GpgAgentKeyMetadataKey? {
        val capabilities = capabilities(
            isPrimary = isPrimary,
            certificateRevoked = certificateRevoked,
        )
        if (capabilities.isEmpty() && !includeWithoutCapabilities) {
            return null
        }
        val keygrip = runCatching {
            GpgKeygripCalculatorJvm.calculate(publicKey)
        }.getOrNull() ?: return null
        return GpgAgentKeyMetadataKey(
            keygrip = keygrip,
            fingerprint = publicKey.fingerprintHex(),
            algorithm = gpgAlgorithmName(publicKey.algorithm),
            capabilities = capabilities,
        )
    }

    private fun GpgVerifiedCertificateKeyJvm.capabilities(
        isPrimary: Boolean,
        certificateRevoked: Boolean,
    ): Set<String> = if (certificateRevoked || revoked) {
        emptySet()
    } else {
        buildSet {
            val signingCapability = keyFlags?.canSign() ?: publicKey.isSigningKey()
            if (signingCapability && (isPrimary || signingCrossCertified)) {
                add("sign")
            }
            if (keyFlags?.canEncrypt() ?: publicKey.isEncryptionKey) {
                add("decrypt")
            }
        }
    }

    private fun Int.canSign(): Boolean =
        this and KeyFlags.SIGN_DATA != 0

    private fun Int.canEncrypt(): Boolean =
        this and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0

    private data class MetadataKey(
        val key: GpgVerifiedCertificateKeyJvm,
        val primary: Boolean,
        val certificateRevoked: Boolean,
    )
}

internal fun PGPPublicKey.isSigningKey(): Boolean =
    when (algorithm) {
        PublicKeyAlgorithmTags.RSA_GENERAL,
        PublicKeyAlgorithmTags.RSA_SIGN,
        PublicKeyAlgorithmTags.DSA,
        PublicKeyAlgorithmTags.ECDSA,
        PublicKeyAlgorithmTags.EDDSA,
        PublicKeyAlgorithmTags.EDDSA_LEGACY,
        PublicKeyAlgorithmTags.Ed25519,
        PublicKeyAlgorithmTags.Ed448,
            -> true

        else -> false
    }
