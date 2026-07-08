package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverVerifyStatus
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.VerifyGpgPublicKeyRequest
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentFingerprint
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.VerifyGpgPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock

class VerifyGpgPublicKeyImpl(
    private val getCiphers: GetCiphers,
    private val getGpgKeyserverConfig: GetGpgKeyserverConfig,
    private val keyserverClient: GpgKeyserverClient,
    private val keyserverStateRepository: GpgKeyserverStateRepository,
    private val parser: GpgPublicKeyParser,
) : VerifyGpgPublicKey {
    constructor(
        directDI: DirectDI,
    ) : this(
        getCiphers = directDI.instance(),
        getGpgKeyserverConfig = directDI.instance(),
        keyserverClient = directDI.instance(),
        keyserverStateRepository = directDI.instance(),
        parser = directDI.instance(),
    )

    override fun invoke(
        request: VerifyGpgPublicKeyRequest,
    ): IO<GpgKeyserverVerifyStatus> = ioEffect(Dispatchers.Default) {
        val (cipher, publicKeyArmored) = getCiphers.requireGpgPublicKeyCipher(
            cipherId = request.cipherId,
            accountId = request.accountId,
        )

        val key = parsePublicKey(cipher, publicKeyArmored)
        val fingerprint = key.fingerprint.normalizeGpgFingerprint()
            .takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("The public GPG key does not contain a fingerprint.")
        val emails = key.emails
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()

        val config = getGpgKeyserverConfig().first()
        val perEmail = mutableMapOf<String, GpgKeyserverVerificationStatus>()
        emails.forEach { email ->
            // The by-email VKS throttle now lives in the keyserver client so it
            // applies to every caller and survives vault unlocks.
            val results = keyserverClient.getByEmail(
                email = email,
                config = config,
            ).bind()
            perEmail[email] = gpgKeyserverEmailVerificationStatus(
                fingerprint = fingerprint,
                results = results,
            )
        }
        val fingerprintResult = if (
            perEmail.values.any {
                it == GpgKeyserverVerificationStatus.VERIFIED ||
                        it == GpgKeyserverVerificationStatus.REVOKED
            }
        ) {
            null
        } else {
            keyserverClient.getByFingerprint(
                fingerprint = fingerprint,
                config = config,
            ).bind()
        }
        val overall = gpgKeyserverAggregateVerificationStatus(
            perEmail = perEmail.values,
            fingerprintResult = fingerprintResult,
        )

        val now = Clock.System.now()
        val current = keyserverStateRepository
            .getByFingerprint(fingerprint)
            .first()
        keyserverStateRepository.put(
            DGpgKeyserverState(
                fingerprint = fingerprint,
                cipherId = cipher.id,
                verificationStatus = overall,
                lastCheckedAt = now,
                lastRefreshedAt = current?.lastRefreshedAt,
                sourceKeyserver = fingerprintResult?.sourceKeyserver
                    ?: current?.sourceKeyserver
                    ?: config.url,
            ),
        ).bind()

        GpgKeyserverVerifyStatus(
            fingerprint = fingerprint,
            overall = overall,
            perEmail = perEmail,
        )
    }

    private fun parsePublicKey(
        cipher: DSecret,
        publicKeyArmored: String,
    ): GpgPublicKeyInfo {
        val expectedFingerprint = cipher.getGpgAgentFingerprint()
            ?.normalizeGpgFingerprint()
            ?.takeIf { it.isNotEmpty() }
        val keys = when (val result = parser.parse(publicKeyArmored)) {
            is GpgPublicKeyParseResult.Success -> result.keys
            is GpgPublicKeyParseResult.Error -> when (result.reason) {
                GpgPublicKeyParseError.Empty -> throw IllegalStateException(
                    "The item does not contain a public GPG key.",
                )

                GpgPublicKeyParseError.Malformed -> throw IllegalStateException(
                    "The stored public GPG key is malformed.",
                )

                GpgPublicKeyParseError.Unsupported -> throw UnsupportedOperationException(
                    "OpenPGP public key parsing is not supported on this platform.",
                )
            }
        }
        if (keys.isEmpty()) {
            throw IllegalStateException("The stored public GPG key does not contain any keys.")
        }

        return expectedFingerprint
            ?.let { expected ->
                keys.firstOrNull { key ->
                    key.fingerprint.normalizeGpgFingerprint() == expected
                }
            }
            ?: keys.first()
    }
}

internal fun gpgKeyserverEmailVerificationStatus(
    fingerprint: String,
    results: List<DGpgKeyserverResult>,
): GpgKeyserverVerificationStatus {
    val normalizedFingerprint = fingerprint.normalizeGpgFingerprint()
    val match = results.firstOrNull { result ->
        result.fingerprint.normalizeGpgFingerprint() == normalizedFingerprint
    } ?: return GpgKeyserverVerificationStatus.NOT_FOUND
    return if (match.revoked) {
        GpgKeyserverVerificationStatus.REVOKED
    } else {
        GpgKeyserverVerificationStatus.VERIFIED
    }
}

internal fun gpgKeyserverAggregateVerificationStatus(
    perEmail: Collection<GpgKeyserverVerificationStatus>,
    fingerprintResult: DGpgKeyserverResult?,
): GpgKeyserverVerificationStatus = when {
    perEmail.any { it == GpgKeyserverVerificationStatus.REVOKED } ->
        GpgKeyserverVerificationStatus.REVOKED

    perEmail.any { it == GpgKeyserverVerificationStatus.VERIFIED } ->
        GpgKeyserverVerificationStatus.VERIFIED

    fingerprintResult?.revoked == true ->
        GpgKeyserverVerificationStatus.REVOKED

    fingerprintResult != null ->
        GpgKeyserverVerificationStatus.FOUND_UNVERIFIED

    else ->
        GpgKeyserverVerificationStatus.NOT_FOUND
}
