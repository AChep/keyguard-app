package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import kotlinx.coroutines.Dispatchers
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
}

/** Describes the [mutualTls] certificate; `null` if there is none or it can not be read. */
suspend fun MutualTlsService.describe(
    mutualTls: ServerEnv.MutualTls?,
): MutualTlsCertificateMetadata? {
    val imported = mutualTls as? ServerEnv.MutualTls.ImportedPkcs12
        ?: return null
    return withContext(Dispatchers.Default) {
        describeImportedPkcs12(imported.pkcs12Base64)
    }
}
