package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.provider.ProviderCreateCredentialRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestData
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasswordData
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.passkey.availablePasskeyCredentials
import com.artemchep.keyguard.common.service.passkey.toAddCredentialCipherRequest
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnCallerContext
import com.artemchep.keyguard.util.webauthn.WebAuthnEncodingException
import com.artemchep.keyguard.util.webauthn.WebAuthnException
import com.artemchep.keyguard.util.webauthn.WebAuthnInvalidStateException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotSupportedException
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import kotlinx.serialization.json.Json
import com.artemchep.keyguard.util.webauthn.decodeExcludedCredentialIds as decodeWebAuthnExcludedCredentialIds

@SuppressLint("RestrictedApi")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyCreateRequest(
    private val json: Json,
    private val passkeyUtils: PasskeyUtils,
    private val authenticator: WebAuthnAuthenticator,
) {

    sealed interface PreparedCreateCredentialRequest {
        data class PublicKey(
            val data: CreatePasskey,
            val origin: String,
            val packageName: String,
            val rpId: String,
        ) : PreparedCreateCredentialRequest

        data class Password(
            val data: CreatePasswordRequest,
            val origin: String,
            val packageName: String,
            val uri: String?,
        ) : PreparedCreateCredentialRequest
    }

    suspend fun prepareCreateCredentialsRequest(
        request: ProviderCreateCredentialRequest,
        privilegedApps: List<DPrivilegedApp>,
    ): PreparedCreateCredentialRequest =
        when (val callingRequest = request.callingRequest) {
            is CreatePublicKeyCredentialRequest -> {
                val data = json.decodeFromString<CreatePasskey>(callingRequest.requestJson)
                decodeExcludedCredentialIds(data)
                val origin = passkeyUtils.callingAppOrigin(
                    appInfo = request.callingAppInfo,
                    privilegedApps = privilegedApps,
                )
                val packageName = request.callingAppInfo.packageName
                val rpId = passkeyUtils.resolveAndValidateRpId(
                    rpId = data.rp.id,
                    origin = origin,
                    packageName = packageName,
                )
                PreparedCreateCredentialRequest.PublicKey(
                    data = data,
                    origin = origin,
                    packageName = packageName,
                    rpId = rpId,
                )
            }

            is CreatePasswordRequest -> {
                val origin = passkeyUtils.callingAppOrigin(
                    appInfo = request.callingAppInfo,
                    privilegedApps = privilegedApps,
                )
                val packageName = request.callingAppInfo.packageName
                val uri = if (!request.callingAppInfo.isOriginPopulated()) {
                    "androidapp://$packageName"
                } else {
                    null
                }
                PreparedCreateCredentialRequest.Password(
                    data = callingRequest,
                    origin = origin,
                    packageName = packageName,
                    uri = uri,
                )
            }

            else -> {
                val msg = "Unsupported create credential request!"
                throw IllegalArgumentException(msg)
            }
        }

    fun processCreateCredentialsRequest(
        request: PreparedCreateCredentialRequest,
        userVerified: Boolean,
        ciphers: List<DSecret> = emptyList(),
    ): Pair<CreateCredentialResponse, AddCredentialCipherRequestData> =
        when (request) {
            is PreparedCreateCredentialRequest.PublicKey ->
                processCreatePublicKeyCredentialsRequest(
                    request = request,
                    userVerified = userVerified,
                    ciphers = ciphers,
                )

            is PreparedCreateCredentialRequest.Password ->
                processCreatePasswordRequest(
                    request = request,
                )
        }

    private fun processCreatePasswordRequest(
        request: PreparedCreateCredentialRequest.Password,
    ): Pair<CreatePasswordResponse, AddCredentialCipherRequestPasswordData> {
        val data = request.data

        val local = AddCredentialCipherRequestPasswordData(
            id = data.id,
            password = data.password,
            callingAppInfo = AddCredentialCipherRequestPasswordData.CallingAppInfo(
                origin = request.origin,
                packageName = request.packageName,
            ),
        )
        val response = CreatePasswordResponse().apply {
            // Taken from
            // androidx.credentials.PasswordCredential
            this.data.putString("androidx.credentials.BUNDLE_KEY_ID", data.id)
            this.data.putString("androidx.credentials.BUNDLE_KEY_PASSWORD", data.password)
        }
        return response to local
    }

    private fun processCreatePublicKeyCredentialsRequest(
        request: PreparedCreateCredentialRequest.PublicKey,
        userVerified: Boolean,
        ciphers: List<DSecret>,
    ): Pair<CreatePublicKeyCredentialResponse, AddCredentialCipherRequestPasskeyData> {
        val result = mapCreateWebAuthnExceptions {
            authenticator.createCredential(
                data = request.data,
                context = WebAuthnCallerContext(request.origin, request.rpId, request.packageName),
                userVerified = userVerified,
                transports = if (request.rpId == "google.com") listOf("internal", "usb") else listOf("internal"),
                credentials = ciphers.availablePasskeyCredentials().map { it.toWebAuthnCredential() },
            )
        }
        return CreatePublicKeyCredentialResponse(result.responseJson) to
            result.credential.toAddCredentialCipherRequest()
    }
}

private fun decodeExcludedCredentialIds(
    data: CreatePasskey,
): Set<String> = mapCreateWebAuthnExceptions {
    decodeWebAuthnExcludedCredentialIds(data)
}

internal inline fun <T> mapCreateWebAuthnExceptions(
    block: () -> T,
): T {
    try {
        return block()
    } catch (e: WebAuthnException) {
        val domError = when (e) {
            is WebAuthnNotSupportedException -> NotSupportedError()
            is WebAuthnEncodingException -> EncodingError()
            is WebAuthnInvalidStateException -> InvalidStateError()
            else -> throw e
        }
        throw CreatePublicKeyCredentialDomException(
            domError = domError,
            errorMessage = e.message.orEmpty(),
        ).apply { initCause(e) }
    }
}
