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
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.kodein.di.DirectDI
import java.io.ByteArrayInputStream
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
            val collection = PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
                JcaKeyFingerprintCalculator(),
            )
            val keys = collection.keyRings
                .asSequence()
                .mapNotNull(::parseRing)
                .toList()
            if (keys.isEmpty()) {
                GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed)
            } else {
                GpgPublicKeyParseResult.Success(keys)
            }
        }.getOrElse {
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed)
        }
    }

    private fun parseRing(
        ring: PGPPublicKeyRing,
    ): GpgPublicKeyInfo? {
        val primary = ring.publicKeys
            .asSequence()
            .firstOrNull { it.isMasterKey }
            ?: ring.publicKey
            ?: return null
        val userIds = primary.userIDs
            .asSequence()
            .toList()
        val subKeys = ring.publicKeys
            .asSequence()
            .filterNot { it.keyID == primary.keyID }
            .map { sub ->
                val flags = sub.keyFlags()
                GpgPublicSubKeyInfo(
                    fingerprint = sub.fingerprintHex(),
                    keygrip = GpgKeygripCalculatorJvm.calculate(sub),
                    keyId = sub.keyID.gpgKeyIdHex(),
                    algorithm = gpgAlgorithmName(sub.algorithm),
                    bitStrength = sub.bitStrength.takeIf { it > 0 },
                    canSign = flags.canSign(),
                    canEncrypt = flags.canEncrypt() || sub.isEncryptionKey,
                    revoked = sub.hasRevocation(),
                    expiresAt = sub.expiresAt(),
                )
            }
            .toList()
        val primaryFlags = primary.keyFlags()
        return GpgPublicKeyInfo(
            fingerprint = primary.fingerprintHex(),
            keygrip = GpgKeygripCalculatorJvm.calculate(primary),
            keyId = primary.keyID.gpgKeyIdHex(),
            algorithm = gpgAlgorithmName(primary.algorithm),
            bitStrength = primary.bitStrength.takeIf { it > 0 },
            userIds = userIds,
            emails = userIds.mapNotNull(::extractGpgUserIdEmail).distinct(),
            createdAt = primary.creationTime?.let { Instant.fromEpochMilliseconds(it.time) },
            expiresAt = primary.expiresAt(),
            revoked = primary.hasRevocation(),
            canSign = primaryFlags.canSign() || subKeys.any { it.canSign },
            canEncrypt = primaryFlags.canEncrypt() ||
                    ring.publicKeys.asSequence().any { it.isEncryptionKey },
            publicKeyArmored = ring.armored(),
            subKeys = subKeys,
        )
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

    private fun Int.canSign(): Boolean = this and KeyFlags.SIGN_DATA != 0

    private fun Int.canEncrypt(): Boolean =
        this and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0

    private fun PGPPublicKey.expiresAt(): Instant? {
        val validSeconds = this.validSeconds
        if (validSeconds <= 0L) {
            return null
        }
        val creationTime = this.creationTime ?: return null
        return Instant.fromEpochMilliseconds(creationTime.time + validSeconds * 1000L)
    }

}
