package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.gpgagent.isCanonical
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.CipherSnapshot
import com.artemchep.keyguard.common.usecase.CipherSnapshotKey
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal data class CipherSnapshotLoadStats(
    val cipherCount: Int,
    val changedCipherCount: Int,
    val loadedPayloadCount: Int,
    val isFullLoad: Boolean,
)

internal data class CipherSnapshotLoadResult(
    val snapshots: List<CipherSnapshot>,
    val snapshotsByCipherId: Map<String, CipherSnapshot>,
    val stats: CipherSnapshotLoadStats,
)

internal class CipherSnapshotLoader(
    private val dbDispatcher: CoroutineDispatcher,
    private val getPasswordStrength: GetPasswordStrength,
    private val gpgKeyMetadataResolver: GpgKeyMetadataResolver? = null,
    private val logRepository: LogRepository? = null,
) {
    private companion object {

        // A full payload scan becomes preferable once
        // at least one quarter of the ciphers changed.
        const val FULL_LOAD_CHANGED_RATIO_DENOMINATOR = 4
        // Keep small updates incremental even when the changed-cipher ratio is high.
        const val FULL_LOAD_MIN_CHANGED_CIPHER_COUNT = 64

        // Leave headroom under SQLite's traditional 999 bind-parameter limit.
        const val MAX_INCREMENTAL_CHANGED_CIPHER_COUNT = 900
    }

    suspend fun load(
        db: Database,
        previousSnapshotsByCipherId: Map<String, CipherSnapshot>,
    ): CipherSnapshotLoadResult {
        if (previousSnapshotsByCipherId.isEmpty()) {
            return loadInitial(db)
        }

        val payloadLoad = withContext(dbDispatcher) {
            db.transactionWithResult {
                val keys = db.cipherQueries
                    .getCipherSnapshotKeys()
                    .executeAsList()
                    .map { row ->
                        CipherSnapshotKey(
                            cipherId = row.cipherId,
                            dataRevCounter = row.dataRevCounter,
                        )
                    }
                val changedKeys = keys.filter { key ->
                    previousSnapshotsByCipherId[key.cipherId]?.key != key
                }
                val isFullLoad = shouldLoadAllPayloads(
                    cipherCount = keys.size,
                    changedCipherCount = changedKeys.size,
                )
                val rows: List<LoadedCipherPayload> = when {
                    keys.isEmpty() || changedKeys.isEmpty() -> emptyList()
                    isFullLoad -> db.cipherQueries
                        .getCipherSnapshots(::loadedCipherPayload)
                        .executeAsList()

                    else -> db.cipherQueries
                        .getCipherSnapshotsByIds(
                            cipherIds = changedKeys.map(CipherSnapshotKey::cipherId),
                            mapper = ::loadedCipherPayload,
                        )
                        .executeAsList()
                }
                CipherPayloadLoad(
                    keys = keys,
                    cipherCount = keys.size,
                    changedCipherCount = changedKeys.size,
                    rows = rows,
                    isFullLoad = isFullLoad,
                )
            }
        }

        val canonicalRows = canonicalizeGpgMetadata(db, payloadLoad.rows)
        val loadedPayloadsByKey = canonicalRows
            .associateBy(LoadedCipherPayload::key)
        val nextSnapshotsByCipherId = HashMap<String, CipherSnapshot>(payloadLoad.cipherCount)
        val snapshots = payloadLoad.keys.map { key ->
            val snapshot = previousSnapshotsByCipherId[key.cipherId]
                ?.takeIf { previous -> previous.key == key }
                ?: CipherSnapshot(
                    cipher = loadedPayloadsByKey.getValue(key).data
                        .toDomain(getPasswordStrength),
                    key = key,
                )
            nextSnapshotsByCipherId[key.cipherId] = snapshot
            snapshot
        }
        return CipherSnapshotLoadResult(
            snapshots = snapshots,
            snapshotsByCipherId = nextSnapshotsByCipherId,
            stats = CipherSnapshotLoadStats(
                cipherCount = payloadLoad.cipherCount,
                changedCipherCount = payloadLoad.changedCipherCount,
                loadedPayloadCount = payloadLoad.rows.size,
                isFullLoad = payloadLoad.isFullLoad,
            ),
        )
    }

    private suspend fun loadInitial(db: Database): CipherSnapshotLoadResult {
        val rows = withContext(dbDispatcher) {
            db.cipherQueries
                .getCipherSnapshots()
                .executeAsList()
        }
        val loadedRows = rows.map { row ->
            val key = CipherSnapshotKey(
                cipherId = row.cipherId,
                dataRevCounter = row.dataRevCounter,
            )
            LoadedCipherPayload(key = key, data = row.data_)
        }
        val canonicalRows = canonicalizeGpgMetadata(db, loadedRows)
        val nextSnapshotsByCipherId = HashMap<String, CipherSnapshot>(canonicalRows.size)
        val snapshots = canonicalRows.map { row ->
            val key = row.key
            CipherSnapshot(
                cipher = row.data.toDomain(getPasswordStrength),
                key = key,
            ).also { snapshot ->
                nextSnapshotsByCipherId[key.cipherId] = snapshot
            }
        }
        return CipherSnapshotLoadResult(
            snapshots = snapshots,
            snapshotsByCipherId = nextSnapshotsByCipherId,
            stats = CipherSnapshotLoadStats(
                cipherCount = canonicalRows.size,
                changedCipherCount = canonicalRows.size,
                loadedPayloadCount = canonicalRows.size,
                isFullLoad = true,
            ),
        )
    }

    private suspend fun canonicalizeGpgMetadata(
        db: Database,
        rows: List<LoadedCipherPayload>,
    ): List<LoadedCipherPayload> {
        val resolver = gpgKeyMetadataResolver
            ?: return rows.map(::sanitizeGpgMetadata)
        val updates = mutableListOf<Pair<BitwardenCipher, BitwardenCipher>>()
        val canonicalRows = rows.map { row ->
            val source = row.data
            val gpgKey = source.gpgKey ?: return@map row
            if (gpgKey.metadata?.isCanonical == true) {
                return@map row
            }
            val metadata = runCatchingNonFatal {
                resolver.resolve(
                    privateKeyArmored = gpgKey.privateKeyArmored,
                    publicKeyArmored = gpgKey.publicKeyArmored,
                    fingerprint = gpgKey.fingerprint,
                )?.metadata?.takeIf { it.isCanonical }
            }.getOrElse { e ->
                if (e !is Exception) throw e
                logRepository?.post(
                    tag = "GpgMetadataCanonicalizer",
                    message = "Failed to resolve GPG metadata for '${source.cipherId}': ${e.message}",
                    level = LogLevel.ERROR,
                )
                null
            }
            val canonical = source.copy(
                gpgKey = gpgKey.copy(metadata = metadata),
            )
            if (metadata != null) {
                updates += source to canonical
            }
            row.copy(data = canonical)
        }
        if (updates.isNotEmpty()) {
            persistCanonicalGpgMetadata(db, updates)
        }
        return canonicalRows
    }

    private fun sanitizeGpgMetadata(row: LoadedCipherPayload): LoadedCipherPayload =
        row.data.gpgKey?.let { gpgKey ->
            if (gpgKey.metadata != null && !gpgKey.metadata.isCanonical) {
                row.copy(
                    data = row.data.copy(gpgKey = gpgKey.copy(metadata = null)),
                )
            } else {
                row
            }
        } ?: row

    private suspend fun persistCanonicalGpgMetadata(
        db: Database,
        updates: List<Pair<BitwardenCipher, BitwardenCipher>>,
    ) = withContext(dbDispatcher) {
        db.cipherQueries.transaction {
            updates.forEach { (source, canonical) ->
                val current = db.cipherQueries
                    .getByCipherId(source.cipherId)
                    .executeAsOneOrNull()
                    ?: return@forEach
                val currentGpgKey = current.data_.gpgKey
                    ?: return@forEach
                if (
                    currentGpgKey.metadata?.isCanonical == true ||
                    currentGpgKey.copy(metadata = null) != source.gpgKey?.copy(metadata = null)
                ) {
                    return@forEach
                }
                db.cipherQueries.insert(
                    cipherId = current.cipherId,
                    accountId = current.accountId,
                    folderId = current.folderId,
                    data = current.data_.copy(gpgKey = canonical.gpgKey),
                    updatedAt = current.updatedAt,
                )
            }
        }
    }

    private fun shouldLoadAllPayloads(
        cipherCount: Int,
        changedCipherCount: Int,
    ): Boolean = when {
        cipherCount == 0 -> false
        changedCipherCount > MAX_INCREMENTAL_CHANGED_CIPHER_COUNT -> true
        changedCipherCount < FULL_LOAD_MIN_CHANGED_CIPHER_COUNT -> false
        else -> changedCipherCount * FULL_LOAD_CHANGED_RATIO_DENOMINATOR >= cipherCount
    }

    private fun loadedCipherPayload(
        cipherId: String,
        data: BitwardenCipher,
        dataRevCounter: Long,
    ) = LoadedCipherPayload(
        key = CipherSnapshotKey(
            cipherId = cipherId,
            dataRevCounter = dataRevCounter,
        ),
        data = data,
    )

    private data class CipherPayloadLoad(
        val keys: List<CipherSnapshotKey>,
        val cipherCount: Int,
        val changedCipherCount: Int,
        val rows: List<LoadedCipherPayload>,
        val isFullLoad: Boolean,
    )

    private data class LoadedCipherPayload(
        val key: CipherSnapshotKey,
        val data: BitwardenCipher,
    )
}
