package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfFido2Extensions
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfFido2HmacCredentials
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeHashAlgorithm
import com.artemchep.keyguard.util.webauthn.MAX_WEBAUTHN_PRF_SECRET_BYTES
import com.artemchep.keyguard.util.webauthn.PasskeyBase64

/**
 * Both halves of the passkey `hmacCredentials` of CXF v1.0 §3.3.12.3, the
 * seeds of the WebAuthn PRF extension. WebAuthn exposes a single PRF, the one
 * hmac-secret uses after user verification (WebAuthn L3 §10.1.4), so Keyguard's
 * secret is `credWithUV`.
 *
 * The section has an exporter that holds one seed "generate the missing
 * credential", identical across all future exchange sessions. Keyguard derives
 * it ([derivePrfSecretWithoutUv]), so every device and every export agree on it
 * without storing it; only an imported seed that differs is stored.
 *
 * CXF recommends 32-byte seeds but does not impose a maximum length.
 *
 * A seed that cannot travel costs its whole passkey, as a counted skip, in
 * both directions: an importer "MUST store and use these credential as-is",
 * and a passkey without its seed gives a site different keys.
 */

/** What the PRF data of a passkey turned out to be. */
internal sealed interface CxfPasskeyPrf<out T> {
    /** No PRF data, or an entry of an unknown algorithm, which §3.3.12.3 has an importer ignore. */
    data object None : CxfPasskeyPrf<Nothing>

    data class Present<out T>(val value: T) : CxfPasskeyPrf<T>

    /** A seed that is missing, empty, does not decode, or is over the cap. */
    data object Unusable : CxfPasskeyPrf<Nothing>
}

internal fun <T> CxfPasskeyPrf<T>.valueOrNull(): T? = (this as? CxfPasskeyPrf.Present)?.value

// The import applies the cap that an export reads stored secrets
// with, so the two mappers stay inverses.

// The padded base64 length of MAX_WEBAUTHN_PRF_SECRET_BYTES.
private const val MAX_ENCODED_PASSKEY_PRF_SECRET_CHARS = (MAX_WEBAUTHN_PRF_SECRET_BYTES + 2) / 3 * 4

// "credWithoutUV" in UTF-8.
private val PRF_SECRET_WITHOUT_UV_LABEL = "credWithoutUV".encodeToByteArray()

/**
 * The `credWithoutUV` of a passkey that has only its PRF secret:
 * `HMAC-SHA-256(secret, "credWithoutUV")`.
 */
internal fun derivePrfSecretWithoutUv(
    prfSecret: ByteArray,
): ByteArray = NativeCrypto.primitives.hmac(
    key = prfSecret,
    data = PRF_SECRET_WITHOUT_UV_LABEL,
    algorithm = NativeHashAlgorithm.SHA_256,
)

/**
 * The extensions of an exported passkey. A stored `credWithoutUV` is exported
 * as it was imported, any other is derived.
 */
internal fun mapPasskeyExtensions(
    credential: DSecret.Login.Fido2Credentials,
): CxfPasskeyPrf<CxfFido2Extensions> {
    val prfSecret = credential.prfSecret
    val prfSecretWithoutUv = credential.prfSecretWithoutUv
    return when {
        prfSecret != null -> mapPasskeyHmacCredentials(prfSecret, prfSecretWithoutUv)
        // A seed without user verification is no use without the other one.
        prfSecretWithoutUv != null -> CxfPasskeyPrf.Unusable
        else -> CxfPasskeyPrf.None
    }
}

private fun mapPasskeyHmacCredentials(
    prfSecret: String,
    prfSecretWithoutUv: String?,
): CxfPasskeyPrf<CxfFido2Extensions> {
    val withUv = PasskeyBase64.decodePrfSecretOrNull(prfSecret)
    val withoutUv = prfSecretWithoutUv?.let(PasskeyBase64::decodePrfSecretOrNull)
    return try {
        if (withUv == null || prfSecretWithoutUv != null && withoutUv == null) {
            CxfPasskeyPrf.Unusable
        } else {
            (withoutUv ?: derivePrfSecretWithoutUv(withUv)).useAndClear { seedWithoutUv ->
                val hmacCredentials = CxfFido2HmacCredentials(
                    algorithm = CxfFido2HmacCredentials.ALGORITHM_HMAC_SHA256,
                    credWithUv = PasskeyBase64.encodeToString(withUv),
                    credWithoutUv = PasskeyBase64.encodeToString(seedWithoutUv),
                )
                CxfPasskeyPrf.Present(CxfFido2Extensions(hmacCredentials = hmacCredentials))
            }
        }
    } finally {
        withUv?.fill(0)
    }
}

/** The PRF secrets of an imported passkey, base64url-encoded. */
internal data class CxfImportedPrfSecrets(
    val prfSecret: String,
    val prfSecretWithoutUv: String?,
)

/**
 * The PRF secrets of an imported passkey. An entry with an unknown algorithm
 * is ignored, as §3.3.12.3 asks. A missing `credWithoutUV` is derived on
 * export, and one equal to the derived seed is not stored, so a passkey
 * Keyguard exported comes back as it left.
 */
internal fun mapImportPasskeyPrfSecrets(
    extensions: CxfFido2Extensions?,
): CxfPasskeyPrf<CxfImportedPrfSecrets> {
    val hmacCredentials = extensions?.hmacCredentials
        ?.takeIf { it.algorithm == CxfFido2HmacCredentials.ALGORITHM_HMAC_SHA256 }
    return if (hmacCredentials != null) {
        mapImportHmacCredentials(hmacCredentials)
    } else {
        CxfPasskeyPrf.None
    }
}

private fun mapImportHmacCredentials(
    hmacCredentials: CxfFido2HmacCredentials,
): CxfPasskeyPrf<CxfImportedPrfSecrets> {
    val withUv = hmacCredentials.credWithUv?.let(::decodeImportPrfSecretOrNull)
    val withoutUv = hmacCredentials.credWithoutUv?.let(::decodeImportPrfSecretOrNull)
    return try {
        if (withUv == null || hmacCredentials.credWithoutUv != null && withoutUv == null) {
            CxfPasskeyPrf.Unusable
        } else {
            val secrets = CxfImportedPrfSecrets(
                prfSecret = PasskeyBase64.encodeToString(withUv),
                prfSecretWithoutUv = withoutUv
                    ?.takeUnless { isDerivedPrfSecretWithoutUv(withUv, it) }
                    ?.let(PasskeyBase64::encodeToString),
            )
            CxfPasskeyPrf.Present(secrets)
        }
    } finally {
        withUv?.fill(0)
        withoutUv?.fill(0)
    }
}

private fun isDerivedPrfSecretWithoutUv(
    prfSecret: ByteArray,
    prfSecretWithoutUv: ByteArray,
): Boolean = derivePrfSecretWithoutUv(prfSecret).useAndClear { it.contentEquals(prfSecretWithoutUv) }

private fun decodeImportPrfSecretOrNull(
    value: String,
): ByteArray? = value
    .takeIf { it.length <= MAX_ENCODED_PASSKEY_PRF_SECRET_CHARS }
    ?.let { runCatchingNonFatal { cxfUrlSafeBase64.decode(it) }.getOrNull() }
    ?.takeIf { it.size in 1..MAX_WEBAUTHN_PRF_SECRET_BYTES }
