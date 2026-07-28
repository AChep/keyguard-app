package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import kotlin.time.Instant

/**
 * Parses ASCII-armored OpenPGP public key blocks (as returned by keyservers)
 * into structured metadata.
 */
interface GpgPublicKeyParser {
    val isSupported: Boolean
        get() = true

    fun parse(
        armored: String,
    ): GpgPublicKeyParseResult
}

/**
 * Parses [armored] and returns the key whose fingerprint matches [fingerprint]
 * (independent of case and formatting). A null or blank fingerprint means that
 * no fingerprint is available, in which case the first parsed key is returned.
 * A non-blank fingerprint must match exactly; it never falls back to another
 * key from the same armored input.
 */
fun GpgPublicKeyParser.parsePrimaryKeyInfo(
    armored: String,
    fingerprint: String? = null,
): GpgPublicKeyInfo? {
    val keys = (parse(armored) as? GpgPublicKeyParseResult.Success)?.keys
        ?: return null
    val normalized = fingerprint
        ?.normalizeGpgFingerprint()
        ?.takeIf { it.isNotEmpty() }
        ?: return keys.firstOrNull()
    return keys.firstOrNull {
        it.fingerprint.normalizeGpgFingerprint() == normalized
    }
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

    /** The input contains a legacy V2/V3 OpenPGP key packet. */
    UnsupportedKeyVersion,

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
    val createdAt: Instant? = null,
    val expiresAt: Instant?,
)

/**
 * Fallback used on platforms without
 * an OpenPGP implementation
 */
object GpgPublicKeyParserUnsupported : GpgPublicKeyParser {
    override val isSupported: Boolean
        get() = false

    override fun parse(
        armored: String,
    ): GpgPublicKeyParseResult = GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Unsupported)
}
