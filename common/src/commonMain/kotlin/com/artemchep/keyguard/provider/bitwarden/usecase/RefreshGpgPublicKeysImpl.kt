package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverSubKey
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysRequest
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.model.toGpgAgentKeyMetadataOrNull
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.gpgKeyserverRefreshFingerprintOrNull
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.RefreshGpgPublicKeys
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.fields
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock

class RefreshGpgPublicKeysImpl(
    private val getCiphers: GetCiphers,
    private val getGpgKeyserverConfig: GetGpgKeyserverConfig,
    private val putGpgKeyserverLastRefresh: PutGpgKeyserverLastRefresh,
    private val keyserverClient: GpgKeyserverClient,
    private val keyserverStateRepository: GpgKeyserverStateRepository,
    private val modifyCipherById: ModifyCipherById,
) : RefreshGpgPublicKeys {
    constructor(
        directDI: DirectDI,
    ) : this(
        getCiphers = directDI.instance(),
        getGpgKeyserverConfig = directDI.instance(),
        putGpgKeyserverLastRefresh = directDI.instance(),
        keyserverClient = directDI.instance(),
        keyserverStateRepository = directDI.instance(),
        modifyCipherById = directDI.instance(),
    )

    override fun invoke(
        request: RefreshGpgPublicKeysRequest,
    ): IO<RefreshGpgPublicKeysResult> = ioEffect(Dispatchers.Default) {
        if (request.cipherIds.isEmpty()) {
            return@ioEffect RefreshGpgPublicKeysResult(
                refreshed = 0,
                notFound = 0,
                skipped = 0,
            )
        }

        val ciphers = getCiphers()
            .first()
            .filter { cipher ->
                cipher.id in request.cipherIds &&
                        request.accountId?.let { it == cipher.accountId } != false
            }
        val targets = ciphers.mapNotNull(::toRefreshTarget)
        val skipped = request.cipherIds.size - targets.map { it.cipherId }.toSet().size
        if (targets.isEmpty()) {
            return@ioEffect RefreshGpgPublicKeysResult(
                refreshed = 0,
                notFound = 0,
                skipped = skipped,
            )
        }

        val config = getGpgKeyserverConfig().first()
        val resultsByFingerprint = targets
            .map { it.fingerprint }
            .distinct()
            .associateWith { fingerprint ->
                keyserverClient.getByFingerprint(
                    fingerprint = fingerprint,
                    config = config,
                ).bind()
            }
        val outcomes = targets.map { target ->
            RefreshOutcome(
                target = target,
                result = resultsByFingerprint[target.fingerprint],
            )
        }
        val foundOutcomes = outcomes.filter { it.result != null }

        if (foundOutcomes.isNotEmpty()) {
            val foundByCipherId = foundOutcomes.associateBy { it.target.cipherId }
            modifyCipherById(
                cipherIds = foundByCipherId.keys,
            ) { model ->
                val outcome = foundByCipherId[model.cipherId]
                    ?: return@modifyCipherById model
                val result = requireNotNull(outcome.result)
                val gpgKey = model.data_.gpgKey.withGpgKeyserverRefresh(
                    result = result,
                )
                model.copy(
                    data_ = model.data_.copy(gpgKey = gpgKey),
                )
            }.bind()
        }

        val now = Clock.System.now()
        outcomes.forEach { outcome ->
            val result = outcome.result
            val fingerprint = result?.fingerprint?.normalizeGpgFingerprint()
                ?: outcome.target.fingerprint
            val current = keyserverStateRepository
                .getByFingerprint(fingerprint)
                .first()
            val status = when {
                result == null -> GpgKeyserverVerificationStatus.NOT_FOUND
                result.revoked -> GpgKeyserverVerificationStatus.REVOKED
                current?.verificationStatus == GpgKeyserverVerificationStatus.VERIFIED ->
                    GpgKeyserverVerificationStatus.VERIFIED

                else -> GpgKeyserverVerificationStatus.FOUND_UNVERIFIED
            }
            keyserverStateRepository.put(
                DGpgKeyserverState(
                    fingerprint = fingerprint,
                    cipherId = outcome.target.cipherId,
                    verificationStatus = status,
                    lastCheckedAt = now,
                    lastRefreshedAt = if (result != null) {
                        now
                    } else {
                        current?.lastRefreshedAt
                    },
                    sourceKeyserver = result?.sourceKeyserver
                        ?: current?.sourceKeyserver
                        ?: config.url,
                ),
            ).bind()
        }
        putGpgKeyserverLastRefresh(now).bind()

        RefreshGpgPublicKeysResult(
            refreshed = foundOutcomes.size,
            notFound = outcomes.size - foundOutcomes.size,
            skipped = skipped,
        )
    }

    private fun toRefreshTarget(
        cipher: DSecret,
    ): RefreshTarget? {
        val fingerprint = cipher.gpgKeyserverRefreshFingerprintOrNull()
            ?: return null
        return RefreshTarget(
            cipherId = cipher.id,
            fingerprint = fingerprint,
        )
    }
}

internal fun BitwardenCipher.GpgKey?.withGpgKeyserverRefresh(
    result: DGpgKeyserverResult,
): BitwardenCipher.GpgKey {
    val publicKeyArmored = result.publicKeyArmored
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Keyserver did not return the public GPG key.")
    val fingerprint = result.fingerprint.normalizeGpgFingerprint()
    val current = this ?: BitwardenCipher.GpgKey()
    val metadata = current.metadata?.mergeWith(result)
    return current.copy(
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadata = metadata
            ?: result.toGpgAgentKeyMetadataOrNull()
            ?: current.metadata,
    )
}

private fun GpgAgentKeyMetadata.mergeWith(
    result: DGpgKeyserverResult,
): GpgAgentKeyMetadata {
    val partsByFingerprint = result.keyParts()
        .associateBy { it.fingerprint.normalizeGpgFingerprint() }
    return copy(
        keys = keys.map { key ->
            val part = partsByFingerprint[key.fingerprint.normalizeGpgFingerprint()]
                ?: return@map key
            key.copy(
                keygrip = key.keygrip.ifBlank {
                    part.keygrip.orEmpty()
                },
                fingerprint = part.fingerprint.normalizeGpgFingerprint(),
                algorithm = part.algorithm ?: key.algorithm,
                capabilities = part.capabilities.takeIf { it.isNotEmpty() }
                    ?: key.capabilities,
            )
        },
    )
}

private fun DGpgKeyserverResult.keyParts(): List<ParsedPublicKeyPart> =
    listOf(
        ParsedPublicKeyPart(
            keygrip = keygrip,
            fingerprint = fingerprint,
            algorithm = algorithm,
            capabilities = capabilities(canSign, canEncrypt),
        ),
    ) + subKeys.map { subKey ->
        ParsedPublicKeyPart(
            keygrip = subKey.keygrip,
            fingerprint = subKey.fingerprint,
            algorithm = subKey.algorithm,
            capabilities = capabilities(subKey.canSign, subKey.canEncrypt),
        )
    }

private fun capabilities(
    canSign: Boolean,
    canEncrypt: Boolean,
): Set<String> = buildSet {
    if (canSign) {
        add("sign")
    }
    if (canEncrypt) {
        add("encrypt")
    }
}

private data class RefreshTarget(
    val cipherId: String,
    val fingerprint: String,
)

private data class RefreshOutcome(
    val target: RefreshTarget,
    val result: DGpgKeyserverResult?,
)

private data class ParsedPublicKeyPart(
    val keygrip: String?,
    val fingerprint: String,
    val algorithm: String?,
    val capabilities: Set<String>,
)
