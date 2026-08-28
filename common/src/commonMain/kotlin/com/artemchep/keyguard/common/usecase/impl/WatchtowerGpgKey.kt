package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.ignores
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.canEncryptAt
import com.artemchep.keyguard.common.service.crypto.canSignAt
import com.artemchep.keyguard.common.service.crypto.isExpiredAt
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParser
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentFingerprint
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentPrivateKeyArmored
import com.artemchep.keyguard.common.service.gpgagent.getGpgAgentPublicKeyArmored
import com.artemchep.keyguard.common.service.gpgagent.isUsableAgentKey
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateEvaluator
import com.artemchep.keyguard.common.service.gpgkeyserver.hasUnbackedRevocationEvidence
import com.artemchep.keyguard.common.service.gpgkeyserver.indeterminateVerificationStatus
import com.artemchep.keyguard.common.service.gpgkeyserver.toGpgKeyserverLocalKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.parseGpgAgentMetadataOrNull
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.flowOfTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.Instant

private const val GPG_KEYS_VERSION = "2"
private const val GPG_KEYSERVER_STALE_DAYS = 30L
private const val SECONDS_IN_DAY = 86_400L

class WatchtowerGpgKeyUnusable internal constructor(
    private val policy: GpgWatchtowerPolicy,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.GPG_KEY_UNUSABLE.value

    constructor(directDI: DirectDI) : this(
        policy = directDI.instance(),
    )

    override fun version(): Flow<String> = gpgWatchtowerDailyVersion(GPG_KEYS_VERSION)

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val now = Clock.System.now()
        return ciphers.map { cipher ->
            val value = if (cipher.ignores(DWatchtowerAlertType.GPG_KEY_UNUSABLE)) {
                null
            } else {
                policy.assess(cipher, now)
                    ?.unusableIssues
                    ?.joinToWatchtowerValue()
            }
            WatchtowerClientResult(
                value = value,
                threat = value != null,
                cipher = cipher,
            )
        }
    }
}

class WatchtowerWeakGpgKey internal constructor(
    private val policy: GpgWatchtowerPolicy,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.WEAK_GPG_KEY.value

    constructor(directDI: DirectDI) : this(
        policy = directDI.instance(),
    )

    // Weakness can be expiry-driven, so re-evaluate daily like the sibling
    // WatchtowerGpgKeyUnusable rather than emitting a single static version.
    override fun version(): Flow<String> = gpgWatchtowerDailyVersion(GPG_KEYS_VERSION)

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val now = Clock.System.now()
        return ciphers.map { cipher ->
            val value = if (cipher.ignores(DWatchtowerAlertType.WEAK_GPG_KEY)) {
                null
            } else {
                policy.assess(cipher, now)
                    ?.weakIssues
                    ?.joinToWatchtowerValue()
            }
            WatchtowerClientResult(
                value = value,
                threat = value != null,
                cipher = cipher,
            )
        }
    }
}

