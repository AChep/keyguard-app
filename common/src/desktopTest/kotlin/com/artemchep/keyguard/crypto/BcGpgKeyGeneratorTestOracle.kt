package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.crypto.GPG_KEY_EXPIRATION_MAX_INSTANT
import com.artemchep.keyguard.common.service.crypto.GpgKeyGenerator
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.resolve
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SignatureSubpacketTags
import org.bouncycastle.openpgp.api.SignatureParameters
import org.bouncycastle.openpgp.api.jcajce.JcaOpenPGPKeyGenerator
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.Date
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

class BcGpgKeyGeneratorTestOracle(
    private val metadataResolver: GpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
    private val now: () -> Instant = { Clock.System.now() },
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : GpgKeyGenerator {
    constructor(
        directDI: DirectDI,
    ) : this(
        metadataResolver = directDI.instance(),
    )

    override fun generate(
        config: GpgKeyConfig,
    ): GeneratedGpgKey {
        val userId = config.userId.trim()
        require(userId.isNotEmpty()) {
            "GPG user ID must not be blank."
        }

        val provider = gpgBouncyCastleProvider
        // OpenPGP key/signature timestamps have one-second precision. Supplying the
        // creation time explicitly lets relative expiry presets resolve against the
        // exact timestamp encoded into every generated component.
        val creationInstant = Instant.fromEpochSeconds(now().epochSeconds)
        val creationTime = Date(creationInstant.toEpochMilliseconds())
        val expiresAt = config.expiry.resolve(
            creationTime = creationInstant,
            timeZone = timeZone(),
        )
        val expirationSeconds = expiresAt?.let { target ->
            expirationSeconds(
                creationTime = creationInstant,
                target = target,
            )
        }
        val expirySignatureParameters = expirationSignatureParameters(expirationSeconds)
        val generator = JcaOpenPGPKeyGenerator(
            PublicKeyPacket.VERSION_4,
            creationTime,
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
                .withPrimaryKey(
                    { it.generateLegacyEd25519KeyPair() },
                    expirySignatureParameters,
                )
                .addSigningSubkey(
                    { it.generateLegacyEd25519KeyPair() },
                    expirySignatureParameters,
                    null,
                )
                .addEncryptionSubkey(
                    { it.generateLegacyX25519KeyPair() },
                    expirySignatureParameters,
                )
                .addUserId(userId, expirySignatureParameters)
                .build()

            is GpgKeyConfig.Rsa -> generator
                .withPrimaryKey(
                    { it.generateRsaKeyPair(config.length.size) },
                    expirySignatureParameters,
                )
                .addSigningSubkey(
                    { it.generateRsaKeyPair(config.length.size) },
                    expirySignatureParameters,
                    null,
                )
                .addEncryptionSubkey(
                    { it.generateRsaKeyPair(config.length.size) },
                    expirySignatureParameters,
                )
                .addUserId(userId, expirySignatureParameters)
                .build()
        }
        val secretKeyRing = key.pgpSecretKeyRing
        val publicKeyRing = secretKeyRing.toCertificate()
        val publicKeyArmored = publicKeyRing.armored()
        val privateKeyArmored = secretKeyRing.armored()
        val primary = publicKeyRing.publicKey
        val fingerprint = primary.fingerprintHex()
        val metadata = metadataResolver.resolve(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
        ) ?: error("Could not resolve metadata for a generated GPG key.")
        return GeneratedGpgKey(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
            metadata = metadata,
            userId = userId,
            typeLabel = config.type.title,
        )
    }

    private fun expirationSignatureParameters(
        expirationSeconds: Long?,
    ): SignatureParameters.Callback = SignatureParameters.Callback.Util.modifyHashedSubpackets { packets ->
        packets.removePacketsOfType(SignatureSubpacketTags.KEY_EXPIRE_TIME)
        expirationSeconds?.let { seconds ->
            packets.setKeyExpirationTime(true, seconds)
        }
        packets
    }

    private fun expirationSeconds(
        creationTime: Instant,
        target: Instant,
    ): Long {
        require(target <= GPG_KEY_EXPIRATION_MAX_INSTANT) {
            "GPG key expiry exceeds the supported protocol range."
        }
        val seconds = target.epochSeconds - creationTime.epochSeconds
        require(seconds in 1L..UInt.MAX_VALUE.toLong()) {
            "GPG key expiry must be after creation and fit the OpenPGP duration field."
        }
        return seconds
    }
}
