package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.crypto.GpgKeyGenerator
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.api.jcajce.JcaOpenPGPKeyGenerator
import org.kodein.di.DirectDI
import java.security.Security

class GpgKeyGeneratorJvm() : GpgKeyGenerator {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun generate(
        config: GpgKeyConfig,
    ): GeneratedGpgKey {
        val userId = config.userId.trim()
        require(userId.isNotEmpty()) {
            "GPG user ID must not be blank."
        }

        ensureBouncyCastleProvider()
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            ?: BouncyCastleProvider()
        val generator = JcaOpenPGPKeyGenerator(
            PublicKeyPacket.VERSION_4,
            provider,
        )
        val key = when (config) {
            // gpg 2.x v4 keys use the LEGACY algorithm ids — EdDSA-legacy (22) over
            // Ed25519 and ECDH-legacy (18) over Curve25519 — NOT the RFC 9580 native
            // Ed25519 (27) / X25519 (25) ids that JcaOpenPGPKeyGenerator.ed25519x25519Key
            // emits. gpg cannot even import a v4 secret key carrying the native ids
            // ("secret key info missing"), and the agent's ECDH decrypt path only
            // implements legacy algorithm 18. So we build the same layout gpg does for a
            // v4 key with the legacy generators: an EdDSA-legacy certify primary, an
            // EdDSA-legacy signing subkey, and an ECDH-legacy Curve25519 encryption
            // subkey. BC attaches the correct key-flags subpackets to each.
            is GpgKeyConfig.Modern -> generator
                .withPrimaryKey { it.generateLegacyEd25519KeyPair() }
                .addSigningSubkey { it.generateLegacyEd25519KeyPair() }
                .addEncryptionSubkey { it.generateLegacyX25519KeyPair() }
                .addUserId(userId)
                .build()

            is GpgKeyConfig.Rsa -> generator
                .compositeRSAKey(config.length.size, userId)
                .build()
        }
        val secretKeyRing = key.pgpSecretKeyRing
        val publicKeyRing = secretKeyRing.toCertificate()
        val publicKeyArmored = publicKeyRing.armored()
        val privateKeyArmored = secretKeyRing.armored()
        val primary = publicKeyRing.publicKey
        val fingerprint = primary.fingerprintHex()
        val metadata = GpgKeyMetadataResolverJvm().resolve(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
        ) ?: GpgAgentKeyMetadata()
        return GeneratedGpgKey(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
            metadata = metadata,
            userId = userId,
            typeLabel = config.type.title,
        )
    }
}
