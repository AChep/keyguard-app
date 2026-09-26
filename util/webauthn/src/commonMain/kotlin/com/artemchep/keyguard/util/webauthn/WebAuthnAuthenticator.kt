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
    private val decodeStoredPrivateKey: (String) -> ByteArray?,
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
        val algorithm = requirePasskeyAlgorithm(
            data = data,
            supportedAlgorithms = passkeyCrypto.supportedAlgorithms,
        )
        requireNoExcludedPasskeyCredential(
            data = data,
            rpId = context.rpId,
            credentials = credentials,
        )

        val key = generateRegistrationKey(algorithm)
        val credentialId = generateCredentialId()
        val credentialIdBytes = PasskeyCredentialId.encode(credentialId)
        val clientData = clientDataJsonBytes(json, "webauthn.create", data.challenge, context)
        val authData = authenticatorDataFactory.encodeAuthenticatorData(
            rpId = context.rpId,
            signCount = 0,
            credentialId = credentialIdBytes,
            credentialPublicKey = key.cosePublicKey,
            attestation = data.attestation,
            userVerified = webAuthnUserVerifiedFlag(
                requirement = data.authenticatorSelection.userVerification,
                userVerified = userVerified,
            ),
            userPresent = true,
        )
        val response = registrationResponseJson(
            clientData = clientData,
            authenticatorData = authData,
            publicKeyAlgorithm = algorithm.coseValue,
            publicKey = key.spkiPublicKey,
            transports = transports,
        )
        return WebAuthnRegistrationResult(
            responseJson = json.encodeToString(credentialResponseJson(credentialIdBytes, response)),
            credential = key.toCredential(data, credentialId, context.rpId),
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

        val credentialIdBytes = PasskeyCredentialId.encode(credential.credentialId)

        // Modern Bitwarden seems to use 0 for passkeys without a signature
        // counter. Non-zero counters are legacy; we preserve them but
        // do not increment them because keeping counters monotonic
        // across devices requires sync coordination.
        val counter = (credential.counter ?: 0).coerceAtLeast(0)
        val authData = authenticatorDataFactory.encodeAuthenticatorData(
            rpId = context.rpId,
            signCount = counter,
            credentialId = credentialIdBytes,
            credentialPublicKey = null,
            userVerified = webAuthnUserVerifiedFlag(
                requirement = request.userVerification,
                userVerified = userVerified,
            ),
            userPresent = true,
        )

        val clientData = clientDataJsonBytes(
            json = json,
            type = "webauthn.get",
            challenge = PasskeyBase64.encodeToString(request.challenge),
            context = context,
        )
        val clientDataJsonHash = clientDataHash
            ?: hashSha256(clientData)
        val signature = signAssertion(credential, authData, clientDataJsonHash)
        val response = assertionResponseJson(
            clientDataJson = PasskeyBase64.encodeToString(clientData),
            authenticatorData = PasskeyBase64.encodeToString(authData),
            signature = signature,
            userHandle = credential.userHandle,
        )
        return json.encodeToString(credentialResponseJson(credentialIdBytes, response))
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
    ): String {
        requireSignableStoredKey(credential)
        val dataToSign = authenticatorData + clientDataHash
        val privateKeyPkcs8 = decodeStoredPrivateKey(credential.keyValue)
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
        val signature = when (result) {
            is PasskeySignResult.Success -> result.signatureDer
            is PasskeySignResult.Error -> throw storedPasskeyKeyEncodingError()
        }
        return try {
            PasskeyBase64.encodeToString(signature)
        } finally {
            signature.fill(0)
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
    fun toCredential(data: CreatePasskey, credentialId: String, rpId: String): WebAuthnCredential {
        val selection = data.authenticatorSelection
        val discoverable = selection.requireResidentKey ||
            selection.residentKey == "required" ||
            selection.residentKey == "preferred"
        return WebAuthnCredential(
            credentialId = credentialId,
            keyType = "public-key",
            keyAlgorithm = profile.keyAlgorithm,
            keyCurve = profile.keyCurve,
            keyValue = encodedPrivateKey,
            rpId = rpId,
            rpName = data.rp.name,
            counter = 0,
            userHandle = data.user.id,
            userName = data.user.name,
            userDisplayName = data.user.displayName,
            discoverable = discoverable,
        )
    }
}
