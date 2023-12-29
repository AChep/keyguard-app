package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.webauthn.Cbor
import com.artemchep.keyguard.common.model.AddPasskeyCipherRequest
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.provider.bitwarden.CreatePasskey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.kodein.di.DirectDI
import org.kodein.di.allInstances
import org.kodein.di.instance
import java.math.BigInteger
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint

@SuppressLint("RestrictedApi")
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PasskeyCreateRequest(
    private val context: Context,
    private val json: Json,
    private val base64Service: Base64Service,
    private val passkeyUtils: PasskeyUtils,
    private val passkeyGenerators: List<PasskeyGenerator>,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
        json = directDI.instance(),
        base64Service = directDI.instance(),
        passkeyUtils = directDI.instance(),
        passkeyGenerators = directDI.allInstances(),
    )

    suspend fun processGetCredentialsRequest(
        request: ProviderCreateCredentialRequest,
        userVerified: Boolean,
    ): Pair<CreatePublicKeyCredentialResponse, AddPasskeyCipherRequest.Data> {
        val request2 = request.callingRequest as CreatePublicKeyCredentialRequest

        val data = json.decodeFromString<CreatePasskey>(request2.requestJson)
        val gen = passkeyGenerators.firstOrNull { generator ->
            val matchingCredentials = data.pubKeyCredParams
                .firstOrNull(generator::handles)
            matchingCredentials != null
        }
        requireNotNull(gen) {
            "None of the allowed public key parameters are supported by the app."
        }

        // Generate a key pair, this pair will be used to sign this and all
        // future authentication requests.
        val keyPair = gen.keyPair()

        val publicKeyCborBytes = getPublicKeyFromEcKeyPair(keyPair)
        val publicKeyAlgorithm = -7
        val publicKeyBytes = keyPair.public.encoded

        val challenge = data.challenge
        val rpId = data.rp.id!!
        val rpName = data.rp.name
        val origin = passkeyUtils.callingAppOrigin(request.callingAppInfo)
        val packageName = request.callingAppInfo.packageName
        passkeyUtils.requireRpMatchesOrigin(
            rpId = rpId,
            origin = origin,
            packageName = packageName,
        )

        val credentialId = passkeyUtils
            .generateCredentialId()
        val credentialIdBytes = PasskeyCredentialId.encode(credentialId)

        val clientData = buildJsonObject {
            put("type", "webauthn.create")
            put("challenge", challenge)
            put("origin", origin)
            put("androidPackageName", packageName)
        }
        val clientDataJson = json.encodeToString(clientData)
        val clientDataBytes = clientDataJson.toByteArray()

        val authData = passkeyUtils.authData(
            rpId = rpId,
            counter = 0,
            credentialId = credentialIdBytes,
            credentialPublicKey = publicKeyCborBytes,
            attestation = data.attestation,
            userVerification = userVerified,
            userPresence = true,
        )
        val attestationObjectBytes = defaultAttestationObject(
            authData = authData,
        )

        val keyValue = base64Service.encodeToString(keyPair.private.encoded)
        val discoverable = data.authenticatorSelection.requireResidentKey ||
                data.authenticatorSelection.residentKey == "required" ||
                data.authenticatorSelection.residentKey == "preferred"
        val local = AddPasskeyCipherRequest.Data(
            credentialId = credentialId,
            keyType = "public-key",
            keyAlgorithm = "ECDSA",
            keyCurve = "P-256",
            keyValue = keyValue,
            rpId = rpId,
            rpName = rpName,
            counter = 0,
            userHandle = data.user.id,
            userName = data.user.name,
            userDisplayName = data.user.displayName,
            discoverable = discoverable,
        )

        val registrationResponse = buildJsonObject {
            put("id", credentialIdBytes)
            put("rawId", credentialIdBytes)
            put("type", "public-key")
            put("authenticatorAttachment", "cross-platform")
            put(
                "response",
                buildJsonObject {
                    put("clientDataJSON", clientDataBytes)
                    put("attestationObject", attestationObjectBytes)
                    put(
                        "transports",
                        if (rpId == "google.com") {
                            buildJsonArray {
                                add("internal")
                                add("usb")
                            }
                        } else {
                            buildJsonArray {
                                add("internal")
                            }
                        },
                    )
                    put("publicKeyAlgorithm", publicKeyAlgorithm)
                    put("publicKey", publicKeyBytes)
                    put("authenticatorData", authData)
                },
            )
            put("clientExtensionResults", buildJsonObject { })
        }
        val registrationResponseJson = json.encodeToString(registrationResponse)
        return CreatePublicKeyCredentialResponse(registrationResponseJson) to local
    }

    @SuppressLint("RestrictedApi")
    private fun defaultAttestationObject(
        authData: ByteArray,
    ): ByteArray {
        val ao = mutableMapOf<String, Any>()
        ao["fmt"] = "none"
        ao["attStmt"] = emptyMap<Any, Any>()
        ao["authData"] = authData
        return Cbor().encode(ao)
    }

    private fun getPublicKeyFromEcKeyPair(keyPair: KeyPair): ByteArray {
        val ecPubKey = keyPair.public as ECPublicKey
        val ecPoint: ECPoint = ecPubKey.w

        // for now, only covers ES256
        if (ecPoint.affineX.bitLength() > 256 || ecPoint.affineY.bitLength() > 256) return ByteArray(
            0,
        )

        val byteX = bigIntToByteArray32(ecPoint.affineX)
        val byteY = bigIntToByteArray32(ecPoint.affineY)

        // refer to RFC9052 Section 7 for details
        return "A5010203262001215820".chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
                byteX +
                "225820".chunked(2).map { it.toInt(16).toByte() }.toByteArray() +
                byteY
    }

    private fun bigIntToByteArray32(bigInteger: BigInteger): ByteArray {
        var ba = bigInteger.toByteArray()

        if (ba.size < 32) {
            // append zeros in front
            ba = ByteArray(32) + ba
        }
        // get the last 32 bytes as bigint conversion sometimes put extra zeros at front
        return ba.copyOfRange(ba.size - 32, ba.size)
    }

    private fun JsonObjectBuilder.put(key: String, data: ByteArray) {
        put(key, PasskeyBase64.encodeToString(data))
    }
}
