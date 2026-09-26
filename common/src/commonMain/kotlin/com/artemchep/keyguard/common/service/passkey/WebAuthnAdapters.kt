package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
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

internal fun DSecret.Login.Fido2Credentials.toWebAuthnCredential() = WebAuthnCredential(
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

internal fun WebAuthnCredential.toAddCredentialCipherRequest() = AddCredentialCipherRequestPasskeyData(
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

internal fun List<DSecret>.availablePasskeyCredentials(): List<DSecret.Login.Fido2Credentials> =
    asSequence()
        .filter { !it.archived && !it.deleted }
        .flatMap { it.login?.fido2Credentials.orEmpty() }
        .toList()

internal fun WebAuthnAllowedCredentialDescriptors.toPasskeyTargetCredentials(): List<PasskeyTarget.AllowedCredential>? =
    if (isAllowCredentialsSupplied) {
        descriptors.map { PasskeyTarget.AllowedCredential(credentialId = it.credentialId, type = it.type) }
    } else {
        null
    }
