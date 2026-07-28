package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.coroutines.flow.Flow

/**
 * Provides decoded ciphers together with their database revision. The
 * snapshots are shared so consumers do not each decode the same cipher payload
 * independently.
 */
internal interface GetCipherSnapshots : () -> Flow<List<CipherSnapshot>>

internal data class CipherSnapshot(
    val cipher: DSecret,
    val key: CipherSnapshotKey,
)

internal data class CipherSnapshotKey(
    val cipherId: String,
    val dataRevCounter: Long,
)
