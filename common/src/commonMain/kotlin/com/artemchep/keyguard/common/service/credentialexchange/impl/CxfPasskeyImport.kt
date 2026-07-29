package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyMaterial
import com.artemchep.keyguard.common.service.crypto.PasskeyKeyProfile
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.common.service.webauthn.PasskeyCredentialId
import kotlin.time.Instant

/**
 * The import half of the four binary passkey members of CXF v1.0 §3.3.12. The
 * export half, the length caps and the crypto seam both halves share live in
 * `CxfPasskey.kt`; the split is by direction, so nothing here is reachable from
 * an export.
 *
 * `credentialId`, `key` and `rpId` are the inverses of their export
 * counterparts. `userHandle`'s are deliberately **not** — see
 * [CxfImportUserHandle].
 */

/**
 * Converts a base64url credential id into Keyguard's stored form — a UUID
 * string when the id is exactly 16 bytes, the base64url string otherwise. The
 * inverse of [mapCredentialId] for every input both directions accept; an empty
 * id, and one past [MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS], are refused on
 * both sides.
 */
internal fun mapImportCredentialId(
    credentialId: String,
): String? {
    val bytes = credentialId
        .takeIf { it.length <= MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS }
        ?.let { value -> runCatchingNonFatal { cxfUrlSafeBase64.decode(value) }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return try {
        PasskeyCredentialId.decode(bytes)
    } finally {
        bytes.fill(0)
    }
}

/**
 * Validates a base64url PKCS#8 private key and returns the native service's
 * canonical P-256 form in the padded standard-base64 representation Keyguard
 * stores. The alphabet stays strict so corrupted or unsupported key material
 * becomes a counted skip instead of an unusable passkey in the vault.
 */
internal fun mapImportKey(
    key: String,
    passkeyCrypto: PasskeyCrypto,
): CxfImportedPasskeyKey? {
    val der = decodeImportKeyDer(key)
        ?: return null
    return try {
        passkeyCrypto.inspectKeyMaterialOrNull(der)
            ?.encodeImportedKey()
    } finally {
        der.fill(0)
    }
}

private fun decodeImportKeyDer(
    key: String,
): ByteArray? = key
    .takeIf { it.length <= MAX_ENCODED_PASSKEY_KEY_CHARS }
    ?.let { value -> runCatchingNonFatal { cxfUrlSafeBase64.decode(value) }.getOrNull() }
    ?.takeIf { it.isNotEmpty() }

internal data class CxfImportedPasskeyKey(
    val keyValue: String,
    val profile: PasskeyKeyProfile,
)

private fun PasskeyKeyMaterial.encodeImportedKey(): CxfImportedPasskeyKey = try {
    CxfImportedPasskeyKey(
        keyValue = cxfStandardBase64.encode(privateKeyPkcs8),
        profile = profile,
    )
} finally {
    clear()
}

/**
 * What an imported `userHandle` member turned out to be.
 *
 * CXF v1.0 §3.3.12 makes the member required and gives a producer no way to say
 * "this credential has no user handle", so a producer in that bind writes `""`.
 * Reading that as [Absent] keeps an otherwise complete credential usable in the
 * `allowCredentials`-driven flow instead of dropping it, and
 * [mapDecodedImportPasskey] stops advertising it for the usernameless ceremony.
 * Export refuses an empty handle instead ([mapUserHandle]); the asymmetry is
 * pinned by tests.
 */
internal sealed interface CxfImportUserHandle {
    data class Present(val value: String) : CxfImportUserHandle

    data object Absent : CxfImportUserHandle

    data object Undecodable : CxfImportUserHandle
}

internal fun mapImportUserHandle(
    userHandle: String,
): CxfImportUserHandle = when {
    userHandle.isEmpty() -> CxfImportUserHandle.Absent
    userHandle.length > MAX_ENCODED_PASSKEY_USER_HANDLE_CHARS -> CxfImportUserHandle.Undecodable
    else -> runCatchingNonFatal { cxfUrlSafeBase64.decode(userHandle) }
        .getOrNull()
        ?.let(::mapDecodedImportUserHandle)
        ?: CxfImportUserHandle.Undecodable
}

private fun mapDecodedImportUserHandle(
    bytes: ByteArray,
): CxfImportUserHandle = try {
    if (bytes.isEmpty()) {
        // A non-empty spelling that decodes to no bytes says the same thing `""`
        // does; kept so a laxer codec cannot store an empty handle.
        CxfImportUserHandle.Absent
    } else if (bytes.size > MAX_PASSKEY_USER_HANDLE_BYTES) {
        CxfImportUserHandle.Undecodable
    } else {
        CxfImportUserHandle.Present(PasskeyBase64.encodeToString(bytes))
    }
} finally {
    bytes.fill(0)
}

/**
 * Maps an imported passkey into Keyguard's stored form, or returns `null` (a
 * counted skip) when the credential id, the private key or the rp id is
 * missing, oversized or undecodable — the three members without which no
 * assertion can ever be produced.
 *
 * The `userHandle` member is the exception: an empty one is read as *absent* and
 * the passkey is kept. See [CxfImportUserHandle].
 *
 * CXF v1.0 §3.3.12 has no key-algorithm member — only the PKCS#8 `key` — so the
 * algorithm and curve are derived from validated key material. Unsupported
 * profiles are skipped. The section requires importers to set a zero signature
 * counter. Discoverability is derived; see [mapDecodedImportPasskey].
 */
internal fun mapImportPasskey(
    passkey: CxfCredential.Passkey,
    creationDate: Instant,
    passkeyCrypto: PasskeyCrypto,
): DSecret.Login.Fido2Credentials? =
    when (val handle = mapImportUserHandle(passkey.userHandle)) {
        CxfImportUserHandle.Undecodable -> null
        CxfImportUserHandle.Absent ->
            mapDecodedImportPasskey(passkey, null, creationDate, passkeyCrypto)

        is CxfImportUserHandle.Present ->
            mapDecodedImportPasskey(passkey, handle.value, creationDate, passkeyCrypto)
    }

/**
 * Assembles the stored credential. `discoverable` is derived from the user
 * handle rather than hardcoded: it is the flag that lets Keyguard answer a
 * request carrying no `allowCredentials` (`WebAuthnAllowedCredentialDescriptors.allows`,
 * `PasskeyTargetCheckImpl`), and an assertion for such a ceremony omits
 * `userHandle` when there is none, which WebAuthn L3 §7.2 has the relying party
 * abort on. With the flag off it stays usable in the allowCredentials-driven
 * flow, where an absent handle is permitted.
 */
private fun mapDecodedImportPasskey(
    passkey: CxfCredential.Passkey,
    userHandle: String?,
    creationDate: Instant,
    passkeyCrypto: PasskeyCrypto,
): DSecret.Login.Fido2Credentials? {
    val credentialId = mapImportCredentialId(passkey.credentialId)
    val key = mapImportKey(passkey.key, passkeyCrypto)
    val rpId = mapRpId(passkey.rpId)
    if (credentialId == null || key == null || rpId == null) {
        return null
    }
    return DSecret.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = key.profile.keyAlgorithm,
        keyCurve = key.profile.keyCurve,
        keyValue = key.keyValue,
        rpId = rpId,
        rpName = null,
        counter = 0,
        userHandle = userHandle,
        userName = passkey.username.takeIf { it.isNotBlank() },
        userDisplayName = passkey.userDisplayName.takeIf { it.isNotBlank() },
        discoverable = userHandle != null,
        creationDate = creationDate,
    )
}
