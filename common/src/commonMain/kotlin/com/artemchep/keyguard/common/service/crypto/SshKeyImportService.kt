package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.KeyPair

interface SshKeyImportService {
    fun import(
        request: SshKeyImportRequest,
    ): SshKeyImportResult
}

data class SshKeyImportRequest(
    /** Borrowed bytes, preserved by the importer for passphrase retries. */
    val content: ByteArray,
    val fileName: String? = null,
    val passphrase: String? = null,
) {
    constructor(
        content: String,
        fileName: String? = null,
        passphrase: String? = null,
    ) : this(content.encodeToByteArray(), fileName, passphrase)
}

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
