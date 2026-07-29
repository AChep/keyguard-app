package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAndroidAppCertificateFingerprint
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfAndroidAppId
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialScope
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.common.util.normalizeSha256FingerprintOrNull
import kotlin.io.encoding.Base64

private const val COMMAND_PREFIX = "cmd://"

/**
 * Base64 codecs for normalizing stored binary passkey fields. Padding is
 * optional both ways: Keyguard's own creation flow writes unpadded base64url
 * (user handles) and padded standard base64 (private keys), and synced values
 * may differ again, so every combination is accepted. The alphabet itself stays
 * strict, so a corrupted value becomes a counted skip instead of garbage bytes
 * on the wire.
 */
internal val cxfUrlSafeBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
internal val cxfStandardBase64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * Maps the username/password of a login to a basic-auth credential, or `null`
 * when neither is present.
 */
internal fun mapBasicAuth(
    login: DSecret.Login,
): CxfCredential.BasicAuth? {
    val username = login.username
        ?.takeIf { it.isNotBlank() }
        ?.let { value ->
            CxfEditableField(
                fieldType = CxfEditableField.FIELD_TYPE_STRING,
                value = value,
            )
        }
    // `isNotEmpty`, not `isNotBlank`: a password of only whitespace is a
    // password. Blank-filtering it here dropped the secret while the credential
    // still travelled, so the loss was invisible to the skip tally.
    val password = login.password
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            CxfEditableField(
                fieldType = CxfEditableField.FIELD_TYPE_CONCEALED_STRING,
                value = value,
            )
        }
    if (username == null && password == null) {
        return null
    }
    return CxfCredential.BasicAuth(
        username = username,
        password = password,
    )
}

/**
 * Maps a TOTP token to a CXF totp credential, or `null` when the token is not
 * representable — a counted export skip at the caller ([CxfSecretMapper]).
 *
 * Admissibility is the shared contract in `CxfTotp.kt`, so nothing here can
 * drift from what [mapImportTotpUri] accepts.
 *
 * CXF v1.0 §3.3.16 strongly recommends `username` and `issuer`: both come from
 * the otpauth URI when present, and the username also falls back to
 * [fallbackUsername], the enclosing login's username. The issuer has no
 * fallback — the spec defines it as the relying party that issued the
 * credential, which the item title (the only other candidate) is not.
 */
internal fun mapTotp(
    token: TotpToken,
    fallbackUsername: String? = null,
): CxfCredential.Totp? = when (token) {
    is TotpToken.TotpAuth -> mapTotpAuth(token, fallbackUsername)
    is TotpToken.SteamAuth -> mapSteamAuth(token, fallbackUsername)
    // CXF v1.0 models only time-based OTP. Both are counted export skips.
    is TotpToken.HotpAuth -> null
    is TotpToken.MobileAuth -> null
}

private fun mapTotpAuth(
    totp: TotpToken.TotpAuth,
    fallbackUsername: String?,
): CxfCredential.Totp? {
    val algorithm = mapTotpAlgorithm(totp.algorithm)
    val secret = canonicalTotpSecretOrNull(totp.keyBase32)
    // The two numeric members are admitted together: a digit count the wire
    // cannot carry makes the token unexportable whatever its period says.
    val period = totp.period
        .takeIf { it in CXF_TOTP_PERIOD_RANGE_LONG && totp.digits in CXF_TOTP_DIGITS_RANGE }
        ?.toInt()
    if (algorithm == null || secret == null || period == null) {
        return null
    }
    return CxfCredential.Totp(
        secret = secret,
        period = period,
        digits = totp.digits,
        algorithm = algorithm.wire,
        username = totp.username
            ?: fallbackUsername?.takeIf { it.isNotBlank() },
        issuer = totp.issuer,
    )
}

/**
 * A Steam token carries no period or digits of its own — the algorithm fixes
 * them ([TotpToken.SteamAuth.PERIOD]/[TotpToken.SteamAuth.DIGITS]) — so the
 * constants are emitted and the receiving side is free to ignore them.
 * [TotpToken.SteamAuth] has no issuer and no username, so the username can
 * only come from the enclosing login.
 */
