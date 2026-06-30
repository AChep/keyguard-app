package com.artemchep.keyguard.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
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
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskey
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.common.service.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.common.service.webauthn.WebAuthnEncodingException
import com.artemchep.keyguard.common.service.webauthn.WebAuthnInvalidStateException
import com.artemchep.keyguard.common.service.webauthn.coseKeyEs256
import com.artemchep.keyguard.common.service.webauthn.decodeExcludedCredentialIds as decodeWebAuthnExcludedCredentialIds
import com.artemchep.keyguard.common.service.webauthn.findExcludedPasskeyCredentialOrNull as findWebAuthnExcludedPasskeyCredentialOrNull
import com.artemchep.keyguard.common.service.webauthn.pubKeyCredParamsOrDefaults
import com.artemchep.keyguard.common.service.webauthn.requireNoExcludedPasskeyCredential as requireNoWebAuthnExcludedPasskeyCredential
import com.artemchep.keyguard.common.service.webauthn.webAuthnNoneAttestationObject
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
            )
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
        val data = request.data
        val gen = requirePasskeyGenerator(
            data = data,
            passkeyGenerators = passkeyGenerators,
        )
        requireNoExcludedPasskeyCredential(
            data = data,
            rpId = request.rpId,
            ciphers = ciphers,
        )

        // Generate a key pair, this pair will be used to sign this and all
        // future authentication requests.
        val keyPair = gen.keyPair()

        val publicKeyCborBytes = getPublicKeyFromEcKeyPair(keyPair)
        val publicKeyAlgorithm = -7
        val publicKeyBytes = keyPair.public.encoded

        val challenge = data.challenge
        val origin = request.origin
        val packageName = request.packageName
        val rpId = request.rpId
        val rpName = data.rp.name

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
            userVerification = passkeyUtils.userVerification(
                mode = data.authenticatorSelection.userVerification,
                userVerified = userVerified,
            ),
            userPresence = true,
        )
        val attestationObjectBytes = defaultAttestationObject(
            authData = authData,
        )

        val keyValue = base64Service.encodeToString(keyPair.private.encoded)
        val discoverable = data.authenticatorSelection.requireResidentKey ||
                data.authenticatorSelection.residentKey == "required" ||
                data.authenticatorSelection.residentKey == "preferred"
        val local = AddCredentialCipherRequestPasskeyData(
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
        // WebAuthn L3 §8.7 None Attestation: `fmt` is "none" and `attStmt` is an empty map.
        // Shared encoder (no androidx Cbor dependency) so Android, iOS, and macOS emit
        // identical attestation objects.
        // - https://www.w3.org/TR/webauthn-3/#sctn-none-attestation
        return webAuthnNoneAttestationObject(authData)
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

        // Shared COSE_Key encoder (RFC 9052 §7) so Android, iOS, and macOS emit identical
        // public-key bytes.
        return coseKeyEs256(byteX, byteY)
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

internal fun findPasskeyGeneratorOrNull(
    data: CreatePasskey,
    passkeyGenerators: List<PasskeyGenerator>,
): PasskeyGenerator? {
    val pubKeyCredParams = data.pubKeyCredParamsOrDefaults()
    return passkeyGenerators.firstOrNull { generator ->
        val matchingCredentials = pubKeyCredParams
            .firstOrNull(generator::handles)
        matchingCredentials != null
    }
}

internal fun requirePasskeyGenerator(
    data: CreatePasskey,
    passkeyGenerators: List<PasskeyGenerator>,
): PasskeyGenerator =
    findPasskeyGeneratorOrNull(
        data = data,
        passkeyGenerators = passkeyGenerators,
    ) ?: throw CreatePublicKeyCredentialDomException(
        // WebAuthn L3 create() throws NotSupportedError when no
        // pubKeyCredParams entry has type "public-key" or no listed
        // public-key algorithm is supported by the authenticator.
        // Spec:
        // - https://www.w3.org/TR/webauthn-3/#sctn-createCredential
        // - https://www.w3.org/TR/webauthn-3/#sctn-createCredential-exceptions
        domError = NotSupportedError(),
        errorMessage = "None of the allowed public key parameters are supported by the app.",
    )

internal fun requireNoExcludedPasskeyCredential(
    data: CreatePasskey,
    rpId: String,
    ciphers: List<DSecret>,
) = mapCreateWebAuthnExceptions {
    requireNoWebAuthnExcludedPasskeyCredential(
        data = data,
        rpId = rpId,
        ciphers = ciphers,
    )
}

internal fun findExcludedPasskeyCredentialOrNull(
    data: CreatePasskey,
    rpId: String,
    ciphers: List<DSecret>,
): DSecret.Login.Fido2Credentials? = mapCreateWebAuthnExceptions {
    findWebAuthnExcludedPasskeyCredentialOrNull(
        data = data,
        rpId = rpId,
        ciphers = ciphers,
    )
}

private fun decodeExcludedCredentialIds(
    data: CreatePasskey,
): Set<String> = mapCreateWebAuthnExceptions {
    decodeWebAuthnExcludedCredentialIds(data)
}

private inline fun <T> mapCreateWebAuthnExceptions(
    block: () -> T,
): T {
    try {
        return block()
    } catch (e: WebAuthnEncodingException) {
        throw CreatePublicKeyCredentialDomException(
            domError = EncodingError(),
            errorMessage = e.message.orEmpty(),
        )
    } catch (e: WebAuthnInvalidStateException) {
        throw CreatePublicKeyCredentialDomException(
            domError = InvalidStateError(),
            errorMessage = e.message.orEmpty(),
        )
    }
}
