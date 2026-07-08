package com.artemchep.keyguard.common.service.crypto

import kotlin.time.Instant

/**
 * Parses ASCII-armored OpenPGP public key blocks (as returned by keyservers)
 * into structured metadata.
 */
interface GpgPublicKeyParser {
    fun parse(
        armored: String,
    ): GpgPublicKeyParseResult
}

sealed interface GpgPublicKeyParseResult {
    data class Success(
        val keys: List<GpgPublicKeyInfo>,
    ) : GpgPublicKeyParseResult

    data class Error(
        val reason: GpgPublicKeyParseError,
    ) : GpgPublicKeyParseResult
}

enum class GpgPublicKeyParseError {
    /** The input was blank. */
    Empty,

    /** The input could not be parsed as one or more OpenPGP public keys. */
    Malformed,

    /** Parsing is not available on this platform. */
    Unsupported,
}

data class GpgPublicKeyInfo(
    val fingerprint: String,
    val keygrip: String? = null,
    val keyId: String,
    val algorithm: String,
    val bitStrength: Int?,
    val userIds: List<String>,
    val emails: List<String>,
    val createdAt: Instant?,
    val expiresAt: Instant?,
    val revoked: Boolean,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    /** The ASCII-armored encoding of just this key ring. */
    val publicKeyArmored: String,
    val subKeys: List<GpgPublicSubKeyInfo>,
)

data class GpgPublicSubKeyInfo(
    val fingerprint: String,
    val keygrip: String? = null,
    val keyId: String,
    val algorithm: String,
    val bitStrength: Int? = null,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    val revoked: Boolean,
    val expiresAt: Instant?,
)

/**
 * Fallback used on platforms without
 * an OpenPGP implementation
 */
object GpgPublicKeyParserUnsupported : GpgPublicKeyParser {
    override fun parse(
        armored: String,
    ): GpgPublicKeyParseResult = GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Unsupported)
}
