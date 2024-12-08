package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.webauthn.PublicKeyCredentialRequestOptions
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.text.Base64Service
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class PasskeyProviderGetRequest(
    private val context: Context,
    private val json: Json,
    private val base64Service: Base64Service,
    private val cryptoService: CryptoGenerator,
    private val passkeyUtils: PasskeyUtils,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
        json = directDI.instance(),
        base64Service = directDI.instance(),
        cryptoService = directDI.instance(),
        passkeyUtils = directDI.instance(),
    )

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    suspend fun processGetCredentialsRequest(
        request: ProviderGetCredentialRequest,
        credential: DSecret.Login.Fido2Credentials,
        userVerified: Boolean,
    ): GetCredentialResponse {
        val opt = request.credentialOptions.first() as GetPublicKeyCredentialOption
        val js = PublicKeyCredentialRequestOptions(opt.requestJson)

        val challenge = PasskeyBase64.encodeToString(js.challenge)
        val rpId = js.rpId
        val origin = passkeyUtils.callingAppOrigin(request.callingAppInfo)
        val packageName = request.callingAppInfo.packageName
        passkeyUtils.requireRpMatchesOrigin(
            rpId = rpId,
            origin = origin,
            packageName = packageName,
        )

        val credentialIdBytes = PasskeyCredentialId.encode(credential.credentialId)

        val factory: KeyFactory = KeyFactory.getInstance("EC")
        val privateKey = run {
            val privateKeyData = base64Service.decode(credential.keyValue)
            val privateKeySpec = PKCS8EncodedKeySpec(privateKeyData)
            factory.generatePrivate(privateKeySpec)
        }

        val counter = kotlin.run {
            val tmp = credential.counter ?: 0
            if (tmp > 0) {
                // TODO: When Bitwarden is ready, switch it to be a
                //  UNIX timestamp.
                tmp
            } else {
                0
            }
        }
        val defaultAuthenticatorData = passkeyUtils.authData(
            rpId = rpId,
            counter = counter,
            credentialId = credentialIdBytes,
            credentialPublicKey = null,
            // True, if we asked a user to enter the password of
            // biometrics and he has passed the check.
            userVerification = userVerified,
            userPresence = true,
        )

        val clientDataJsonBytes = kotlin.run {
            val jsonObject = buildJsonObject {
                put("type", "webauthn.get")
                put("challenge", challenge)
                put("origin", origin)
                put("androidPackageName", packageName)
            }
            json.encodeToString(jsonObject)
                .toByteArray()
        }
        val clientDataJsonHash = opt.clientDataHash
            ?: cryptoService.hashSha256(clientDataJsonBytes)

        val signature = kotlin.run {
            val dataToSign = defaultAuthenticatorData + clientDataJsonHash
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(privateKey)
            sig.update(dataToSign)
            sig.sign()
        }

        val r = buildJsonObject {
            put("clientDataJSON", clientDataJsonBytes)
            put("authenticatorData", defaultAuthenticatorData)
            put("signature", PasskeyBase64.encodeToString(signature))
            put("userHandle", credential.userHandle)
        }
        val authenticationResponse = buildJsonObject {
            put("id", PasskeyBase64.encodeToString(credentialIdBytes))
            put("rawId", credentialIdBytes)
            put("type", "public-key")
            put("authenticatorAttachment", "cross-platform")
            put("response", r)
            put("clientExtensionResults", buildJsonObject { })
        }
        val authenticationResponseJson = json.encodeToString(authenticationResponse)
        val passkeyCredential = PublicKeyCredential(authenticationResponseJson)
        return GetCredentialResponse(passkeyCredential)
    }

    private fun JsonObjectBuilder.put(key: String, data: ByteArray) {
        put(key, PasskeyBase64.encodeToString(data))
    }
}
