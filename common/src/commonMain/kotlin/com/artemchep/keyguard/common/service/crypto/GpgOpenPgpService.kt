package com.artemchep.keyguard.common.service.crypto

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.time.Instant

interface GpgOpenPgpService {
    fun clearSignText(
        request: GpgOpenPgpSignTextRequest,
    ): String

    fun signTextDetached(
        request: GpgOpenPgpSignTextRequest,
    ): String

    fun verifyClearSignedText(
        request: GpgOpenPgpVerifyTextRequest,
    ): GpgOpenPgpVerification

    fun verifyDetachedText(
        request: GpgOpenPgpVerifyDetachedTextRequest,
    ): GpgOpenPgpVerification

    fun encryptText(
        request: GpgOpenPgpEncryptTextRequest,
    ): String

    fun decryptText(
        request: GpgOpenPgpDecryptTextRequest,
    ): GpgOpenPgpDecryptTextResult

    fun signFile(
        request: GpgOpenPgpSignFileRequest,
    )

    fun verifyFile(
        request: GpgOpenPgpVerifyFileRequest,
    ): GpgOpenPgpVerification

    fun encryptFile(
        request: GpgOpenPgpEncryptFileRequest,
    )

    fun decryptFile(
        request: GpgOpenPgpDecryptFileRequest,
    ): GpgOpenPgpDecryptFileResult
}

data class GpgOpenPgpPrivateKey(
    val armored: String,
    val preferredFingerprint: String? = null,
)

data class GpgOpenPgpPublicKey(
    val armored: String,
)

data class GpgOpenPgpSignTextRequest(
    val text: String,
    val privateKey: GpgOpenPgpPrivateKey,
)

data class GpgOpenPgpVerifyTextRequest(
    val signedText: String,
    val publicKeys: List<GpgOpenPgpPublicKey>,
)

data class GpgOpenPgpVerifyDetachedTextRequest(
    val text: String,
    val signature: String,
    val publicKeys: List<GpgOpenPgpPublicKey>,
)

data class GpgOpenPgpEncryptTextRequest(
    val text: String,
    val publicKeys: List<GpgOpenPgpPublicKey>,
    val signingPrivateKey: GpgOpenPgpPrivateKey? = null,
)

data class GpgOpenPgpDecryptTextRequest(
    val encryptedText: String,
    val privateKeys: List<GpgOpenPgpPrivateKey>,
    val publicKeys: List<GpgOpenPgpPublicKey> = emptyList(),
)

data class GpgOpenPgpDecryptTextResult(
    val text: String,
    val verification: GpgOpenPgpVerification? = null,
)

data class GpgOpenPgpSignFileRequest(
    val input: Source,
    val signatureOutput: Sink,
    val privateKey: GpgOpenPgpPrivateKey,
    val armored: Boolean = true,
)

data class GpgOpenPgpVerifyFileRequest(
    val input: Source,
    val signatureInput: Source,
    val publicKeys: List<GpgOpenPgpPublicKey>,
)

data class GpgOpenPgpEncryptFileRequest(
    val input: Source,
    val output: Sink,
    val publicKeys: List<GpgOpenPgpPublicKey>,
    val fileName: String,
    val armored: Boolean = true,
    val signingPrivateKey: GpgOpenPgpPrivateKey? = null,
)

data class GpgOpenPgpDecryptFileRequest(
    val input: Source,
    val output: Sink,
    val privateKeys: List<GpgOpenPgpPrivateKey>,
    val publicKeys: List<GpgOpenPgpPublicKey> = emptyList(),
)

data class GpgOpenPgpDecryptFileResult(
    val verification: GpgOpenPgpVerification? = null,
)

data class GpgOpenPgpVerification(
    val status: GpgOpenPgpVerificationStatus,
    val keyId: String,
    val fingerprint: String?,
    val userIds: List<String>,
    val createdAt: Instant?,
    val warnings: List<GpgOpenPgpVerificationWarning> = emptyList(),
)

enum class GpgOpenPgpVerificationStatus {
    VALID,
    INVALID,
    MISSING_PUBLIC_KEY,
}

enum class GpgOpenPgpVerificationWarning {
    KEY_REVOKED,
    KEY_EXPIRED,
    SIGNATURE_EXPIRED,
}
