package com.artemchep.keyguard.common.service.crypto

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.time.Instant

interface GpgOpenPgpVerifier {
    fun verifyClearSignedText(
        request: GpgOpenPgpVerifyTextRequest,
    ): GpgOpenPgpVerification

    fun verifyDetachedText(
        request: GpgOpenPgpVerifyDetachedTextRequest,
    ): GpgOpenPgpVerification

    fun verifyFile(
        request: GpgOpenPgpVerifyFileRequest,
    ): GpgOpenPgpVerification
}

interface GpgOpenPgpCertificationEvaluator {
    /**
     * Returns exact target User IDs certified by a currently usable trusted
     * primary key. Implementations that cannot evaluate certifications fail
     * closed by returning no confirmed identities.
     */
    fun evaluateUserIdCertifications(
        request: GpgOpenPgpUserIdCertificationRequest,
    ): List<String> = emptyList()
}

interface GpgOpenPgpService :
    GpgOpenPgpVerifier,
    GpgOpenPgpCertificationEvaluator {

    fun clearSignText(
        request: GpgOpenPgpSignTextRequest,
    ): String

    fun signTextDetached(
        request: GpgOpenPgpSignTextRequest,
    ): String

    fun encryptText(
        request: GpgOpenPgpEncryptTextRequest,
    ): String

    fun decryptText(
        request: GpgOpenPgpDecryptTextRequest,
    ): GpgOpenPgpDecryptTextResult

    /**
     * Validates and exports exactly one OpenPGP public-key certificate.
     *
     * Implementations must parse the certificate before writing any output. When
     * [GpgOpenPgpExportPublicKeyRequest.armored] is false, [GpgOpenPgpPublicKey.armored]
     * is decoded into its binary OpenPGP packet representation.
     */
    fun exportPublicKey(
        request: GpgOpenPgpExportPublicKeyRequest,
    )

    fun signFile(
        request: GpgOpenPgpSignFileRequest,
    )

    fun clearSignFile(
        request: GpgOpenPgpClearSignFileRequest,
    )

    /**
     * Requires [GpgOpenPgpReadFileRequest.input] to be a clear-signed document.
     * The output receives the verified, dash-unescaped body, excluding the
     * signature separator line ending and unauthenticated trailing spaces and tabs.
     */
    fun verifyClearSignedFile(
        request: GpgOpenPgpReadFileRequest,
    ): GpgOpenPgpReadFileResult.ClearSigned

    fun encryptFile(
        request: GpgOpenPgpEncryptFileRequest,
    )

    /** Requires [GpgOpenPgpReadFileRequest.input] to be an OpenPGP message. */
    fun decryptFile(
        request: GpgOpenPgpReadFileRequest,
    ): GpgOpenPgpReadFileResult.Message

    /**
     * Classifies and reads an inline OpenPGP document without consuming bytes
     * during classification.
     */
    fun readFile(
        request: GpgOpenPgpReadFileRequest,
    ): GpgOpenPgpReadFileResult = if (request.input.isClearSignedOpenPgpDocument()) {
        verifyClearSignedFile(request)
    } else {
        decryptFile(request)
    }
}

data class GpgOpenPgpReadFileRequest(
    /** The complete inline OpenPGP document. */
    val input: Source,
    /** Receives authenticated message plaintext or the recovered clear-signed body. */
    val output: Sink,
    /** Used only for OpenPGP messages. */
    val privateKeys: List<GpgOpenPgpPrivateKey> = emptyList(),
    val publicKeys: List<GpgOpenPgpPublicKey> = emptyList(),
    /** Used only for OpenPGP messages. */
    val allowSignedOnly: Boolean = false,
)

sealed interface GpgOpenPgpReadFileResult {
    data class Message(
        val verification: GpgOpenPgpVerification? = null,
        val metadata: GpgOpenPgpLiteralMetadata? = null,
        val encrypted: Boolean = true,
        /** Raw value of the message "Charset:" armor header, when present once. */
        val declaredCharset: String? = null,
        /** Exact primary key or subkey component that recovered the session key. */
        val decryptionKeyFingerprint: String? = null,
        /** Deprecation warnings for the component that recovered the session key. */
        val warnings: List<GpgOpenPgpDecryptionWarning> = emptyList(),
    ) : GpgOpenPgpReadFileResult

    data class ClearSigned(
        val verification: GpgOpenPgpVerification,
        /** True when every recovered body line is valid UTF-8. */
        val bodyValidUtf8: Boolean,
        /** Size of the recovered body in bytes. */
        val bodySize: Long,
    ) : GpgOpenPgpReadFileResult
}

data class GpgOpenPgpPrivateKey(
    val armored: String,
    val preferredFingerprint: String? = null,
)

data class GpgOpenPgpPublicKey(
    val armored: String,
)

data class GpgOpenPgpCertificationAuthority(
    val publicKey: GpgOpenPgpPublicKey,
    val primaryFingerprint: String,
)

data class GpgOpenPgpUserIdCertificationRequest(
    val publicKey: GpgOpenPgpPublicKey,
    val authorities: List<GpgOpenPgpCertificationAuthority>,
    val referenceTime: Instant,
)

data class GpgOpenPgpExportPublicKeyRequest(
    val publicKey: GpgOpenPgpPublicKey,
    val output: Sink,
    val armored: Boolean = true,
)

data class GpgOpenPgpSignTextRequest(
    val text: String,
    val privateKey: GpgOpenPgpPrivateKey,
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
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
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
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
    val decryptionKeyFingerprint: String? = null,
    val warnings: List<GpgOpenPgpDecryptionWarning> = emptyList(),
)

