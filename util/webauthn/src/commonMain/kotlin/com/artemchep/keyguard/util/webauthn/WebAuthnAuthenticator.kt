package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.util.webauthn.crypto.NativePasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyProfile
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyPublicKey
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private const val MAX_ENCODED_PASSKEY_KEY_CHARS = 5_464

/**
 * Registration and assertion operations, invoked after provider consent and RP validation.
 * [decodeStoredPrivateKey] returns caller-owned bytes or null for an invalid encoding;
 * the authenticator clears those bytes after signing, including on failure.
 */
class WebAuthnAuthenticator(
    private val json: Json,
    private val authenticatorDataFactory: WebAuthnAuthenticatorDataFactory,
    // Storage encodings differ from WebAuthn's base64url wire encoding.
    private val decodeStoredPrivateKey: (String) -> ByteArray? = PasskeyBase64::decodeStoredKeyOrNull,
    private val passkeyCrypto: PasskeyCrypto = NativePasskeyCrypto,
    private val generateCredentialId: () -> String = { Uuid.random().toString() },
    private val hashSha256: (ByteArray) -> ByteArray = NativeCrypto.primitives::sha256,
) {
    fun createCredential(
        data: CreatePasskey,
        context: WebAuthnCallerContext,
        userVerified: Boolean,
        transports: List<String>,
        credentials: List<WebAuthnCredential> = emptyList(),
    ): WebAuthnRegistrationResult {
        val result = createCredential(data.toRegistrationRequest(context.rpId), userVerified, credentials)
        val clientData = clientDataJsonBytes(json, "webauthn.create", data.challenge, context)
        val response = registrationResponseJson(
            clientData = clientData,
            authenticatorData = result.authenticatorData,
            attestationObject = result.attestationObject,
            publicKeyAlgorithm = result.publicKeyAlgorithm,
            publicKey = result.publicKey,
            transports = transports,
        )
        return WebAuthnRegistrationResult(
            responseJson = json.encodeToString(credentialResponseJson(result.credentialId, response)),
            credential = result.credential,
        )
    }

    fun createCredential(
        request: WebAuthnRegistrationRequest,
        userVerified: Boolean,
        credentials: List<WebAuthnCredential> = emptyList(),
    ): WebAuthnCredentialCreationResult {
        val algorithm = requirePasskeyAlgorithm(
            pubKeyCredParams = request.pubKeyCredParams,
            supportedAlgorithms = passkeyCrypto.supportedAlgorithms,
        )
        requireNoExcludedPasskeyCredential(
            excludedCredentialIds = request.excludedCredentialIds,
            rpId = request.rpId,
            credentials = credentials,
        )

        val key = generateRegistrationKey(algorithm)
        val credentialId = generateCredentialId()
        val credentialIdBytes = PasskeyCredentialId.encode(credentialId)
        val authData = authenticatorDataFactory.encodeAuthenticatorData(
            rpId = request.rpId,
            signCount = 0,
            credentialId = credentialIdBytes,
            credentialPublicKey = key.cosePublicKey,
            attestation = request.attestation,
            userVerified = webAuthnUserVerifiedFlag(
                requirement = request.userVerification,
                userVerified = userVerified,
            ),
            userPresent = true,
        )
        return WebAuthnCredentialCreationResult(
            credential = key.toCredential(request, credentialId),
            credentialId = credentialIdBytes,
            authenticatorData = authData,
            attestationObject = webAuthnNoneAttestationObject(authData),
            publicKeyAlgorithm = algorithm.coseValue,
            publicKey = key.spkiPublicKey,
        )
    }

    fun getAssertion(
        request: WebAuthnAssertionRequest,
        context: WebAuthnCallerContext,
        credential: WebAuthnCredential,
        userVerified: Boolean,
        clientDataHash: ByteArray? = null,
    ): String {
        requireCredentialRpIdMatchesRequest(
            credential = credential,
            rpId = context.rpId,
        )
        requireCredentialAllowed(
            credential = credential,
            allowCredentials = request.allowedCredentials,
        )

        val clientData = clientDataJsonBytes(
            json = json,
            type = "webauthn.get",
            challenge = PasskeyBase64.encodeToString(request.challenge),
            context = context,
        )
        val result = getAssertion(
            request = WebAuthnAssertionHashRequest(
                rpId = context.rpId,
                clientDataHash = clientDataHash ?: hashSha256(clientData),
                userVerification = request.userVerification,
                allowedCredentials = request.allowedCredentials,
            ),
            credential = credential,
            userVerified = userVerified,
        )
        return try {
            val response = assertionResponseJson(
                clientDataJson = PasskeyBase64.encodeToString(clientData),
                authenticatorData = PasskeyBase64.encodeToString(result.authenticatorData),
                signature = PasskeyBase64.encodeToString(result.signature),
                userHandle = result.userHandle,
            )
            json.encodeToString(credentialResponseJson(result.credentialId, response))
        } finally {
            result.signature.fill(0)
        }
    }

    fun getAssertion(
        request: WebAuthnAssertionHashRequest,
        credential: WebAuthnCredential,
        userVerified: Boolean,
    ): WebAuthnAssertionResult {
        requireCredentialRpIdMatchesRequest(credential, request.rpId)
        requireCredentialAllowed(credential, request.allowedCredentials)

        val credentialIdBytes = PasskeyCredentialId.encode(credential.credentialId)

        // Modern Bitwarden seems to use 0 for passkeys without a signature
        // counter. Non-zero counters are legacy; we preserve them but
        // do not increment them because keeping counters monotonic
        // across devices requires sync coordination.
        val counter = (credential.counter ?: 0).coerceAtLeast(0)
        val authData = authenticatorDataFactory.encodeAuthenticatorData(
            rpId = request.rpId,
            signCount = counter,
            credentialId = credentialIdBytes,
            credentialPublicKey = null,
            userVerified = webAuthnUserVerifiedFlag(
                requirement = request.userVerification,
                userVerified = userVerified,
            ),
            userPresent = true,
        )

        return WebAuthnAssertionResult(
            credentialId = credentialIdBytes,
            authenticatorData = authData,
            signature = signAssertion(credential, authData, request.clientDataHash),
            userHandle = credential.userHandle,
        )
    }

    private fun generateRegistrationKey(algorithm: PasskeySignatureAlgorithm): RegistrationKey {
        val material = passkeyCrypto.generate(algorithm)
        try {
            return when (val publicKey = material.publicKey) {
                is PasskeyPublicKey.EcP256 -> RegistrationKey(
                    profile = material.profile,
                    encodedPrivateKey = PasskeyBase64.encodeToString(material.privateKeyPkcs8),
                    cosePublicKey = coseKeyEs256(publicKey.x, publicKey.y),
                    spkiPublicKey = publicKey.spki.copyOf(),
                )
            }
        } finally {
            material.clear()
        }
    }

    private fun signAssertion(
        credential: WebAuthnCredential,
        authenticatorData: ByteArray,
        clientDataHash: ByteArray,
    ): ByteArray {
        requireSignableStoredKey(credential)
        val privateKeyPkcs8 = decodeStoredPrivateKey(credential.keyValue)
            ?: throw storedPasskeyKeyEncodingError()
        val dataToSign = authenticatorData + clientDataHash
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
        return when (result) {
            is PasskeySignResult.Success -> result.signatureDer
            is PasskeySignResult.Error -> throw storedPasskeyKeyEncodingError()
        }
    }

    /**
     * Rejects a stored credential this provider cannot produce an assertion
     * for: only an ES256 key — `public-key` / `ECDSA` / `P-256` — is signable
     * here, and the encoded key is length-capped before it reaches the Base64
     * decoder so a malformed vault entry cannot turn into an unbounded decode.
     */
    private fun requireSignableStoredKey(
        credential: WebAuthnCredential,
    ) {
        val isEs256 = credential.keyType == "public-key" &&
            credential.keyAlgorithm == "ECDSA" &&
            credential.keyCurve == "P-256"
        if (!isEs256 || credential.keyValue.length > MAX_ENCODED_PASSKEY_KEY_CHARS) {
            throw storedPasskeyKeyEncodingError()
        }
    }
}

private fun storedPasskeyKeyEncodingError() = WebAuthnEncodingException(
    message = "The stored passkey key is malformed or unsupported.",
)

private class RegistrationKey(
    private val profile: PasskeyKeyProfile,
    private val encodedPrivateKey: String,
    val cosePublicKey: ByteArray,
    val spkiPublicKey: ByteArray,
) {
    fun toCredential(request: WebAuthnRegistrationRequest, credentialId: String): WebAuthnCredential {
        return WebAuthnCredential(
            credentialId = credentialId,
            keyType = "public-key",
            keyAlgorithm = profile.keyAlgorithm,
            keyCurve = profile.keyCurve,
            keyValue = encodedPrivateKey,
            rpId = request.rpId,
            rpName = request.rpName,
            counter = 0,
            userHandle = request.userHandle,
            userName = request.userName,
            userDisplayName = request.userDisplayName,
            discoverable = request.discoverable,
        )
    }
}
