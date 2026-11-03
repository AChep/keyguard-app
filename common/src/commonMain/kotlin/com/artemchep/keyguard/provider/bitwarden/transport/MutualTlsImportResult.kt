package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv

sealed interface MutualTlsImportResult {
    data class Success(
        val imported: ServerEnv.MutualTls.ImportedPkcs12,
        val metadata: MutualTlsCertificateMetadata,
    ) : MutualTlsImportResult

    data object PasswordRequired : MutualTlsImportResult

    data object InvalidPassword : MutualTlsImportResult

    data object Error : MutualTlsImportResult
}
