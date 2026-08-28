package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverVerifyStatus
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.VerifyGpgPublicKeyRequest
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentFingerprint
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRecorder
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
    keyserverStateRepository: GpgKeyserverStateRepository,
    private val parser: GpgPublicKeyParser,
    metadataResolver: GpgKeyMetadataResolver,
    reconciler: GpgCertificateMaterialReconciler,
) : VerifyGpgPublicKey {
    private val stateRecorder = GpgKeyserverStateRecorder(
        repository = keyserverStateRepository,
        reconciler = reconciler,
        resolver = metadataResolver,
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        getCiphers = directDI.instance(),
        getGpgKeyserverConfig = directDI.instance(),
        keyserverClient = directDI.instance(),
        keyserverStateRepository = directDI.instance(),
        parser = directDI.instance(),
        metadataResolver = directDI.instance(),
        reconciler = directDI.instance(),
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
        val matchingResults = mutableListOf<DGpgKeyserverResult>()
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
            matchingResults += results.filter {
                it.fingerprint.normalizeGpgFingerprint() == fingerprint
            }
        }
        val fingerprintResult = if (
            matchingResults.any { !it.publicKeyArmored.isNullOrBlank() }
        ) {
            null
        } else {
            keyserverClient.getByFingerprint(
                fingerprint = fingerprint,
                config = config,
            ).bind()
        }
        fingerprintResult?.let { result ->
            check(result.fingerprint.normalizeGpgFingerprint() == fingerprint)
            check(!result.publicKeyArmored.isNullOrBlank()) {
                "Keyserver did not return the public GPG key."
            }
            matchingResults += result
        }
        check(matchingResults.isEmpty() || matchingResults.any { !it.publicKeyArmored.isNullOrBlank() }) {
            "Could not obtain signed GPG key evidence from the keyserver."
        }
        val publicationStatus = gpgKeyserverAggregateVerificationStatus(
            perEmail = perEmail.values,
            fingerprintResult = fingerprintResult,
        )

        val now = Clock.System.now()
        val saved = stateRecorder.record(
            fingerprint = fingerprint,
            cipherIds = setOf(cipher.id),
            publicCertificates = listOf(publicKeyArmored) + matchingResults.mapNotNull {
                it.publicKeyArmored?.takeIf(String::isNotBlank)
            },
            publicationStatus = publicationStatus,
            sourceKeyserver = fingerprintResult?.sourceKeyserver
                ?: matchingResults.firstOrNull()?.sourceKeyserver
                ?: config.url,
            checkedAt = now,
            refreshed = false,
        ).bind()
        val overall = saved.verificationStatus

        GpgKeyserverVerifyStatus(
            fingerprint = fingerprint,
            overall = overall,
            perEmail = perEmail.mapValues { (_, status) ->
                if (status == GpgKeyserverVerificationStatus.VERIFIED &&
                    overall in setOf(GpgKeyserverVerificationStatus.REVOKED, GpgKeyserverVerificationStatus.UNKNOWN)
                ) overall else status
            },
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

                GpgPublicKeyParseError.UnsupportedKeyVersion ->
                    throw UnsupportedOperationException(
                        "OpenPGP V2/V3 keys are not supported.",
                    )

                GpgPublicKeyParseError.MultipleCertificates -> throw IllegalStateException(
                    "The item stores more than one secret GPG key.",
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
    results.firstOrNull { result ->
        result.fingerprint.normalizeGpgFingerprint() == normalizedFingerprint
    } ?: return GpgKeyserverVerificationStatus.NOT_FOUND
    // An HKP index is publication evidence, not a signed revocation verdict.
    return GpgKeyserverVerificationStatus.VERIFIED
}

internal fun gpgKeyserverAggregateVerificationStatus(
    perEmail: Collection<GpgKeyserverVerificationStatus>,
    fingerprintResult: DGpgKeyserverResult?,
): GpgKeyserverVerificationStatus = when {
    perEmail.any { it == GpgKeyserverVerificationStatus.VERIFIED } ->
        GpgKeyserverVerificationStatus.VERIFIED

    fingerprintResult != null ->
        GpgKeyserverVerificationStatus.FOUND_UNVERIFIED

    else ->
        GpgKeyserverVerificationStatus.NOT_FOUND
}
