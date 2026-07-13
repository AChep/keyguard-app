package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.crypto.extractGpgUserIdEmail
import com.artemchep.keyguard.common.service.crypto.gpgAlgorithmName
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.kodein.di.DirectDI
import kotlin.time.Instant

class GpgPublicKeyParserJvm() : GpgPublicKeyParser {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun parse(
        armored: String,
    ): GpgPublicKeyParseResult {
        if (armored.isBlank()) {
            return GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Empty)
        }
        return runCatching {
            val collection = parseGpgPublicKeyRingCollection(armored)
            val rings = collection.keyRings
                .asSequence()
                .toList()
            val candidateRevocationKeys = rings
                .asSequence()
                .flatMap { ring -> ring.publicKeys.asSequence() }
                .toList()
            val keys = rings.mapNotNull { ring ->
                parseRing(
                    ring = ring,
                    candidateRevocationKeys = candidateRevocationKeys,
                )
            }
            if (keys.isEmpty()) {
                GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed)
            } else {
                GpgPublicKeyParseResult.Success(keys)
            }
        }.getOrElse { error ->
            GpgPublicKeyParseResult.Error(
                when (error) {
                    is GpgUnsupportedKeyVersionException ->
                        GpgPublicKeyParseError.UnsupportedKeyVersion

                    else -> GpgPublicKeyParseError.Malformed
                },
            )
        }
    }

    private fun parseRing(
        ring: PGPPublicKeyRing,
        candidateRevocationKeys: List<PGPPublicKey>,
    ): GpgPublicKeyInfo? {
        val certificate = GpgCertificateInspectorJvm.inspect(
            ring = ring,
            candidateRevocationKeys = candidateRevocationKeys,
        )
            ?: return null
        val primary = certificate.primary
        val primaryKey = primary.publicKey
        val certificateRevoked = primary.revoked
        val userIds = certificate.verifiedUserIds
        val subKeys = certificate.subkeys
            .asSequence()
            .filter { it.authenticated }
            .map { subkey ->
                val sub = subkey.publicKey
                val flags = subkey.keyFlags
                GpgPublicSubKeyInfo(
                    fingerprint = sub.fingerprintHex(),
                    keygrip = GpgKeygripCalculatorJvm.calculate(sub),
                    keyId = sub.keyID.gpgKeyIdHex(),
                    algorithm = gpgAlgorithmName(sub.algorithm),
                    bitStrength = sub.bitStrength.takeIf { it > 0 },
                    canSign = !certificateRevoked &&
                        !subkey.revoked &&
                        (flags?.canSign() ?: sub.isSigningKey()) &&
                        subkey.signingCrossCertified,
                    canEncrypt = !certificateRevoked &&
                        !subkey.revoked &&
                        (flags?.canEncrypt() ?: sub.isEncryptionKey),
                    revoked = subkey.revoked,
                    createdAt = sub.creationTime?.let { Instant.fromEpochMilliseconds(it.time) },
                    expiresAt = subkey.expiresAt(),
                )
            }
            .toList()
        val primaryCanSign = primary.authenticated &&
            !primary.revoked &&
            (primary.keyFlags?.canSign() ?: primaryKey.isSigningKey())
        val certificateCanEncrypt = !certificateRevoked &&
            certificate.authenticatedKeys.any { key ->
                !key.revoked &&
                (key.keyFlags?.canEncrypt() ?: key.publicKey.isEncryptionKey)
            }
        return GpgPublicKeyInfo(
            fingerprint = primaryKey.fingerprintHex(),
            keygrip = GpgKeygripCalculatorJvm.calculate(primaryKey),
            keyId = primaryKey.keyID.gpgKeyIdHex(),
            algorithm = gpgAlgorithmName(primaryKey.algorithm),
            bitStrength = primaryKey.bitStrength.takeIf { it > 0 },
            userIds = userIds,
            emails = userIds.mapNotNull(::extractGpgUserIdEmail).distinct(),
            createdAt = primaryKey.creationTime?.let { Instant.fromEpochMilliseconds(it.time) },
            expiresAt = primary.expiresAt(),
            revoked = primary.revoked,
            canSign = primaryCanSign || subKeys.any { it.canSign },
            canEncrypt = certificateCanEncrypt,
            publicKeyArmored = ring.armored(),
            subKeys = subKeys,
        )
    }

    private fun Int.canSign(): Boolean = this and KeyFlags.SIGN_DATA != 0

    private fun Int.canEncrypt(): Boolean =
        this and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0

    private fun GpgVerifiedCertificateKeyJvm.expiresAt(): Instant? {
        if (validSeconds <= 0L) {
            return null
        }
        val creationTime = publicKey.creationTime ?: return null
        return Instant.fromEpochMilliseconds(creationTime.time + validSeconds * 1000L)
    }
}