class WatchtowerGpgKeyPublishing internal constructor(
    keyserverStateRepository: GpgKeyserverStateRepository,
    getCiphers: GetCiphers,
    evaluator: GpgKeyserverStateEvaluator,
    scope: CoroutineScope,
    clock: Clock = Clock.System,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.GPG_KEY_PUBLISHING.value

    constructor(directDI: DirectDI) : this(
        keyserverStateRepository = directDI.instance(),
        getCiphers = directDI.instance(),
        evaluator = GpgKeyserverStateEvaluator(directDI),
        scope = directDI.instance<WindowCoroutineScope>(),
    )

    // Resolve with the entire vault, not the current processing batch: a designated
    // revoker can be a different cipher, including a cipher with private material.
    // Reassess on relevant data changes and daily for time-dependent policy.
    private val assessments = combine(
        keyserverStateRepository.getAll(),
        getCiphers()
            .map { ciphers -> ciphers.mapNotNull { it.toGpgKeyserverLocalKey() } }
            .distinctUntilChanged(),
        flowOfTime(unit = DurationUnit.DAYS),
    ) { states, localKeys, _ ->
        val now = clock.now()
        val keysByFingerprint = localKeys.groupBy { it.fingerprint }
        val candidates = localKeys.map { it.publicKeyArmored }.distinct().map(::GpgOpenPgpPublicKey)
        states.sortedBy { it.fingerprint }.map { state ->
            currentCoroutineContext().ensureActive()
            val fingerprint = state.fingerprint.normalizeGpgFingerprint()
            val status = runCatchingNonFatal {
                if (state.hasUnbackedRevocationEvidence()) {
                    return@runCatchingNonFatal GpgKeyserverVerificationStatus.REVOKED
                }
                val evidence = evaluator.mergeEvidence(
                    fingerprint = fingerprint,
                    publicCertificates = buildList {
                        state.revocationEvidenceArmored?.let(::add)
                        keysByFingerprint[fingerprint]?.forEach { add(it.publicKeyArmored) }
                    },
                )
                evaluator.evaluate(state, evidence, candidates)
            }.getOrElse { state.indeterminateVerificationStatus() }
            GpgKeyPublishingAssessment(
                fingerprint = fingerprint,
                cipherId = state.cipherId,
                issue = gpgKeyPublishingIssue(status, state.lastCheckedAt, now),
            )
        }
    }.flowOn(dispatcher)
        .distinctUntilChanged()
        .shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
            replay = 1,
        )

    override fun version(): Flow<String> = assessments.map { states ->
        // Only changed outcomes invalidate persisted alerts, not every daily assessment.
        states.joinToString(separator = "|", prefix = "3|") { state ->
            listOf(state.fingerprint, state.cipherId.orEmpty(), state.issue.orEmpty())
                .joinToString(separator = ":")
        }
    }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val states = assessments.first()
        val statesByCipherId = states
            .filter { it.cipherId != null }
            .groupBy { it.cipherId.orEmpty() }
        val statesByFingerprint = states
            .associateBy { it.fingerprint.normalizeGpgFingerprint() }

        return ciphers.map { cipher ->
            val value = if (cipher.ignores(DWatchtowerAlertType.GPG_KEY_PUBLISHING)) {
                null
            } else {
                gpgKeyPublishingIssue(
                    cipher = cipher,
                    statesByCipherId = statesByCipherId,
                    statesByFingerprint = statesByFingerprint,
                )
            }
            WatchtowerClientResult(
                value = value,
                threat = value != null,
                cipher = cipher,
            )
        }
    }
}

