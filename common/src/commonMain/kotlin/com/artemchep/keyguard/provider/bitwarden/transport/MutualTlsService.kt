package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface MutualTlsService {
    fun importPkcs12(
        sourcePkcs12: ByteArray,
        password: String?,
    ): MutualTlsImportResult

    /** Returns `null` if the bundle can not be read. */
    fun describeImportedPkcs12(
        pkcs12Base64: String,
    ): MutualTlsCertificateMetadata?

    /**
     * Returns `null` if the certificate is gone, the app may no longer
     * use it, or the platform has no certificate store.
     */
    suspend fun describeSystemStore(
        certificate: ServerEnv.MutualTls.SystemStore,
    ): MutualTlsCertificateMetadata?

    /** See [MutualTlsSystemStore.changes]; emits once on a platform without a store. */
    val systemStoreChanges: Flow<Unit>
}

/**
 * Describes the [mutualTls] certificate: `null` if there is none or it can
 * not be read. A certificate of the system's store is described again
 * whenever the store reports a change, so every screen follows it.
 */
fun MutualTlsService.describeFlow(
    mutualTls: ServerEnv.MutualTls?,
): Flow<MutualTlsCertificateMetadata?> = when (mutualTls) {
    is ServerEnv.MutualTls.ImportedPkcs12 -> flow {
        val metadata = withContext(Dispatchers.Default) {
            describeImportedPkcs12(mutualTls.pkcs12Base64)
        }
        emit(metadata)
    }

    is ServerEnv.MutualTls.SystemStore -> systemStoreChanges
        .map { describeSystemStore(mutualTls) }

    null -> flowOf(null)
}