enum class GpgOpenPgpDecryptionWarning {
    WEAK_RSA_KEY,
    ELGAMAL_KEY,
}

data class GpgOpenPgpSignFileRequest(
    val input: Source,
    val signatureOutput: Sink,
    val privateKey: GpgOpenPgpPrivateKey,
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    val armored: Boolean = true,
)

data class GpgOpenPgpClearSignFileRequest(
    val input: Source,
    val output: Sink,
    val privateKey: GpgOpenPgpPrivateKey,
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
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
    val candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    val fileName: GpgOpenPgpLiteralFileName,
    val armored: Boolean = true,
    val signingPrivateKey: GpgOpenPgpPrivateKey? = null,
    val enableCompression: Boolean = true,
)

private const val CLEAR_SIGNED_OPENPGP_FRAMING_LIMIT_BYTES = 64 * 1024
private const val CLEAR_SIGNED_OPENPGP_SCAN_BUFFER_BYTES = 8 * 1024

private val CLEAR_SIGNED_OPENPGP_MARKER =
    "-----BEGIN PGP SIGNED MESSAGE-----".encodeToByteArray()

private fun Source.isClearSignedOpenPgpDocument(): Boolean = peek().use { lookahead ->
    // Keep this bound aligned with MAX_CLEAR_SIGNED_HEADER_BYTES in the native
    // clear-sign parser. Classification only finds the exact marker line; the
    // native parser remains responsible for validating the document framing.
    val buffer = ByteArray(CLEAR_SIGNED_OPENPGP_SCAN_BUFFER_BYTES)
    val scanner = ClearSignedOpenPgpMarkerScanner()
    var scannedBytes = 0
    while (scannedBytes < CLEAR_SIGNED_OPENPGP_FRAMING_LIMIT_BYTES) {
        val readLimit = minOf(
            buffer.size,
            CLEAR_SIGNED_OPENPGP_FRAMING_LIMIT_BYTES - scannedBytes,
        )
        val count = lookahead.readAtMostTo(buffer, endIndex = readLimit)
        if (count <= 0) {
            return@use false
        }
        scannedBytes += count
        if (scanner.update(buffer, count)) {
            return@use true
        }
    }

    false
}

private class ClearSignedOpenPgpMarkerScanner {
    private var lineLength = 0
    private var lineMatchesMarker = true
    private var previousInputWasCr = false

    fun update(
        buffer: ByteArray,
        count: Int,
    ): Boolean {
        var index = 0
        var markerFound = false
        while (index < count && !markerFound) {
            val byte = buffer[index]
            if (previousInputWasCr) {
                previousInputWasCr = false
                if (byte == '\n'.code.toByte()) {
                    index += 1
                }
                markerFound = completeLine()
                if (markerFound || byte == '\n'.code.toByte()) {
                    continue
                }
            }

            index += 1
            when (byte) {
                '\r'.code.toByte() -> previousInputWasCr = true
                '\n'.code.toByte() -> markerFound = completeLine()
                else -> acceptLineByte(byte)
            }
        }
        return markerFound
    }

    private fun acceptLineByte(byte: Byte) {
        if (lineMatchesMarker) {
            lineMatchesMarker =
                lineLength < CLEAR_SIGNED_OPENPGP_MARKER.size &&
                    byte == CLEAR_SIGNED_OPENPGP_MARKER[lineLength]
        }
        lineLength += 1
    }

    private fun completeLine(): Boolean {
        val isMarker = lineMatchesMarker && lineLength == CLEAR_SIGNED_OPENPGP_MARKER.size
        lineLength = 0
        lineMatchesMarker = true
        return isMarker
    }
}

data class GpgOpenPgpVerification(
    val status: GpgOpenPgpVerificationStatus,
    val keyId: String,
    val fingerprint: String?,
    val userIds: List<String>,
    val createdAt: Instant?,
    val warnings: List<GpgOpenPgpVerificationWarning> = emptyList(),
    /**
     * Exact entries from [userIds] certified by a trusted local authority.
     * Never populated alongside a [GpgOpenPgpVerificationWarning.POLICY_CONFLICT]
     * warning: a policy conflict makes every identity assertion ambiguous.
     */
    val confirmedUserIds: List<String> = emptyList(),
    /** One leaf result per input signature, in packet order. */
    val signatures: List<GpgOpenPgpVerification> = emptyList(),
) {
    /** True only for an unqualified valid result under Keyguard's caller policy. */
    val isPolicyAccepted: Boolean
        get() = status == GpgOpenPgpVerificationStatus.VALID && warnings.isEmpty()
}

/** Payload signature result; signing-key policy is reported separately in `warnings`. */
enum class GpgOpenPgpVerificationStatus {
    VALID,
    INVALID,
    MISSING_PUBLIC_KEY,
}

enum class GpgOpenPgpVerificationWarning {
    /** The signature may verify mathematically, but the signing authority is revoked. */
    KEY_REVOKED,

    /** The signature may verify mathematically, but the signing authority is expired. */
    KEY_EXPIRED,

    /** The signature statement has expired and is therefore reported as invalid. */
    SIGNATURE_EXPIRED,

    /** The payload may be valid, but equally recent authenticated key policies disagree. */
    POLICY_CONFLICT,

    /**
     * The signature is bound to a digest algorithm that is no longer collision resistant
     * (SHA-1 or MD5). Such a signature is never reported as valid.
     */
    WEAK_DIGEST,
}
