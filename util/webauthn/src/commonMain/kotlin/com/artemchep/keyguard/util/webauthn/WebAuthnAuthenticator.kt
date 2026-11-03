package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeHashAlgorithm
import com.artemchep.keyguard.util.webauthn.crypto.NativePasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyProfile
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyPublicKey
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private val hmacSha256: (key: ByteArray, data: ByteArray) -> ByteArray = { key, data ->
        NativeCrypto.primitives.hmac(key, data, NativeHashAlgorithm.SHA_256)
    },
    private val randomBytes: (Int) -> ByteArray = NativeCrypto.primitives::randomBytes,
) {
    fun createCredential(
        data: CreatePasskey,
        context: WebAuthnCallerContext,
        userVerified: Boolean,
        transports: List<String>,
        credentials: List<WebAuthnCredential> = emptyList(),
    ): WebAuthnRegistrationResult {
        val result = createCredential(data.toRegistrationRequest(context.rpId), userVerified, credentials)
        return try {
            val clientData = clientDataJsonBytes(json, "webauthn.create", data.challenge, context)
            val response = registrationResponseJson(
                clientData = clientData,
                authenticatorData = result.authenticatorData,
                attestationObject = result.attestationObject,
                publicKeyAlgorithm = result.publicKeyAlgorithm,
                publicKey = result.publicKey,
                transports = transports,
            )
            val clientExtensionResults = if (result.prfEnabled) {
                prfClientExtensionResults(enabled = true, results = result.prfResults)
            } else {
                JsonObject(emptyMap())
            }
            WebAuthnRegistrationResult(
                responseJson = json.encodeToString(
                    credentialResponseJson(result.credentialId, response, clientExtensionResults),
                ),
                credential = result.credential,
            )
        } finally {
            result.prfResults?.clear()
        }
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
        val userVerifiedFlag = webAuthnUserVerifiedFlag(
            requirement = request.userVerification,
            userVerified = userVerified,
        )
        val authData = authenticatorDataFactory.encodeAuthenticatorData(
            rpId = request.rpId,
            signCount = 0,
            credentialId = credentialIdBytes,
            credentialPublicKey = key.cosePublicKey,
            attestation = request.attestation,
            userVerified = userVerifiedFlag,
            userPresent = true,
        )
        // Keep the PRF secret stable across devices even if a site
        // first requests the extension after registration.
        val prfSecret = randomBytes(WEBAUTHN_PRF_SECRET_BYTES)
        return try {
            WebAuthnCredentialCreationResult(
                credential = key.toCredential(
                    request = request,
                    credentialId = credentialId,
                    prfSecret = PasskeyBase64.encodeToString(prfSecret),
                ),
                credentialId = credentialIdBytes,
                authenticatorData = authData,
                attestationObject = webAuthnNoneAttestationObject(authData),
                publicKeyAlgorithm = algorithm.coseValue,
                publicKey = key.spkiPublicKey,
                prfEnabled = request.prf != null,
                prfResults = request.prf?.let { prf ->
                    evaluatePrf(prfSecret, prf.eval, prf.salted)
                },
            )
        } finally {
            prfSecret.fill(0)
        }
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
        val prf = request.prf?.let { inputs ->
            PasskeyCredentialId.encodeOrNull(credential.credentialId)
                ?.let(inputs::valuesFor)
        }
        val result = getAssertion(
            request = WebAuthnAssertionHashRequest(
                rpId = context.rpId,
                clientDataHash = clientDataHash ?: hashSha256(clientData),
                userVerification = request.userVerification,
                allowedCredentials = request.allowedCredentials,
                prf = prf,
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
            val clientExtensionResults = if (request.prf != null) {
                prfClientExtensionResults(enabled = null, results = result.prfResults)
            } else {
                JsonObject(emptyMap())
            }
            json.encodeToString(credentialResponseJson(result.credentialId, response, clientExtensionResults))
        } finally {
            result.signature.fill(0)
            result.prfResults?.clear()
        }
    }

    fun getAssertion(
        request: WebAuthnAssertionHashRequest,
        credential: WebAuthnCredential,
        userVerified: Boolean,
    ): WebAuthnAssertionResult {
        requireCredentialRpIdMatchesRequest(credential, request.rpId)
        requireCredentialAllowed(credential, request.allowedCredentials)

        val credentialIdBytes = PasskeyCredentialId.encodeOrNull(credential.credentialId)
            ?: throw WebAuthnEncodingException("The stored passkey credential ID is malformed.")

        // Modern Bitwarden seems to use 0 for passkeys without a signature
        // counter. Non-zero counters are legacy; we preserve them but
        // do not increment them because keeping counters monotonic
        // across devices requires sync coordination.
        val counter = (credential.counter ?: 0).coerceAtLeast(0)
        val userVerifiedFlag = webAuthnUserVerifiedFlag(
            requirement = request.userVerification,
            userVerified = userVerified,
        )
        val authData = authenticatorDataFactory.encodeAuthenticatorData(
            rpId = request.rpId,
            signCount = counter,
            credentialId = credentialIdBytes,
            credentialPublicKey = null,
            userVerified = userVerifiedFlag,
            userPresent = true,
        )

        // Sign first: unlike the PRF outputs, the signature is not a secret.
        val signature = signAssertion(credential, authData, request.clientDataHash)
        // The caller wipes the signature it receives, so wipe it here if it never does.
        var succeeded = false
        try {
            val prfResults = credential.prfSecret
                ?.takeIf { request.prf != null }
                ?.let(PasskeyBase64::decodePrfSecretOrNull)
                ?.let { secret ->
                    try {
                        evaluatePrf(secret, request.prf, request.prfSalted)
                    } finally {
                        secret.fill(0)
                    }
                }
            return WebAuthnAssertionResult(
                credentialId = credentialIdBytes,
                authenticatorData = authData,
                signature = signature,
                // Readable whenever there is no allow list: requireCredentialAllowed checked it.
                userHandle = credential.userHandleBytes(),
                prfResults = prfResults,
            ).also { succeeded = true }
        } finally {
            if (!succeeded) signature.fill(0)
        }
    }

    /**
     * A credential has a single PRF, which "MUST be independent of the state
     * of user verification" (WebAuthn L3 §10.1.4): the inputs are evaluated
     * even when the authenticator data does not report a verified user.
     */
    private fun evaluatePrf(
        secret: ByteArray,
        values: WebAuthnPrfValues?,
        salted: Boolean,
    ): WebAuthnPrfValues? = values?.evaluate(secret, salted, hmacSha256, hashSha256)

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

    private fun requireSignableStoredKey(
        credential: WebAuthnCredential,
    ) {
        if (!credential.isSignableStoredKey()) {
            throw storedPasskeyKeyEncodingError()
        }
    }
}

/**
 * Whether this provider can produce an assertion for a stored credential:
 * only an ES256 key — `public-key` / `ECDSA` / `P-256` — is signable here,
 * and the encoded key is length-capped before it reaches the Base64 decoder
 * so a malformed vault entry cannot turn into an unbounded decode. A blank
 * key never decodes.
 */
fun WebAuthnCredential.isSignableStoredKey(): Boolean =
    keyType == "public-key" &&
        keyAlgorithm == "ECDSA" &&
        keyCurve == "P-256" &&
        keyValue.isNotBlank() &&
        keyValue.length <= MAX_ENCODED_PASSKEY_KEY_CHARS

private fun storedPasskeyKeyEncodingError() = WebAuthnEncodingException(
    message = "The stored passkey key is malformed or unsupported.",
)

private class RegistrationKey(
    private val profile: PasskeyKeyProfile,
    private val encodedPrivateKey: String,
    val cosePublicKey: ByteArray,
    val spkiPublicKey: ByteArray,
) {
    fun toCredential(
        request: WebAuthnRegistrationRequest,
        credentialId: String,
        prfSecret: String?,
    ): WebAuthnCredential {
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
            prfSecret = prfSecret,
        )
    }
}
