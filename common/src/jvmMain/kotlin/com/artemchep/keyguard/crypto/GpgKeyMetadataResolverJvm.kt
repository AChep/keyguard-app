package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.gpgAlgorithmName
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.kodein.di.DirectDI
import java.io.ByteArrayInputStream

class GpgKeyMetadataResolverJvm() : GpgKeyMetadataResolver {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
    ): GpgAgentKeyMetadata? {
        ensureBouncyCastleProvider()
        return privateKeyArmored
            ?.takeIf { it.isNotBlank() }
            ?.let { parsePrivateKeyMetadataOrNull(it, fingerprint) }
            ?: publicKeyArmored
                ?.takeIf { it.isNotBlank() }
                ?.let { parsePublicKeyMetadataOrNull(it, fingerprint) }
    }

    private fun parsePrivateKeyMetadataOrNull(
        armored: String,
        fingerprint: String?,
    ): GpgAgentKeyMetadata? = runCatching {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val rings = collection.keyRings
            .asSequence()
            .selectSecretRingsByFingerprint(fingerprint)
        rings.secretRingsToMetadataOrNull()
    }.getOrNull()

    private fun parsePublicKeyMetadataOrNull(
        armored: String,
        fingerprint: String?,
    ): GpgAgentKeyMetadata? = runCatching {
        val collection = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val rings = collection.keyRings
            .asSequence()
            .selectPublicRingsByFingerprint(fingerprint)
        rings.publicRingsToMetadataOrNull()
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

    private fun Sequence<PGPSecretKeyRing>.secretRingsToMetadataOrNull(): GpgAgentKeyMetadata? =
        flatMap { ring -> ring.publicKeysWithPrimaryMarker() }
            .publicKeysToMetadataOrNull()

    private fun Sequence<PGPPublicKeyRing>.publicRingsToMetadataOrNull(): GpgAgentKeyMetadata? =
        flatMap { ring -> ring.publicKeysWithPrimaryMarker() }
            .publicKeysToMetadataOrNull()

    private fun PGPSecretKeyRing.publicKeysWithPrimaryMarker(): Sequence<Pair<PGPPublicKey, Boolean>> {
        val primaryKeyId = publicKey?.keyID
        return publicKeys
            .asSequence()
            .map { key -> key to (key.isMasterKey || key.keyID == primaryKeyId) }
    }

    private fun PGPPublicKeyRing.publicKeysWithPrimaryMarker(): Sequence<Pair<PGPPublicKey, Boolean>> {
        val primaryKeyId = publicKey?.keyID
        return publicKeys
            .asSequence()
            .map { key -> key to (key.isMasterKey || key.keyID == primaryKeyId) }
    }

    private fun Sequence<Pair<PGPPublicKey, Boolean>>.publicKeysToMetadataOrNull(): GpgAgentKeyMetadata? {
        val keys = mapNotNull { (key, primary) ->
            key.toMetadataKeyOrNull(includeWithoutCapabilities = primary)
        }
            .toList()
        return GpgAgentKeyMetadata(
            version = 1,
            keys = keys,
        ).takeIf { keys.isNotEmpty() }
    }

    private fun PGPPublicKey.toMetadataKeyOrNull(
        includeWithoutCapabilities: Boolean,
    ): GpgAgentKeyMetadataKey? {
        val capabilities = capabilities()
        if (capabilities.isEmpty() && !includeWithoutCapabilities) {
            return null
        }
        val keygrip = runCatching {
            GpgKeygripCalculatorJvm.calculate(this)
        }.getOrNull() ?: return null
        return GpgAgentKeyMetadataKey(
            keygrip = keygrip,
            fingerprint = fingerprintHex(),
            algorithm = gpgAlgorithmName(algorithm),
            capabilities = capabilities,
        )
    }

    private fun PGPPublicKey.capabilities(): Set<String> = buildSet {
        val flags = keyFlags()
        if (flags.canSign() || (flags == 0 && isSigningKey())) {
            add("sign")
        }
        if (flags.canEncrypt() || isEncryptionKey) {
            add("decrypt")
        }
    }

    private fun PGPPublicKey.keyFlags(): Int {
        var flags = 0
        val signatures = this.signatures
        while (signatures.hasNext()) {
            val signature = signatures.next() ?: continue
            val hashed = signature.hashedSubPackets ?: continue
            flags = flags or hashed.keyFlags
        }
        return flags
    }

    private fun Int.canSign(): Boolean =
        this and KeyFlags.SIGN_DATA != 0

    private fun Int.canEncrypt(): Boolean =
        this and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0
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
