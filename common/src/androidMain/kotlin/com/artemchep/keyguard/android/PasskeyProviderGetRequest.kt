package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
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
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.PasskeyCrypto
import com.artemchep.keyguard.common.service.crypto.PasskeySignResult
import com.artemchep.keyguard.common.service.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.decodeOrNull
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.common.service.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.common.service.webauthn.WebAuthnEncodingException
import com.artemchep.keyguard.common.service.webauthn.WebAuthnNotAllowedException
import com.artemchep.keyguard.common.service.webauthn.requireCredentialAllowedByRequestOptions as requireWebAuthnCredentialAllowedByRequestOptions
import com.artemchep.keyguard.common.service.webauthn.requireCredentialRpIdMatchesRequest as requireWebAuthnCredentialRpIdMatchesRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val MAX_ENCODED_PASSKEY_KEY_CHARS = 5_464

class PasskeyProviderGetRequest(
    private val context: Context,
    private val json: Json,
    private val base64Service: Base64Service,
    private val cryptoService: CryptoGenerator,
    private val passkeyCrypto: PasskeyCrypto,
    private val passkeyUtils: PasskeyUtils,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
        json = directDI.instance(),
        base64Service = directDI.instance(),
        cryptoService = directDI.instance(),
        passkeyCrypto = directDI.instance(),
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
        requireCredentialRpIdMatchesRequest(
            credential = credential,
            rpId = rpId,
        )
        requireCredentialAllowedByRequestOptions(
            credential = credential,
            requestJson = opt.requestJson,
            json = json,
        )

        val credentialIdBytes = PasskeyCredentialId.encode(credential.credentialId)

        val counter = kotlin.run {
            val tmp = credential.counter ?: 0
            if (tmp > 0) {
                // Modern Bitwarden seems to use 0 for passkeys without a signature
                // counter. Non-zero counters are legacy; we preserve them but
                // do not increment them because keeping counters monotonic
                // across devices requires sync coordination.
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
            requireSignableStoredKey(credential)
            val dataToSign = defaultAuthenticatorData + clientDataJsonHash
            val privateKeyPkcs8 = base64Service.decodeOrNull(credential.keyValue)
                ?: throw storedPasskeyKeyEncodingError()
            val result = try {
                passkeyCrypto.sign(
                    algorithm = PasskeySignatureAlgorithm.ES256,
                    privateKeyPkcs8 = privateKeyPkcs8,
                    data = dataToSign,
                )
            } finally {
                privateKeyPkcs8.fill(0)
                dataToSign.fill(0)
            }
            when (result) {
                is PasskeySignResult.Success -> result.signatureDer
                is PasskeySignResult.Error -> throw storedPasskeyKeyEncodingError()
            }
        }
        val encodedSignature = try {
            PasskeyBase64.encodeToString(signature)
        } finally {
            signature.fill(0)
        }

        val r = assertionResponseJson(
            clientDataJson = PasskeyBase64.encodeToString(clientDataJsonBytes),
            authenticatorData = PasskeyBase64.encodeToString(defaultAuthenticatorData),
            signature = encodedSignature,
            userHandle = credential.userHandle,
        )
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

    /**
     * Rejects a stored credential this provider cannot produce an assertion
     * for: only an ES256 key — `public-key` / `ECDSA` / `P-256` — is signable
     * here, and the encoded key is length-capped before it reaches the Base64
     * decoder so a malformed vault entry cannot turn into an unbounded decode.
     */
    private fun requireSignableStoredKey(
        credential: DSecret.Login.Fido2Credentials,
    ) {
        val isEs256 = credential.keyType == "public-key" &&
            credential.keyAlgorithm == "ECDSA" &&
            credential.keyCurve == "P-256"
        if (!isEs256 || credential.keyValue.length > MAX_ENCODED_PASSKEY_KEY_CHARS) {
            throw storedPasskeyKeyEncodingError()
        }
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

    private fun JsonObjectBuilder.put(key: String, data: ByteArray) {
        put(key, PasskeyBase64.encodeToString(data))
    }
}

private fun storedPasskeyKeyEncodingError() = GetPublicKeyCredentialDomException(
    domError = EncodingError(),
    errorMessage = "The stored passkey key is malformed or unsupported.",
)

internal fun assertionResponseJson(
    clientDataJson: String,
    authenticatorData: String,
    signature: String,
    userHandle: String?,
): JsonObject = buildJsonObject {
    put("clientDataJSON", clientDataJson)
    put("authenticatorData", authenticatorData)
    put("signature", signature)
    userHandle
        ?.takeIf { it.isNotEmpty() }
        ?.let { put("userHandle", it) }
}

internal fun requireCredentialRpIdMatchesRequest(
    credential: DSecret.Login.Fido2Credentials,
    rpId: String,
) = requireWebAuthnCredentialRpIdMatchesRequest(
    credential = credential,
    rpId = rpId,
)

internal fun requireCredentialAllowedByRequestOptions(
    credential: DSecret.Login.Fido2Credentials,
    requestJson: String,
    json: Json,
    decodeCredentialId: (String) -> ByteArray = PasskeyBase64::decode,
) = mapGetWebAuthnExceptions {
    requireWebAuthnCredentialAllowedByRequestOptions(
        credential = credential,
        requestJson = requestJson,
        json = json,
        decodeCredentialId = decodeCredentialId,
    )
}

private inline fun <T> mapGetWebAuthnExceptions(
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
