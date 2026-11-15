package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey

interface GpgKeyImportService {
    fun import(
        request: GpgKeyImportRequest,
    ): GpgKeyImportResult
}

data class GpgKeyImportRequest(
    /** Borrowed key bytes, preserved by the importer so a passphrase retry can reuse them. */
    val content: ByteArray,
    val fileName: String? = null,
    /** Tried in order on every encrypted component of the key. */
    val passphrases: List<String> = emptyList(),
)

sealed interface GpgKeyImportResult {
    data class Success(
        val gpgKey: GeneratedGpgKey,
    ) : GpgKeyImportResult

    data class NeedsPassphrase(
        val formatLabel: String,
        /** Encrypted components that no passphrase unlocked, primary key first. */
        val lockedComponents: List<GpgKeyImportComponent> = emptyList(),
        /** Indexes into [GpgKeyImportRequest.passphrases] that unlocked at least one component. */
        val unlockingPassphraseIndexes: Set<Int> = emptySet(),
    ) : GpgKeyImportResult

    data class Error(
        val reason: GpgKeyImportError,
    ) : GpgKeyImportResult
}

/** A primary key or subkey that is still locked during import. */
data class GpgKeyImportComponent(
    val fingerprint: String,
    val keyId: String,
    val primary: Boolean,
    val algorithm: String? = null,
    val bitStrength: Int? = null,
    val canSign: Boolean = false,
    val canEncrypt: Boolean = false,
)

enum class GpgKeyImportError {
    Empty,
    UnsupportedFormat,
    UnsupportedPlatform,
    InvalidPassphrase,
    MalformedKey,

    /** The file holds several keys, while an item stores exactly one. */
    MultipleKeys,
}

object GpgKeyImportServiceUnsupported : GpgKeyImportService {
    override fun import(
        request: GpgKeyImportRequest,
    ): GpgKeyImportResult = GpgKeyImportResult.Error(
        reason = GpgKeyImportError.UnsupportedPlatform,
    )
}