internal class GpgWatchtowerPolicy(
    private val parser: GpgPublicKeyParser,
) {
    constructor(directDI: DirectDI) : this(
        parser = directDI.instance(),
    )

    suspend fun assess(
        cipher: DSecret,
        now: Instant,
    ): GpgWatchtowerAssessment? {
        if (!cipher.isGpgWatchtowerTarget()) {
            return null
        }

        val metadata = cipher.parseGpgAgentMetadataOrNull()
        val privateKeyArmored = cipher.getGpgAgentPrivateKeyArmored()
            ?.takeIf { it.isNotBlank() }
        val publicKeyArmored = cipher.getGpgAgentPublicKeyArmored()
            ?.takeIf { it.isNotBlank() }
        if (publicKeyArmored == null) {
            val issues = if (privateKeyArmored != null || metadata != null) {
                listOf(GpgWatchtowerIssue.MISSING_PUBLIC_KEY.code)
            } else {
                emptyList()
            }
            return GpgWatchtowerAssessment(
                fingerprint = cipher.gpgWatchtowerFingerprint(),
                unusableIssues = issues,
            )
        }

        // Parsing includes current policy: a restoration signature can become
        // effective or expire even when the certificate bytes have not changed.
        val keys = when (val result = parser.parse(publicKeyArmored)) {
            is GpgPublicKeyParseResult.Success -> result.keys
            is GpgPublicKeyParseResult.Error -> {
                if (result.reason == GpgPublicKeyParseError.Unsupported) {
                    return null
                }
                return GpgWatchtowerAssessment(
                    fingerprint = cipher.gpgWatchtowerFingerprint(),
                    unusableIssues = listOf(GpgWatchtowerIssue.MALFORMED_PUBLIC_KEY.code),
                )
            }
        }
        if (keys.isEmpty()) {
            return GpgWatchtowerAssessment(
                fingerprint = cipher.gpgWatchtowerFingerprint(),
                unusableIssues = listOf(GpgWatchtowerIssue.MALFORMED_PUBLIC_KEY.code),
            )
        }

        val expectedFingerprint = cipher.gpgWatchtowerFingerprint()
        val key = expectedFingerprint
            ?.let { expected ->
                keys.firstOrNull { key ->
                    key.fingerprint.normalizeGpgFingerprint() == expected
                }
            }
            ?: keys.first()
        if (expectedFingerprint != null &&
            key.fingerprint.normalizeGpgFingerprint() != expectedFingerprint
        ) {
            return GpgWatchtowerAssessment(
                fingerprint = expectedFingerprint,
                unusableIssues = listOf(GpgWatchtowerIssue.FINGERPRINT_MISMATCH.code),
            )
        }

        return GpgWatchtowerAssessment(
            fingerprint = key.fingerprint.normalizeGpgFingerprint(),
            unusableIssues = buildUnusableIssues(
                key = key,
                metadata = metadata,
                privateKeyArmored = privateKeyArmored,
                now = now,
            ),
            weakIssues = buildWeakIssues(key),
        )
    }

    private fun buildUnusableIssues(
        key: GpgPublicKeyInfo,
        metadata: GpgAgentKeyMetadata?,
        privateKeyArmored: String?,
        now: Instant,
    ): List<String> = buildList {
        if (key.revoked) {
            add(GpgWatchtowerIssue.KEY_REVOKED.code)
        }
        val expired = key
            .isExpiredAt(now)
        if (expired) {
            add(GpgWatchtowerIssue.KEY_EXPIRED.code)
        }

        val expectedSign = key.canSign
        val expectedDecrypt = key.canEncrypt

        if (!expectedSign && !expectedDecrypt) {
            add(GpgWatchtowerIssue.NO_CAPABILITY.code)
        }

        if (privateKeyArmored != null) {
            if (metadata == null) {
                add(GpgWatchtowerIssue.MISSING_AGENT_METADATA.code)
            } else {
                val hasAgentKey = metadata.routableAgentKeys.any { it.isUsableAgentKey }
                if (!hasAgentKey) {
                    add(GpgWatchtowerIssue.MISSING_AGENT_KEY.code)
                }
                val publicFingerprints = key.publicPartFingerprints()
                val missingMetadataFingerprints = metadata.certificates
                    .asSequence()
                    .flatMap { it.components.asSequence() }
                    .mapNotNull { component ->
                        component.fingerprint.normalizeGpgFingerprint().takeIf(String::isNotEmpty)
                    }
                    .any { it !in publicFingerprints }
                if (missingMetadataFingerprints) {
                    add(GpgWatchtowerIssue.METADATA_MISMATCH.code)
                }
            }
        }

        val usableSign = key.canSignAt(now)
        val usableDecrypt = key.canEncryptAt(now)
        if (expectedSign && !usableSign) {
            add(GpgWatchtowerIssue.NO_SIGNING_KEY.code)
        }
        if (expectedDecrypt && !usableDecrypt) {
            add(GpgWatchtowerIssue.NO_DECRYPTION_KEY.code)
        }
    }.distinct()

    private fun buildWeakIssues(
        key: GpgPublicKeyInfo,
    ): List<String> = buildList {
        weakIssueFor(
            algorithm = key.algorithm,
            bitStrength = key.bitStrength,
        )?.let(::add)
        key.subKeys.forEach { subKey ->
            weakIssueFor(
                algorithm = subKey.algorithm,
                bitStrength = subKey.bitStrength,
            )?.let(::add)
        }
    }.distinct()

    private fun weakIssueFor(
        algorithm: String,
        bitStrength: Int?,
    ): String? {
        val normalized = algorithm.uppercase()
        return when {
            normalized == "RSA" && bitStrength != null && bitStrength < 2048 ->
                "rsa_$bitStrength"

            normalized == "DSA" ->
                GpgWatchtowerIssue.DSA.code

            normalized == "ELGAMAL" ->
                GpgWatchtowerIssue.ELGAMAL.code

            normalized.startsWith("ALGO_") ->
                "unknown_algorithm_$normalized"

            else -> null
        }
    }
}

internal data class GpgWatchtowerAssessment(
    val fingerprint: String?,
    val unusableIssues: List<String> = emptyList(),
    val weakIssues: List<String> = emptyList(),
)

