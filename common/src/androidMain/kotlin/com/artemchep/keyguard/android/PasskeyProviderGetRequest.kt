package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.domerrors.SyntaxError
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
import com.artemchep.keyguard.util.webauthn.WebAuthnException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotAllowedException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotSupportedException
import com.artemchep.keyguard.util.webauthn.WebAuthnSyntaxException
import com.artemchep.keyguard.util.webauthn.parseWebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.parseWebAuthnPrfAssertionInputs
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
        val body = json.parseToJsonElement(opt.requestJson) as? JsonObject

        val origin = passkeyUtils.callingAppOrigin(
            appInfo = request.callingAppInfo,
            privilegedApps = privilegedApps,
        )
        val packageName = request.callingAppInfo.packageName
        val rpId = passkeyUtils.resolveAndValidateRpId(
            rpId = requestRpIdOrNull(body),
            origin = origin,
            packageName = packageName,
        )
        val responseJson = mapGetWebAuthnExceptions {
            val allowedCredentials = parseWebAuthnAllowedCredentialDescriptors(body)
            authenticator.getAssertion(
                request = WebAuthnAssertionRequest(
                    challenge = js.challenge,
                    userVerification = js.userVerification,
                    allowedCredentials = allowedCredentials,
                    prf = parseWebAuthnPrfAssertionInputs(body, allowedCredentials),
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
        body: JsonObject?,
    ): String? {
        if (body == null || !body.containsKey("rpId")) {
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
    } catch (e: WebAuthnException) {
        val domError = when (e) {
            is WebAuthnEncodingException -> EncodingError()
            is WebAuthnNotAllowedException -> NotAllowedError()
            is WebAuthnNotSupportedException -> NotSupportedError()
            is WebAuthnSyntaxException -> SyntaxError()
            else -> throw e
        }
        throw GetPublicKeyCredentialDomException(
            domError = domError,
            errorMessage = e.message.orEmpty(),
        ).apply {
            initCause(e)
        }
    }
}
