package com.artemchep.keyguard.provider.bitwarden.usecase

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

        val loadedPayloadsByKey = payloadLoad.rows
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
        val nextSnapshotsByCipherId = HashMap<String, CipherSnapshot>(rows.size)
        val snapshots = rows.map { row ->
            val key = CipherSnapshotKey(
                cipherId = row.cipherId,
                dataRevCounter = row.dataRevCounter,
            )
            CipherSnapshot(
                cipher = row.data_.toDomain(getPasswordStrength),
                key = key,
            ).also { snapshot ->
                nextSnapshotsByCipherId[row.cipherId] = snapshot
            }
        }
        return CipherSnapshotLoadResult(
            snapshots = snapshots,
            snapshotsByCipherId = nextSnapshotsByCipherId,
            stats = CipherSnapshotLoadStats(
                cipherCount = rows.size,
                changedCipherCount = rows.size,
                loadedPayloadCount = rows.size,
                isFullLoad = true,
            ),
        )
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
