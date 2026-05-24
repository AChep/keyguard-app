package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.webauthn.PublicKeyCredentialRequestOptions
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import kotlinx.serialization.Serializable
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
        privilegedApps: List<DPrivilegedApp>,
    ): GetCredentialResponse {
        val opt = request.credentialOptions.first() as GetPublicKeyCredentialOption
        val js = PublicKeyCredentialRequestOptions(opt.requestJson)

        val challenge = PasskeyBase64.encodeToString(js.challenge)
        val rpId = js.rpId
        val origin = passkeyUtils.callingAppOrigin(
            appInfo = request.callingAppInfo,
            privilegedApps = privilegedApps,
        )
        val packageName = request.callingAppInfo.packageName
        passkeyUtils.requireRpMatchesOrigin(
            rpId = rpId,
            origin = origin,
            packageName = packageName,
        )

        val credentialIdBytes = PasskeyCredentialId.encode(credential.credentialId)

        val factory: KeyFactory = KeyFactory.getInstance("EC")
        val privateKeyBytes = base64Service.decode(credential.keyValue)
        val privateKey = run {
            val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
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
            userVerification = passkeyUtils.userVerification(
                mode = js.userVerification,
                userVerified = userVerified,
            ),
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
        val prfResults = resolvePrfEvalInput(opt.requestJson, credentialIdBytes)
            ?.let { eval ->
                buildJsonObject {
                    val firstOutput = passkeyUtils.computePrf(
                        privateKeyBytes = privateKeyBytes,
                        prfInput = PasskeyBase64.decode(eval.first),
                    )
                    put("first", PasskeyBase64.encodeToString(firstOutput))
                    eval.second?.let { secondBase64 ->
                        val secondOutput = passkeyUtils.computePrf(
                            privateKeyBytes = privateKeyBytes,
                            prfInput = PasskeyBase64.decode(secondBase64),
                        )
                        put("second", PasskeyBase64.encodeToString(secondOutput))
                    }
                }
            }
        val authenticationResponse = buildJsonObject {
            put("id", PasskeyBase64.encodeToString(credentialIdBytes))
            put("rawId", credentialIdBytes)
            put("type", "public-key")
            put("authenticatorAttachment", "cross-platform")
            put("response", r)
            put("clientExtensionResults", buildJsonObject {
                if (prfResults != null) {
                    put("prf", buildJsonObject {
                        put("results", prfResults)
                    })
                }
            })
        }
        val authenticationResponseJson = json.encodeToString(authenticationResponse)
        val passkeyCredential = PublicKeyCredential(authenticationResponseJson)
        return GetCredentialResponse(passkeyCredential)
    }

    private fun resolvePrfEvalInput(
        requestJson: String,
        credentialIdBytes: ByteArray,
    ): PrfEvalInput? {
        val options = runCatching {
            json.decodeFromString<GetCredentialRequestOptions>(requestJson)
        }.getOrNull()
        val prf = options?.extensions?.prf ?: return null
        val credentialIdBase64 = PasskeyBase64.encodeToString(credentialIdBytes)
        return prf.evalByCredential[credentialIdBase64] ?: prf.eval
    }

    private fun JsonObjectBuilder.put(key: String, data: ByteArray) {
        put(key, PasskeyBase64.encodeToString(data))
    }

    // https://www.w3.org/TR/webauthn-3/#prf-extension
    @Serializable
    private data class GetCredentialRequestOptions(
        val extensions: GetCredentialExtensions? = null,
    )

    @Serializable
    private data class GetCredentialExtensions(
        val prf: GetCredentialPrfExtension? = null,
    )

    @Serializable
    private data class GetCredentialPrfExtension(
        val eval: PrfEvalInput? = null,
        val evalByCredential: Map<String, PrfEvalInput> = emptyMap(),
    )

    @Serializable
    private data class PrfEvalInput(
        val first: String,
        val second: String? = null,
    )
}
