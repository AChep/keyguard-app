package com.artemchep.keyguard.provider.bitwarden.entity.api

import com.artemchep.keyguard.common.util.to6DigitsNanosOfSecond
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginFido2CredentialsRequest(
    @SerialName("credentialId")
    val credentialId: String? = null,
    @SerialName("keyType")
    val keyType: String,
    @SerialName("keyAlgorithm")
    val keyAlgorithm: String,
    @SerialName("keyCurve")
    val keyCurve: String,
    @SerialName("keyValue")
    val keyValue: String,
    @SerialName("rpId")
    val rpId: String,
    @SerialName("rpName")
    val rpName: String?,
    @SerialName("counter")
    val counter: String,
    @SerialName("userHandle")
    val userHandle: String,
    @SerialName("userName")
    val userName: String?,
    @SerialName("userDisplayName")
    val userDisplayName: String?,
    @SerialName("discoverable")
    val discoverable: String,
    @SerialName("creationDate")
    val creationDate: Instant,
) {
    companion object
}

fun LoginFido2CredentialsRequest.Companion.of(
    model: BitwardenCipher.Login.Fido2Credentials,
) = kotlin.run {
    LoginFido2CredentialsRequest(
        credentialId = model.credentialId,
        keyType = model.keyType,
        keyAlgorithm = model.keyAlgorithm,
        keyCurve = model.keyCurve,
        keyValue = model.keyValue,
        rpId = model.rpId,
        rpName = model.rpName,
        counter = model.counter,
        userHandle = model.userHandle,
        userName = model.userName,
        userDisplayName = model.userDisplayName,
        discoverable = model.discoverable,
        creationDate = model.creationDate
            .to6DigitsNanosOfSecond(),
    )
}
