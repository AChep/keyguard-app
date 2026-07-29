package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAndroidAppCertificateFingerprint
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialScope
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.normalizeSha256FingerprintOrNull
import com.artemchep.keyguard.common.util.toHex
import io.ktor.http.encodeURLParameter

private const val TOTP_DEFAULT_PERIOD = 30
private const val TOTP_DEFAULT_DIGITS = 6

private const val STEAM_URI_PREFIX = "steam://"

/**
 * Rebuilds an otpauth (or Steam) URI from an imported TOTP credential, or
 * returns `null` (a counted skip at [CxfImportSecretMapper]) when the
 * credential is not one the shared contract in `CxfTotp.kt` admits.
 * Default parameters (period 30, 6 digits, SHA-1) are omitted from the URI,
 * matching how other providers emit them.
 */
internal fun mapImportTotpUri(
    totp: CxfCredential.Totp,
): String? {
    // §3.3.16: "importers MUST ignore TOTP entries with unknown algorithm
    // values." Never guess SHA-1 — a wrong-algorithm TOTP renders a plausible
    // six-digit code the relying party rejects, so a counted skip is better
    // than a wrong credential.
    val algorithm = CxfTotpAlgorithm.fromWireOrNull(totp.algorithm)
    // §3.3.16: the secret MUST be base32. Canonicalized rather than refused
    // wherever possible — case-folding and dropping separators preserves the
    // decoded bytes exactly, on every backend.
    val secret = canonicalTotpSecretOrNull(totp.secret)
    if (algorithm == null || secret == null) {
        return null
    }
    return when (algorithm) {
        // The steam form carries nothing but the secret, so period and digits
        // are not consulted: `TotpToken.SteamAuth` fixes both.
        CxfTotpAlgorithm.Steam -> STEAM_URI_PREFIX + secret
        is CxfTotpAlgorithm.Hash -> mapImportOtpAuthUri(totp, secret, algorithm)
    }
}

private fun mapImportOtpAuthUri(
    totp: CxfCredential.Totp,
    secret: String,
    algorithm: CxfTotpAlgorithm.Hash,
): String? {
    val representable = totp.period in CXF_TOTP_PERIOD_RANGE &&
        totp.digits in CXF_TOTP_DIGITS_RANGE
    return if (!representable) {
        null
    } else {
        // Belt and braces: with the checks above this gate is unreachable, and
        // it would buy nothing on the steam branch, where `parseOtpSteam`
        // cannot fail.
        buildOtpAuthUri(totp, secret, algorithm)
            .takeIf { TotpToken.parse(it).isRight() }
    }
}

private fun buildOtpAuthUri(
    totp: CxfCredential.Totp,
    secret: String,
    algorithm: CxfTotpAlgorithm.Hash,
): String = buildString {
    append("otpauth://totp/")
    append(buildOtpAuthLabel(totp))
    append("?secret=")
    // A canonical secret is [A-Z2-7] only, so this is a no-op today and stays
    // as a guard against a future canonicalizer that admits more.
    append(secret.encodeURLParameter())
    totp.issuer
        ?.takeIf { it.isNotBlank() }
        ?.let { issuer ->
            append("&issuer=")
            append(issuer.encodeURLParameter())
        }
    algorithm.otpAuthParam
        ?.let { param ->
            append("&algorithm=")
            append(param)
        }
    totp.digits
        .takeIf { it != TOTP_DEFAULT_DIGITS }
        ?.let { digits ->
            append("&digits=")
            append(digits)
        }
    totp.period
        .takeIf { it != TOTP_DEFAULT_PERIOD }
        ?.let { period ->
            append("&period=")
            append(period)
        }
}

/**
 * Builds the otpauth label — the `issuer:account` path component.
 *
 * The colon is the label's separator and Google's Key Uri Format treats a
 * percent-encoded one exactly like a literal one, so `%3A` buys no separation:
 * a colon inside either half is read back as the separator, turning
 * `username = "alice:bob"` into a fabricated issuer `"alice"`. The label format
 * cannot carry the character, so it is dropped from both halves.
 *
 * Nothing authenticable is affected: the secret, digits, period and algorithm
 * are query parameters, and the exact issuer still travels in `issuer=`, which
 * `TotpToken` prefers over the label prefix — only an account name containing
 * a colon loses the character.
 */
private fun buildOtpAuthLabel(
    totp: CxfCredential.Totp,
): String {
    val issuer = otpAuthLabelPart(totp.issuer)
    val username = otpAuthLabelPart(totp.username)
    return when {
        issuer != null && username != null -> "$issuer:$username"
        username != null -> username
        else -> ""
    }
}

private fun otpAuthLabelPart(
    value: String?,
): String? = value
    ?.takeIf { it.isNotBlank() }
    ?.replace(":", "")
    ?.takeIf { it.isNotBlank() }
    ?.encodeURLParameter()

/**
 * Maps an item's scope back into Keyguard uris — web urls verbatim, Android
 * app ids through the `androidapp://` convention. The inverse of [mapScope].
 */
internal fun mapImportUris(
    scope: CxfCredentialScope?,
): List<DSecret.Uri> {
    if (scope == null) {
        return emptyList()
    }
    val uris = mutableListOf<DSecret.Uri>()
    scope.urls.forEach { url ->
        val value = url.trim()
        if (value.isNotEmpty()) {
            uris += DSecret.Uri(
                uri = value,
            )
        }
    }
    scope.androidApps.forEach { app ->
        val bundleId = app.bundleId.trim()
        if (bundleId.isNotEmpty()) {
            uris += DSecret.Uri(
                uri = PROTOCOL_ANDROID_APP + bundleId,
                signatures = listOfNotNull(
                    app.certificate?.let(::mapImportCertificateFingerprint),
                ),
            )
        }
    }
    return uris
}

/**
 * Converts a CXF certificate fingerprint (base64url raw hash bytes) back into
 * Keyguard's colon-separated upper-case hex form. Only SHA-256 fingerprints
 * with the full 32-byte hash are representable; anything else returns `null`.
 * The inverse of [mapCertificateFingerprint].
 */
internal fun mapImportCertificateFingerprint(
    certificate: CxfAndroidAppCertificateFingerprint,
): DSecret.Uri.Signature? {
    val hex = certificate
        .takeIf { it.hashAlg == CxfAndroidAppCertificateFingerprint.HASH_ALG_SHA256 }
        ?.let { runCatchingNonFatal { cxfUrlSafeBase64.decode(it.fingerprint) }.getOrNull() }
        ?.toHex()
        ?.normalizeSha256FingerprintOrNull()
        ?: return null
    return DSecret.Uri.Signature(
        certFingerprintSha256 = hex,
    )
}

/**
 * When an item carries no scope at all, a login's uris are derived from the
 * relying-party id of its first *successfully imported* passkey. A passkey that
 * failed to map contributes nothing: it is already a counted skip, and an item
 * must not inherit a url from a credential it did not keep.
 */
internal fun mapPasskeyFallbackUris(
    passkeys: List<DSecret.Login.Fido2Credentials>,
): List<DSecret.Uri> {
    val rpId = passkeys.firstNotNullOfOrNull { passkey ->
        passkey.rpId.trim().takeIf { it.isNotEmpty() }
    }
        ?: return emptyList()
    val uri = if (rpId.startsWith("http://") || rpId.startsWith("https://")) {
        rpId
    } else {
        "https://$rpId"
    }
    return listOf(
        DSecret.Uri(
            uri = uri,
        ),
    )
}
