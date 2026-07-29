package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential

private const val TOTP_PERIOD_MIN = 1

/**
 * §3.3.16's CDDL types `digits` as `uint .size 2`; the effective ceiling is
 * Keyguard's own otpauth parser, which accepts one to nine (`TotpToken.kt`,
 * `parseOtpDigits`). A spec-legal `digits: 10` is therefore a counted refusal
 * on both sides.
 */
private const val TOTP_DIGITS_MIN = 1
private const val TOTP_DIGITS_MAX = 9

/**
 * A period of `0` is not a time step. The upper bound is Int-representability
 * rather than the CDDL's `uint .size 2`: `.size` is a CBOR-flavoured hint the
 * JSON encoding cannot carry, and enforcing it here would turn a working vault
 * credential into a counted export skip.
 */
internal val CXF_TOTP_PERIOD_RANGE = TOTP_PERIOD_MIN..Int.MAX_VALUE

internal val CXF_TOTP_DIGITS_RANGE = TOTP_DIGITS_MIN..TOTP_DIGITS_MAX

/**
 * `period` as the vault holds it — `TotpToken.TotpAuth.period` is a `Long`, so
 * the range has to be re-expressed to compare without narrowing first.
 */
internal val CXF_TOTP_PERIOD_RANGE_LONG =
    CXF_TOTP_PERIOD_RANGE.first.toLong()..CXF_TOTP_PERIOD_RANGE.last.toLong()

private val BASE32_SECRET_PATTERN = Regex("^[A-Za-z2-7]+=*$")

/**
 * The single source of truth for what a CXF TOTP credential's `algorithm` may
 * contain (CXF v1.0 §3.3.16). Both directions consult it, so "representable"
 * means exactly the same thing on the way out and on the way in.
 */
internal sealed interface CxfTotpAlgorithm {
    val wire: String

    /**
     * The three members of the spec's `OTPHashAlgorithm` production, and the
     * only algorithms an otpauth uri can carry.
     */
    enum class Hash(
        override val wire: String,
        /**
         * The otpauth `algorithm=` query value, or `null` for SHA-1 — the
         * format's default, which is omitted. This `null` never means
         * "unrepresentable"; refusal is [fromWireOrNull] returning `null`.
         */
        val otpAuthParam: String?,
    ) : CxfTotpAlgorithm {
        Sha1(CxfCredential.Totp.ALGORITHM_SHA1, null),
        Sha256(CxfCredential.Totp.ALGORITHM_SHA256, "SHA256"),
        Sha512(CxfCredential.Totp.ALGORITHM_SHA512, "SHA512"),
    }

    /**
     * Not a member of `OTPHashAlgorithm` (§3.3.16.1). §3.3.16 types the member
     * as `OTPHashAlgorithm / tstr`, so carrying the industry `steam` marker is
     * structurally legal, while §3.3.16 also has importers MUST ignore TOTP
     * entries whose algorithm they do not know — the outcome a provider that
     * cannot compute Steam codes wants. Pinned deviation D8 — see
     * `CxfConformanceBehaviorTest`.
     */
    data object Steam : CxfTotpAlgorithm {
        override val wire: String get() = CxfCredential.Totp.ALGORITHM_STEAM
    }

    companion object {
        /**
         * Decodes a wire value, or `null` for an algorithm the format does not
         * name — which §3.3.16 says importers MUST ignore. `null` never means
         * "assume SHA-1".
         *
         * Matching trims and folds case: the spec literals are lowercase, so
         * strictly `"SHA256"` is an unknown value, but it has exactly one
         * possible meaning. Pinned deviation D9.
         */
        fun fromWireOrNull(raw: String): CxfTotpAlgorithm? =
            when (val value = raw.trim().lowercase()) {
                Steam.wire -> Steam
                else -> Hash.entries.firstOrNull { it.wire == value }
            }
    }
}

/**
 * Canonicalizes a TOTP secret into unpadded upper-case base32, or returns `null`
 * when the value is not base32 at all. §3.3.16 requires the secret to be a
 * Base32 string; the single canonical spelling is this app's own choice.
 * Consulted by **both** directions, so export and import agree on which secrets
 * are representable.
 *
 * Only the alphabet refuses. Separators (whitespace and `-`) are stripped
 * rather than refused because both base32 backends already ignore them, so
 * `JBSW-Y3DP-EHPK 3PXP` stays a working secret. A length whose remainder mod 8
 * is 1 decodes to a different byte count on JVM/Android than on iOS; it is
 * admitted anyway, since that divergence is app-wide and independent of CXF.
 */
internal fun canonicalTotpSecretOrNull(
    secret: String,
): String? = secret
    .filterNot { it.isWhitespace() || it == '-' }
    .takeIf { it.matches(BASE32_SECRET_PATTERN) }
    ?.trimEnd('=')
    ?.uppercase()
    ?.takeIf { it.isNotEmpty() }
