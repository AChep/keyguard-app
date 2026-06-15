package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.KeyPair

interface SshKeyImportService {
    fun import(
        request: SshKeyImportRequest,
    ): SshKeyImportResult
}

data class SshKeyImportRequest(
    val content: String,
    val fileName: String? = null,
    val passphrase: String? = null,
)

sealed interface SshKeyImportResult {
    data class Success(
        val keyPair: KeyPair,
    ) : SshKeyImportResult

    data class NeedsPassphrase(
        val formatLabel: String,
    ) : SshKeyImportResult

    data class Error(
        val reason: SshKeyImportError,
    ) : SshKeyImportResult
}

enum class SshKeyImportError {
    UnsupportedFormat,
    UnsupportedAlgorithm,
    InvalidPassphrase,
    MalformedKey,
}
