package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyInspectionResult
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyMaterial
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.common.service.webauthn.PasskeyCredentialId

internal const val MAX_ENCODED_PASSKEY_KEY_CHARS = 5_464
internal const val MAX_ENCODED_PASSKEY_USER_HANDLE_CHARS = 88
internal const val MAX_PASSKEY_USER_HANDLE_BYTES = 64

/**
 * CTAP 2.1 caps a credential id at 1023 bytes (`maxCredentialIdLength`), so
 * this is the base64url length of 2048 bytes — roughly double the ceiling.
 * The value is opaque to Keyguard and is never parsed, so refusing a real one
 * costs a working passkey while admitting a large one costs nothing; the
 * headroom is deliberate and only a payload built to be a payload lands past
 * it. Applied in both directions so the two mappers stay inverses.
 */
internal const val MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS = 2_732

/**
 * An RP ID is a domain name, which RFC 1035 §2.3.4 bounds to 253 characters in
 * presentation form. The cap is four times that because nothing here parses
 * the value as a domain — it is compared byte-for-byte against a request — so
 * a vault holding some longer non-conforming string still travels, while a
 * megabyte-sized member cannot ride in and wedge a sync.
 */
internal const val MAX_PASSKEY_RP_ID_CHARS = 1_024

/**
 * The export half of the four binary passkey members of CXF v1.0 §3.3.12, plus
 * the length caps and the crypto seam both halves share. The import half lives
 * in `CxfPasskeyImport.kt`.
 *
 * A member that decodes to no bytes, or that is longer than a real one can be,
 * is refused — a credential missing any of them can never produce an assertion
 * — and every refusal is a counted skip at both callers.
 */

/**
 * Normalizes a stored credential id (either a UUID string or a base64url
 * string) into a base64url string. Returns `null` when it cannot be decoded,
 * when it decodes to nothing, or when it is longer than
 * [MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS].
 */
internal fun mapCredentialId(
    credentialId: String,
): String? {
    val bytes = credentialId
        .takeIf { it.length <= MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS }
        ?.let { value -> runCatchingNonFatal { PasskeyCredentialId.encode(value) }.getOrNull() }
        // The decoded value must equal the `rawId` seen at registration
        // (CXF v1.0 §3.3.12), which a WebAuthn Credential ID never leaves empty.
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return try {
        PasskeyBase64.encodeToString(bytes)
    } finally {
        bytes.fill(0)
    }
}

/**
 * Validates a stored PKCS#8 DER private key and emits the native service's
 * canonical P-256 form as base64url. Keyguard's own creation flow persists the
 * value as standard base64, while synced vaults may carry base64url; both
 * alphabets are accepted, but strictly. Undecodable, malformed, or unsupported
 * keys return `null` and become counted skips.
 */
internal fun mapKey(
    keyValue: String,
    passkeyCrypto: PasskeyCrypto,
): String? {
    val der = decodeStoredKeyDer(keyValue)
        ?: return null
    return try {
        passkeyCrypto.inspectKeyMaterialOrNull(der)
            ?.encodeCxfKey()
    } finally {
        der.fill(0)
    }
}

private fun decodeStoredKeyDer(
    keyValue: String,
): ByteArray? = keyValue
    .takeIf { it.length <= MAX_ENCODED_PASSKEY_KEY_CHARS }
    ?.let { value ->
        runCatchingNonFatal { cxfStandardBase64.decode(value) }.getOrNull()
            ?: runCatchingNonFatal { cxfUrlSafeBase64.decode(value) }.getOrNull()
    }
    // A zero-length DER is not a PKCS#8 PrivateKeyInfo and yields no public key.
    ?.takeIf { it.isNotEmpty() }

/**
 * [PasskeyCrypto] is an injected interface, not a promise: the production
 * adapter folds a malformed or unsupported key into
 * [PasskeyKeyInspectionResult.Error], but a backend that cannot initialize
 * throws instead. Unguarded, that throw unwinds past every remaining item of
 * the account and `CxfExportServiceImpl` charges the loss as a single `Account`
 * skip — a 500-item vault exporting nothing because one stored key was
 * unreadable. One unusable key must cost one counted credential skip.
 *
 * The same reasoning as `CxfSecretMapper.mapSshKey`'s guard over
 * `SshKeyPkcs8Exporter`.
 */
internal fun PasskeyCrypto.inspectKeyMaterialOrNull(
    privateKeyPkcs8: ByteArray,
): PasskeyKeyMaterial? {
    val inspection = runCatchingNonFatal { inspect(privateKeyPkcs8) }
        .getOrNull()
    return when (inspection) {
        is PasskeyKeyInspectionResult.Success -> inspection.keyMaterial
        is PasskeyKeyInspectionResult.Error -> null
        null -> null
    }
}

private fun PasskeyKeyMaterial.encodeCxfKey(): String = try {
    PasskeyBase64.encodeToString(privateKeyPkcs8)
} finally {
    clear()
}

/**
 * An RP ID is a domain that `requireCredentialRpIdMatchesRequest` compares
 * byte-for-byte, so a blank one can never be asserted against and is rejected in
 * both directions, as is one no domain name could be
 * ([MAX_PASSKEY_RP_ID_CHARS]). What survives is passed through verbatim:
 * trimming or case-folding it would silently rewrite stored data.
 */
internal fun mapRpId(
    rpId: String,
): String? = rpId
    .takeIf { it.isNotBlank() && it.length <= MAX_PASSKEY_RP_ID_CHARS }

/**
 * Normalizes a stored base64url user handle (padded or unpadded) through a
 * decode/encode round-trip into the unpadded form CXF requires. Returns `null`
 * when the value is not valid base64url, or when it decodes to nothing.
 *
 * Export-only. CXF v1.0 §3.3.12 makes `userHandle` a required member with no way
 * to express absence, and its value MUST equal `PublicKeyCredentialUserEntity`'s
 * `id`, which WebAuthn L3 §5.4.3 bounds to 1..64 bytes. The import direction
 * reads the same wire member very differently; see [CxfImportUserHandle].
 */
internal fun mapUserHandle(
    userHandle: String,
): String? {
    val bytes = userHandle
        .takeIf { it.length <= MAX_ENCODED_PASSKEY_USER_HANDLE_CHARS }
        ?.let { value -> runCatchingNonFatal { cxfUrlSafeBase64.decode(value) }.getOrNull() }
        ?.takeIf { it.size in 1..MAX_PASSKEY_USER_HANDLE_BYTES }
        ?: return null
    return try {
        PasskeyBase64.encodeToString(bytes)
    } finally {
        bytes.fill(0)
    }
}
