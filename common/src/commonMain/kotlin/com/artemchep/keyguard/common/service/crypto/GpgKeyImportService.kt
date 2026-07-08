package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey

interface GpgKeyImportService {
    fun import(
        request: GpgKeyImportRequest,
    ): GpgKeyImportResult
}

data class GpgKeyImportRequest(
    val content: String,
    val fileName: String? = null,
    val passphrase: String? = null,
)

sealed interface GpgKeyImportResult {
    data class Success(
        val gpgKey: GeneratedGpgKey,
    ) : GpgKeyImportResult

    data class NeedsPassphrase(
        val formatLabel: String,
    ) : GpgKeyImportResult

    data class Error(
        val reason: GpgKeyImportError,
    ) : GpgKeyImportResult
}

enum class GpgKeyImportError {
    Empty,
    UnsupportedFormat,
    UnsupportedPlatform,
    InvalidPassphrase,
    MalformedKey,
}

object GpgKeyImportServiceUnsupported : GpgKeyImportService {
    override fun import(
        request: GpgKeyImportRequest,
    ): GpgKeyImportResult = GpgKeyImportResult.Error(
        reason = GpgKeyImportError.UnsupportedPlatform,
    )
}
