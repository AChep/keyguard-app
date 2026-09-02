package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgRenewalAuthorization
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
        /** Certificates present in the input but omitted because they are unsupported. */
        val skippedCertificates: Int = 0,
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

    /**
     * The input holds several secret certificates (for example a full
     * `gpg --export-secret-keys` dump), while this operation accepts one.
     */
    MultipleCertificates,

    /** Parsing is not available on this platform. */
    Unsupported,
}

data class GpgUserIdInfo(
    /** Stable identifier derived from the exact OpenPGP identity packet body. */
    val identityId: String,
    val userId: String,
)

data class GpgPublicKeyInfo(
    val fingerprint: String,
    val keygrip: String? = null,
    val keyId: String,
    val algorithm: String,
    val bitStrength: Int?,
    /** Authenticated full OpenPGP User ID strings. */
    val userIds: List<String>,
    /** Mailboxes extracted from [userIds]; callers normalize them before comparison. */
    val emails: List<String>,
    val createdAt: Instant?,
    val expiresAt: Instant?,
    val revoked: Boolean,
    val canSign: Boolean,
    val canEncrypt: Boolean,
    /**
     * The ASCII-armored encoding of this certificate's complete original public packet span.
     * Metadata fields above still report only policy-authenticated identities and capabilities.
     * When parsing secret input, this is instead its ordinary transferable public projection.
     */
    val publicKeyArmored: String,
    val subKeys: List<GpgPublicSubKeyInfo>,
    /**
     * Whether a self-signature that satisfies the current hash policy
     * authenticates this key.
     *
     * `false` means the key is bound only by a legacy weak-hash (SHA-1)
     * self-signature: it authorizes nothing, but renewing it reissues that
     * signature with a modern algorithm and repairs the key.
     */
    val authenticated: Boolean = true,
    /**
     * Whether recertification may reissue this key's own self-signatures.
     *
     * This is what tells the two `authenticated == false` keys apart:
     * [GpgRenewalAuthorization.TEMPLATE_ONLY] is the weak-hash key a renewal
     * repairs, [GpgRenewalAuthorization.NONE] is the key a renewal cannot
     * touch — it has no verified self-signature at all, or it is revoked.
     * Subkeys carry no such field: an unauthenticated subkey is only reported
     * when it is template-renewable.
     */
    val renewal: GpgRenewalAuthorization = GpgRenewalAuthorization.NONE,
    /** Policy-authenticated textual User IDs paired with their stable packet identifiers. */
    val userIdDetails: List<GpgUserIdInfo> = emptyList(),
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
    /** See [GpgPublicKeyInfo.authenticated]. */
    val authenticated: Boolean = true,
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
