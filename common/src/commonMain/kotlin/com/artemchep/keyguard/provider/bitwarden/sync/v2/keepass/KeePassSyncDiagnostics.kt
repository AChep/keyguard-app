package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.provider.bitwarden.sync.v2.SyncDiagnostics

class KeePassSyncDiagnostics(
    logRepository: LogRepository?,
    enabled: Boolean = !isRelease,
) : SyncDiagnostics(
    logRepository = logRepository,
    enabled = enabled,
    tag = TAG,
) {
    companion object {
        private const val TAG = "SyncDiagnostics.keepass"
    }

    suspend fun syncPipelineStarted() = info {
        "sync_pipeline_started"
    }

    suspend fun syncPipelineCompleted() = info {
        "sync_pipeline_completed"
    }

    suspend fun databaseExtracted(
        folderCount: Int,
        cipherCount: Int,
    ) = info {
        "database_extracted folder_count=$folderCount cipher_count=$cipherCount"
    }

    suspend fun noMutationsToFlush() = debug {
        "no_mutations_to_flush"
    }

    suspend fun externalModificationGuardUnavailable() = warning {
        "external_modification_guard_unavailable"
    }

    suspend fun externalModificationAborted(
        before: String,
        after: String,
    ) = debug {
        "external_modification_aborted before=$before after=$after"
    }

    suspend fun databaseFlushed(mutationCount: Int) = info {
        "database_flushed mutation_count=$mutationCount"
    }

    suspend fun writeBackCommitted() = debug {
        "write_back_committed"
    }

    suspend fun writeBackDiscarded(error: Throwable) = debug {
        "write_back_discarded error=${error.summary()}"
    }
}
