package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2

internal data class FingerprintEpochs(
    val config: Long = 0L,
    val org: Long = 0L,
    val locale: Long = 0L,
)

internal data class CachedRow(
    /** [fingerprintOf] the source at build time; stamped by the cache. */
    val fingerprint: Long,
    val item: VaultItem2.Item,
)

/**
 * The result of one [VaultRowCache.reconcile] pass.
 */
internal data class ReconcileResult(
    /** One row per input secret, in input order. */
    val rows: List<CachedRow>,
    /** Ids rebuilt in this pass (new to the cache, or fingerprint changed). */
    val changedIds: Set<String>,
    /** `changedIds.size`, kept separate for cheap thresholds / logging. */
    val rebuiltCount: Int,
)

internal class VaultRowCache(
    private val rebuild: (DSecret) -> CachedRow,
    /** Injectable for tests; defaults to the repo's plain println idiom. */
    private val log: (String) -> Unit = { message -> println(message) },
) {
    companion object {
        const val REBUILD_WARN_MIN_SIZE = 8
        const val REBUILD_WARN_FRACTION = 0.5
    }

    private var cache: MutableMap<String, CachedRow> = HashMap()

    private var lastEpochs: FingerprintEpochs? = null

    fun reconcile(
        secrets: List<DSecret>,
        epochs: FingerprintEpochs,
    ): ReconcileResult {
        val epochsUnchanged = lastEpochs == epochs
        lastEpochs = epochs

        val next = HashMap<String, CachedRow>(secrets.size * 2)
        val rows = ArrayList<CachedRow>(secrets.size)
        val changedIds = mutableSetOf<String>()
        // The number of rebuilt rows that were already cached (a changed
        // cipher) as opposed to new to the cache; only those participate
        // in the instability diagnostic.
        var rebuiltExistingCount = 0
        for (secret in secrets) {
            val fingerprint = fingerprintOf(secret, epochs)
            val cached = cache[secret.id]
            val row = if (cached != null && cached.fingerprint == fingerprint) {
                cached
            } else {
                if (cached != null) rebuiltExistingCount += 1
                changedIds += secret.id
                rebuild(secret)
                    .copy(fingerprint = fingerprint)
            }
            next[secret.id] = row
            rows += row
        }
        cache = next

        if (
            epochsUnchanged &&
            secrets.size >= REBUILD_WARN_MIN_SIZE &&
            rebuiltExistingCount > secrets.size * REBUILD_WARN_FRACTION
        ) {
            log(
                "[W]/VaultRowCache: rebuilt $rebuiltExistingCount of ${secrets.size} " +
                        "previously cached rows with unchanged epochs; a fingerprint " +
                        "input is likely unstable, see vaultRowFingerprintInputs!",
            )
        }
        return ReconcileResult(
            rows = rows,
            changedIds = changedIds,
            rebuiltCount = changedIds.size,
        )
    }
}

internal val vaultRowFingerprintInputs: List<Pair<String, (DSecret) -> Any?>> = listOf(
    "id" to { it.id },
    "accountId" to { it.accountId },
    "organizationId" to { it.organizationId },
    "folderId" to { it.folderId },
    "collectionIds" to { it.collectionIds.sorted() },
    "name" to { it.name },
    "revisionDate" to { it.revisionDate },
    "createdDate" to { it.createdDate },
    "deletedDate" to { it.deletedDate },
    "archivedDate" to { it.archivedDate },
    "favorite" to { it.favorite },
    "reprompt" to { it.reprompt },
    "synced" to { it.synced },
    "hasError" to { it.hasError },
    "tags" to { it.tags },
    "uris" to { secret -> secret.uris.map { it.uri } },
    "customIcon" to { it.customIcon },
    "notes" to { it.notes },
    "type" to { it.type.name },
    "attachments" to { secret ->
        secret.attachments
            .map { "${it.id}|${it.fileName()}|${it.fileSize()}" }
    },
    "login.username" to { it.login?.username },
    "login.password" to { it.login?.password },
    "login.passwordStrength" to { it.login?.passwordStrength },
    "login.passwordRevisionDate" to { it.login?.passwordRevisionDate },
    "login.totp" to { it.login?.totp?.raw },
    "login.fido2Credentials" to { secret ->
        secret.login?.fido2Credentials
            ?.map { "${it.credentialId}|${it.rpId}|${it.userDisplayName}|${it.userName}" }
    },
    "card.brand" to { it.card?.brand },
    "card.number" to { it.card?.number },
    "identity.firstName" to { it.identity?.firstName },
    "sshKey.fingerprint" to { it.sshKey?.fingerprint },
    "gpgKey.fingerprint" to { it.gpgKey?.fingerprint },
)

private const val FNV64_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325

private const val FNV64_PRIME = 0x100000001b3L

/** Marker mixed in for `null` extractor values, distinct from any real string. */
private const val NULL_MARKER = "\u0000<null>"

internal fun fingerprintOf(
    secret: DSecret,
    epochs: FingerprintEpochs,
): Long {
    var hash = FNV64_OFFSET_BASIS
    for ((name, extractor) in vaultRowFingerprintInputs) {
        hash = hash.mix(name)
        val value = extractor(secret)
        hash = hash.mix(value?.toString() ?: NULL_MARKER)
    }
    // Fold the epochs through the same mix instead of a plain XOR: two
    // epochs bumping in lock-step would cancel each other out under XOR.
    hash = hash.mix(epochs.config)
    hash = hash.mix(epochs.org)
    hash = hash.mix(epochs.locale)
    return hash
}

private fun Long.mix(value: String): Long {
    var hash = this
    for (char in value) {
        hash = (hash xor char.code.toLong()) * FNV64_PRIME
    }
    // Terminate the field so adjacent fields can not
    // shift content into each other.
    hash = (hash xor FIELD_TERMINATOR) * FNV64_PRIME
    return hash
}

private fun Long.mix(value: Long): Long {
    var hash = this
    var v = value
    repeat(Long.SIZE_BYTES) {
        hash = (hash xor (v and BYTE_MASK)) * FNV64_PRIME
        v = v ushr Byte.SIZE_BITS
    }
    return hash
}

private const val FIELD_TERMINATOR = 0xFFL

private const val BYTE_MASK = 0xFFL
