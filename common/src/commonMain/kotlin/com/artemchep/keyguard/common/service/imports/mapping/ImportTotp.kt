package com.artemchep.keyguard.common.service.imports.mapping

import com.artemchep.keyguard.common.model.TotpToken

private val BASE32_SECRET_PATTERN = Regex("^[A-Za-z2-7]+=*$")

private val TOTP_URI_PREFIXES = listOf(
    "otpauth://",
    "steam://",
    "motp://",
)

/**
 * Canonicalizes a TOTP secret into unpadded upper-case base32, or returns `null`
 * when the value is not base32 at all. CXF v1.0 §3.3.16 requires the secret to
 * be a Base32 string; the single canonical spelling is this app's own choice.
 * Consulted by **both** CXF directions and by the file imports, so they all
 * agree on which secrets are representable.
 *
 * Only the alphabet refuses. Separators (whitespace and `-`) are stripped
 * rather than refused because both base32 backends already ignore them, so
 * `JBSW-Y3DP-EHPK 3PXP` stays a working secret. A length whose remainder mod 8
 * is 1 decodes to a different byte count on JVM/Android than on iOS; it is
 * admitted anyway, since that divergence is app-wide.
 */
internal fun canonicalTotpSecretOrNull(
    secret: String,
): String? = secret
    .filterNot { it.isWhitespace() || it == '-' }
    .takeIf { it.matches(BASE32_SECRET_PATTERN) }
    ?.trimEnd('=')
    ?.uppercase()
    ?.takeIf { it.isNotEmpty() }

/**
 * A one-time password configuration as an export file spells it.
 */
internal sealed interface ImportedTotp {
    /**
     * The record has no one-time password.
     */
    data object Absent : ImportedTotp

    data class Valid(
        val raw: String,
    ) : ImportedTotp

    /**
     * The record has a value Keyguard cannot generate codes from. It is
     * dropped and counted rather than stored as a broken configuration.
     */
    data object Invalid : ImportedTotp
}

/**
 * Reads a one-time password from an export: an `otpauth://`, `steam://` or
 * `motp://` URI, or a bare base32 secret.
 */
internal fun importedTotp(
    raw: String?,
): ImportedTotp {
    val value = raw
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return ImportedTotp.Absent
    val isUri = TOTP_URI_PREFIXES.any { prefix ->
        value.startsWith(prefix, ignoreCase = true)
    }
    val totp = if (isUri) {
        value.takeIf { isUsableTotpUri(it) }
    } else {
        canonicalTotpSecretOrNull(value)
    }
    return totp
        ?.let(ImportedTotp::Valid)
        ?: ImportedTotp.Invalid
}

/**
 * The otpauth parser takes the secret on trust, so a URI whose secret is not
 * base32 parses fine and then generates codes from garbage.
 */
private fun isUsableTotpUri(
    uri: String,
): Boolean = when (val token = TotpToken.parse(uri).getOrNull()) {
    null -> false
    is TotpToken.TotpAuth -> canonicalTotpSecretOrNull(token.keyBase32) != null
    is TotpToken.HotpAuth -> canonicalTotpSecretOrNull(token.keyBase32) != null
    is TotpToken.SteamAuth -> canonicalTotpSecretOrNull(token.keyBase32) != null
    is TotpToken.MobileAuth -> token.secret.isNotBlank()
}