private fun mapSteamAuth(
    steam: TotpToken.SteamAuth,
    fallbackUsername: String?,
): CxfCredential.Totp? {
    val secret = canonicalTotpSecretOrNull(steam.keyBase32)
        ?: return null
    return CxfCredential.Totp(
        secret = secret,
        period = TotpToken.SteamAuth.PERIOD.toInt(),
        digits = TotpToken.SteamAuth.DIGITS,
        algorithm = CxfTotpAlgorithm.Steam.wire,
        username = fallbackUsername?.takeIf { it.isNotBlank() },
        issuer = null,
    )
}

internal fun mapTotpAlgorithm(
    algorithm: CryptoHashAlgorithm,
): CxfTotpAlgorithm.Hash? = when (algorithm) {
    CryptoHashAlgorithm.SHA_1 -> CxfTotpAlgorithm.Hash.Sha1
    CryptoHashAlgorithm.SHA_256 -> CxfTotpAlgorithm.Hash.Sha256
    CryptoHashAlgorithm.SHA_512 -> CxfTotpAlgorithm.Hash.Sha512
    // Unreachable: TotpToken parsing throws on `algorithm=md5`, so no token can
    // carry it. Kept as an exhaustiveness guard — adding a member to the shared
    // hash enum must be a compile error here, not a mis-exported credential.
    CryptoHashAlgorithm.MD5 -> null
}

/**
 * Partitions a login's uris into scope urls and Android application ids.
 * Returns `null` when there is neither.
 *
 * Everything that is not an `androidapp://` entry passes into `urls` verbatim —
 * bare domains, `iosapp://` and other schemes included. CXF v1.0 §3.2.4 only
 * says urls SHOULD follow RFC 3986, and bare domains are common in real vaults
 * (Keyguard's own autofill save flow stores them), so rewriting or refusing
 * them would lose scope an importer can still use. The only entries dropped are
 * the ones that are not scopes at all: blanks, regular-expression match
 * patterns, and Keyguard-private `cmd://` command uris, which must never leak
 * into an interchange file.
 */
internal fun mapScope(
    uris: List<DSecret.Uri>,
): CxfCredentialScope? {
    val urls = mutableListOf<String>()
    val androidApps = mutableListOf<CxfAndroidAppId>()
    uris.forEach { uri ->
        val value = uri.uri.trim()
        when {
            value.isEmpty() -> Unit
            uri.match == DSecret.Uri.MatchType.RegularExpression -> Unit
            value.startsWith(COMMAND_PREFIX, ignoreCase = true) -> Unit

            value.startsWith(PROTOCOL_ANDROID_APP, ignoreCase = true) -> {
                val bundleId = value.substring(PROTOCOL_ANDROID_APP.length)
                if (bundleId.isNotBlank()) {
                    androidApps += CxfAndroidAppId(
                        bundleId = bundleId,
                        certificate = uri.signatures
                            .firstOrNull()
                            ?.let { mapCertificateFingerprint(it) },
                    )
                }
            }

            else -> urls += value
        }
    }
    if (urls.isEmpty() && androidApps.isEmpty()) {
        return null
    }
    return CxfCredentialScope(
        urls = urls,
        androidApps = androidApps,
    )
}

/**
 * Converts a Keyguard signature (a colon-separated, upper-case hex SHA-256
 * fingerprint, e.g. `AB:CD:...`) into the CXF certificate-fingerprint shape:
 * the raw hash bytes base64url-encoded.
 *
 * The value is gated by [normalizeSha256FingerprintOrNull], the same validator
 * every other consumer of a signature uses, so the hard-coded `sha256` label is
 * true by construction: a fingerprint that is not exactly 32 bytes of hex is not
 * a SHA-256 hash. That also makes this the exact inverse of
 * [mapImportCertificateFingerprint], which ends in the same normalizer.
 */
internal fun mapCertificateFingerprint(
    signature: DSecret.Uri.Signature,
): CxfAndroidAppCertificateFingerprint? {
    // Past the normalizer the string is 64 upper-case hex characters, but
    // `hexToByteArray` is not typed as total, so its failure is absorbed here
    // rather than relying on a proof that lives in another file.
    val bytes = signature.certFingerprintSha256
        .normalizeSha256FingerprintOrNull()
        ?.replace(":", "")
        ?.let { hex -> runCatchingNonFatal { hex.hexToByteArray() }.getOrNull() }
        ?: return null
    return CxfAndroidAppCertificateFingerprint(
        fingerprint = PasskeyBase64.encodeToString(bytes),
        hashAlg = CxfAndroidAppCertificateFingerprint.HASH_ALG_SHA256,
    )
}
