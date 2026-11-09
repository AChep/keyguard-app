@file:Suppress("MagicNumber") // FIDO/CBOR wire constants and bounded frame sizes.

package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import com.artemchep.keyguard.util.webauthn.WebAuthnPublicKeyCredentialDescriptor
import com.artemchep.keyguard.util.webauthn.isValidCanonicalWebAuthnRpId

/** Built-in verification, interactive selection, ES256 and the CTAP PRF extensions. */
object HybridCtap {
    const val OK = 0
    const val INVALID_COMMAND = 1
    const val INVALID_PARAMETER = 2
    const val INVALID_CBOR = 0x12
    const val MISSING_PARAMETER = 0x14
    const val CREDENTIAL_EXCLUDED = 0x19
    const val UNSUPPORTED_ALGORITHM = 0x26
    const val UNSUPPORTED_OPTION = 0x2b
    const val INVALID_OPTION = 0x2c
    const val DENIED = 0x27
    const val NO_CREDENTIALS = 0x2e
    const val PIN_AUTH_INVALID = 0x33
    const val INVALID_SUBCOMMAND = 0x3e

    internal fun status(code: Int): ByteArray = byteArrayOf(code.toByte())

    internal fun success(payload: ByteArray): ByteArray = status(OK) + payload

    fun getInfo(aaguid: ByteArray): ByteArray =
        HybridCbor.encode(
            mapOf(
                // FIDO_2_1 would claim mandatory pinUvAuthToken, credProtect and credential management.
                1 to listOf("FIDO_2_0"),
                2 to listOf("hmac-secret", "hmac-secret-mc"),
                3 to aaguid,
                4 to mapOf("rk" to true, "up" to true, "uv" to true),
                5 to 4096,
                6 to listOf(1, 2),
                9 to listOf("hybrid"),
                10 to listOf(mapOf("alg" to -7, "type" to "public-key")),
            ),
        )

    fun postHandshake(aaguid: ByteArray): ByteArray = HybridCbor.encode(mapOf(1 to getInfo(aaguid)))

    internal fun parameters(command: ByteArray): Map<*, *> {
        require(command.size in 2..4096)
        return HybridCbor.decode(command.copyOfRange(1, command.size)) as? Map<*, *>
            ?: error("Invalid CTAP parameters")
    }

    /** CTAP §6.2.2 pre-flight: `up=false` only asks whether an allowed credential exists. */
    internal fun isSilent(map: Map<*, *>): Boolean = (map[5L] as? Map<*, *>)?.get("up") == false

    internal fun assertionOptions(
        map: Map<*, *>,
        prf: HybridPrfContext?,
    ): WebAuthnAssertionHashRequest {
        val rpId = map[1L] as? String ?: error("Missing RP ID")
        require(isValidCanonicalWebAuthnRpId(rpId))
        val hash = map[2L] as? ByteArray ?: error("Missing client data hash")
        require(hash.size == 32)
        validateAssertionOptions(map)
        // The message size and CBOR item bounds limit the list; hybrid clients never split it.
        val descriptors = credentialDescriptors(map[3L])
        return WebAuthnAssertionHashRequest(
            rpId = rpId,
            clientDataHash = hash,
            userVerification = if (isSilent(map)) "discouraged" else "required",
            allowedCredentials = WebAuthnAllowedCredentialDescriptors(descriptors.isNotEmpty(), descriptors),
            prf = prf?.values,
            prfSalted = true,
        )
    }

    private fun validateAssertionOptions(map: Map<*, *>) {
        val extensions = map[4L]?.let { it as? Map<*, *> ?: error("Invalid extensions") }
        require(extensions?.get("hmac-secret") == null || extensions["hmac-secret"] is Map<*, *>)
        if (map[6L] != null) throw HybridCtapException(PIN_AUTH_INVALID)
        require(map[7L] == null || map[7L] in listOf(1L, 2L))
        val options = map[5L]?.let { it as? Map<*, *> ?: error("Invalid options") }
        require(options?.values?.all { it is Boolean } != false)
        // CTAP 2.2 §6.2.2: platforms must not send rk.
        if (options?.containsKey("rk") == true) throw HybridCtapException(UNSUPPORTED_OPTION)
    }

    /**
     * CTAP §6.2.2 requires the user ID of a discoverable credential with or without an allow list.
     * Only a credential with a readable user handle can answer a request without one.
     * [userSelected] reports that the user chose the account on the phone; never with an allow list.
     */
    fun assertionResponse(
        result: WebAuthnAssertionResult,
        userSelected: Boolean = false,
    ): ByteArray {
        val response =
            linkedMapOf<Int, Any>(
                1 to mapOf("type" to "public-key", "id" to result.credentialId),
                2 to result.authenticatorData,
                3 to result.signature,
            )
        result.userHandle?.let { id ->
            // Phones return the account names after user verification. macOS failed to
            // decode responses whose user entity had only an id.
            response[4] =
                buildMap {
                    put("id", id)
                    result.userName?.let { put("name", it) }
                    result.userDisplayName?.let { put("displayName", it) }
                }
        }
        if (userSelected) response[6] = true
        return success(HybridCbor.encode(response))
    }

    fun registrationResponse(result: WebAuthnCredentialCreationResult): ByteArray =
        success(HybridCbor.encode(mapOf(1 to "none", 2 to result.authenticatorData, 3 to emptyMap<Any, Any>())))
}

/** A CBOR list of `{type, id}` credential descriptors, as in allow and exclude lists. */
internal fun credentialDescriptors(input: Any?): List<WebAuthnPublicKeyCredentialDescriptor> {
    val list = input?.let { it as? List<*> ?: error("Invalid descriptor list") }.orEmpty()
    return list.map { item ->
        val descriptor = item as? Map<*, *> ?: error("Invalid descriptor")
        val type = descriptor["type"] as? String ?: error("Missing descriptor type")
        val id = descriptor["id"] as? ByteArray ?: error("Missing credential ID")
        require(id.size in 1..1023)
        WebAuthnPublicKeyCredentialDescriptor(type, PasskeyCredentialId.decode(id))
    }
}

/** Mirrors [registrationRequest]: once built, the request owns the opened PRF context. */
internal fun assertionRequest(
    parameters: Map<*, *>,
    prfSession: HybridPrfSession,
    userPresent: Boolean,
): HybridAssertionRequest {
    val extensions = parameters[4L]?.let { it as? Map<*, *> ?: error("Invalid extensions") }
    val hmacSecret = extensions?.get("hmac-secret")
    // A pre-flight only identifies an allowed credential; Chromium sends it without PRF inputs.
    if (!userPresent && hmacSecret != null) throw HybridCtapException(HybridCtap.UNSUPPORTED_OPTION)
    val prf = prfSession.open(hmacSecret)
    var transferred = false
    return try {
        val options = HybridCtap.assertionOptions(parameters, prf)
        if (!userPresent && !options.allowedCredentials.isAllowCredentialsSupplied) {
            throw HybridCtapException(HybridCtap.NO_CREDENTIALS)
        }
        HybridAssertionRequest(options, prf, userPresent).also { transferred = true }
    } finally {
        if (!transferred) prf?.clear()
    }
}