private enum class GpgWatchtowerIssue(
    val code: String,
) {
    MISSING_PUBLIC_KEY("missing_public_key"),
    MALFORMED_PUBLIC_KEY("malformed_public_key"),
    FINGERPRINT_MISMATCH("fingerprint_mismatch"),
    KEY_REVOKED("revoked"),
    KEY_EXPIRED("expired"),
    NO_CAPABILITY("no_capability"),
    MISSING_AGENT_METADATA("missing_agent_metadata"),
    MISSING_AGENT_KEY("missing_agent_key"),
    METADATA_MISMATCH("metadata_mismatch"),
    NO_SIGNING_KEY("no_signing_key"),
    NO_DECRYPTION_KEY("no_decryption_key"),
    DSA("dsa"),
    ELGAMAL("elgamal"),
}

private fun gpgWatchtowerDailyVersion(
    version: String,
): Flow<String> = flow {
    val seconds = Clock.System.now().epochSeconds
    val days = seconds / SECONDS_IN_DAY
    emit("$version|$days")
}

private data class GpgKeyPublishingAssessment(
    val fingerprint: String,
    val cipherId: String?,
    val issue: String?,
)

private fun gpgKeyPublishingIssue(
    cipher: DSecret,
    statesByCipherId: Map<String, List<GpgKeyPublishingAssessment>>,
    statesByFingerprint: Map<String, GpgKeyPublishingAssessment>,
): String? {
    if (!cipher.isGpgWatchtowerTarget()) {
        return null
    }
    val fingerprint = cipher.toGpgKeyserverLocalKey()?.fingerprint ?: cipher.gpgWatchtowerFingerprint()
    val state = statesByCipherId[cipher.id]
        ?.firstOrNull { state ->
            fingerprint == null ||
                    state.fingerprint.normalizeGpgFingerprint() == fingerprint
        }
        ?: fingerprint?.let(statesByFingerprint::get)
        ?: return "unknown"
    return state.issue
}

private fun gpgKeyPublishingIssue(
    status: GpgKeyserverVerificationStatus,
    lastCheckedAt: Instant?,
    now: Instant,
): String? = when (status) {
    GpgKeyserverVerificationStatus.REVOKED -> "revoked"
    GpgKeyserverVerificationStatus.UNKNOWN -> "unknown"
    GpgKeyserverVerificationStatus.NOT_FOUND -> "not_found"
    GpgKeyserverVerificationStatus.FOUND_UNVERIFIED -> "unverified"
    GpgKeyserverVerificationStatus.VERIFIED -> {
        val ageSeconds = lastCheckedAt?.let { now.epochSeconds - it.epochSeconds }
        "stale".takeIf { ageSeconds == null || ageSeconds >= GPG_KEYSERVER_STALE_DAYS * SECONDS_IN_DAY }
    }
}

internal fun DSecret.isGpgWatchtowerTarget(): Boolean =
    type == DSecret.Type.GpgKey ||
            getGpgAgentPrivateKeyArmored()?.isNotBlank() == true ||
            getGpgAgentPublicKeyArmored()?.isNotBlank() == true ||
            getGpgAgentFingerprint()?.isNotBlank() == true ||
            parseGpgAgentMetadataOrNull()?.certificates?.isNotEmpty() == true

private fun DSecret.gpgWatchtowerFingerprint(): String? =
    getGpgAgentFingerprint()
        ?.normalizeGpgFingerprint()
        ?.takeIf(String::isNotEmpty)
        ?: parseGpgAgentMetadataOrNull()
            ?.certificates
            ?.firstNotNullOfOrNull { certificate ->
                certificate.primaryFingerprint
                    .normalizeGpgFingerprint()
                    .takeIf(String::isNotEmpty)
            }

private fun GpgPublicKeyInfo.publicPartFingerprints(): Set<String> =
    buildSet {
        add(fingerprint.normalizeGpgFingerprint())
        subKeys.forEach { subKey ->
            add(subKey.fingerprint.normalizeGpgFingerprint())
        }
    }


private fun List<String>.joinToWatchtowerValue(): String? =
    takeIf { it.isNotEmpty() }
        ?.joinToString(separator = ",")
