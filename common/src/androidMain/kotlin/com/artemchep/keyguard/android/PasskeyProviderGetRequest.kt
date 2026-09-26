package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.webauthn.PublicKeyCredentialRequestOptions
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnCallerContext
import com.artemchep.keyguard.util.webauthn.WebAuthnEncodingException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotAllowedException
import com.artemchep.keyguard.util.webauthn.parseWebAuthnAllowedCredentialDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class PasskeyProviderGetRequest(
    private val json: Json,
    private val passkeyUtils: PasskeyUtils,
    private val authenticator: WebAuthnAuthenticator,
) {

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun processGetCredentialsRequest(
        request: ProviderGetCredentialRequest,
        credential: DSecret.Login.Fido2Credentials,
        userVerified: Boolean,
        privilegedApps: List<DPrivilegedApp>,
    ): GetCredentialResponse {
        val opt = request.credentialOptions.first() as GetPublicKeyCredentialOption
        val js = PublicKeyCredentialRequestOptions(opt.requestJson)

        val origin = passkeyUtils.callingAppOrigin(
            appInfo = request.callingAppInfo,
            privilegedApps = privilegedApps,
        )
        val packageName = request.callingAppInfo.packageName
        val rpId = passkeyUtils.resolveAndValidateRpId(
            rpId = requestRpIdOrNull(opt.requestJson),
            origin = origin,
            packageName = packageName,
        )
        val responseJson = mapGetWebAuthnExceptions {
            authenticator.getAssertion(
                request = WebAuthnAssertionRequest(
                    challenge = js.challenge,
                    userVerification = js.userVerification,
                    allowedCredentials = parseWebAuthnAllowedCredentialDescriptors(opt.requestJson, json),
                ),
                context = WebAuthnCallerContext(origin, rpId, packageName),
                credential = credential.toWebAuthnCredential(),
                userVerified = userVerified,
                clientDataHash = opt.clientDataHash,
            )
        }
        return GetCredentialResponse(PublicKeyCredential(responseJson))
    }

    private fun requestRpIdOrNull(
        requestJson: String,
    ): String? {
        val body = json.parseToJsonElement(requestJson) as? JsonObject
            ?: return null
        if (!body.containsKey("rpId")) {
            return null
        }

        val primitive = body["rpId"] as? JsonPrimitive
        return primitive?.contentOrNull.orEmpty()
    }
}

internal inline fun <T> mapGetWebAuthnExceptions(
    block: () -> T,
): T {
    try {
        return block()
    } catch (e: WebAuthnEncodingException) {
        throw GetPublicKeyCredentialDomException(
            domError = EncodingError(),
            errorMessage = e.message.orEmpty(),
        ).apply {
            initCause(e)
        }
    } catch (e: WebAuthnNotAllowedException) {
        throw GetPublicKeyCredentialDomException(
            domError = NotAllowedError(),
            errorMessage = e.message.orEmpty(),
        ).apply {
            initCause(e)
        }
    }
}
