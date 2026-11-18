package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canEdit
import com.artemchep.keyguard.util.webauthn.WebAuthnCredential

internal val KEYGUARD_PASSKEY_AAGUID = byteArrayOf(
    0xd5.toByte(),
    0x48.toByte(),
    0x82.toByte(),
    0x6e.toByte(),
    0x79.toByte(),
    0xb4.toByte(),
    0xdb.toByte(),
    0x40.toByte(),
    0xa3.toByte(),
    0xd8.toByte(),
    0x11.toByte(),
    0x11.toByte(),
    0x6f.toByte(),
    0x7e.toByte(),
    0x83.toByte(),
    0x49.toByte(),
)

fun DSecret.Login.Fido2Credentials.toWebAuthnCredential() = WebAuthnCredential(
    credentialId = credentialId,
    keyType = keyType,
    keyAlgorithm = keyAlgorithm,
    keyCurve = keyCurve,
    keyValue = keyValue,
    rpId = rpId,
    rpName = rpName,
    counter = counter,
    userHandle = userHandle,
    userName = userName,
    userDisplayName = userDisplayName,
    discoverable = discoverable,
    prfSecret = prfSecret,
)

fun WebAuthnCredential.toAddCredentialCipherRequest() = AddCredentialCipherRequestPasskeyData(
    credentialId = credentialId,
    keyType = keyType,
    keyAlgorithm = keyAlgorithm,
    keyCurve = keyCurve,
    keyValue = keyValue,
    rpId = rpId,
    rpName = rpName,
    counter = counter,
    userHandle = requireNotNull(userHandle),
    userName = userName,
    userDisplayName = userDisplayName,
    discoverable = discoverable,
    prfSecret = prfSecret,
)

/** The passkeys of items that are neither archived nor in the trash, each with its item. */
internal fun List<DSecret>.availablePasskeys(): List<Pair<DSecret, DSecret.Login.Fido2Credentials>> =
    asSequence()
        .filter { !it.archived && !it.deleted }
        .flatMap { cipher -> cipher.login?.fido2Credentials.orEmpty().map { cipher to it } }
        .toList()

/**
 * The passkeys a registration's `excludeCredentials` can name: an archived item still
 * stores its passkeys, so they still exclude another one for the account; the trash does not.
 */
internal fun List<DSecret>.storedPasskeyCredentials(): List<DSecret.Login.Fido2Credentials> =
    asSequence()
        .filter { !it.deleted }
        .flatMap { it.login?.fido2Credentials.orEmpty() }
        .toList()

/**
 * Whether a new passkey may be stored in this item: an editable login, in a vault the user
 * [can write][canWrite] to, and in none of the read-only [collections]. It does not check
 * whether the item is trashed or archived: callers exclude those first.
 */
internal fun DSecret.canStorePasskey(
    canWrite: Boolean,
    collections: List<DCollection>,
): Boolean = type == DSecret.Type.Login &&
    service.canEdit() &&
    canWrite &&
    collections.none { it.accountId == accountId && it.id in collectionIds && it.readOnly }
