package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.DSecret
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
