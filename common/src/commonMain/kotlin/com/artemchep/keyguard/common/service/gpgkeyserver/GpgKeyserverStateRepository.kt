package com.artemchep.keyguard.common.service.gpgkeyserver

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import kotlinx.coroutines.flow.Flow

/**
 * Durable store for per-key keyserver metadata (verification status, last
 * checked/refreshed timestamps, source keyserver). Backed by the encrypted
 * vault database and keyed by the normalized key fingerprint.
 */
interface GpgKeyserverStateRepository {
    fun getAll(): Flow<List<DGpgKeyserverState>>

    fun getByFingerprint(
        fingerprint: String,
    ): Flow<DGpgKeyserverState?>

    fun getByCipherId(
        cipherId: String,
    ): Flow<List<DGpgKeyserverState>>

    fun put(
        model: DGpgKeyserverState,
    ): IO<Unit>

    /** Reads, evaluates, and writes one fingerprint while holding the vault mutation lock. */
    fun update(
        fingerprint: String,
        transform: (DGpgKeyserverState?, List<GpgKeyserverLocalKey>) -> DGpgKeyserverState,
    ): IO<DGpgKeyserverState>

    fun removeByFingerprint(
        fingerprint: String,
    ): IO<Unit>

    fun removeAll(): IO<Unit>
}
