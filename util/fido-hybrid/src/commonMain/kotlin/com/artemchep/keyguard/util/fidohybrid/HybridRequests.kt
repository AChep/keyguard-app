package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import com.artemchep.keyguard.util.webauthn.WebAuthnPrfValues
import com.artemchep.keyguard.util.webauthn.WebAuthnRegistrationRequest

/** A request without [userPresent] is a pre-flight: answer without UI or report no credentials. */
class HybridAssertionRequest(
    val options: WebAuthnAssertionHashRequest,
    private val prf: HybridPrfContext? = null,
    val userPresent: Boolean = true,
) {
    fun extensions(results: WebAuthnPrfValues?): ByteArray? =
        if (results != null && prf != null) HybridCbor.encode(mapOf("hmac-secret" to prf.encrypt(results))) else null

    fun clear() {
        options.clientDataHash.fill(0)
        prf?.clear()
    }
}

/** "none" attestation signs nothing, so the client data hash is validated but not kept. */
class HybridRegistrationRequest(
    val options: WebAuthnRegistrationRequest,
    private val prf: HybridPrfContext? = null,
) {
    fun extensions(results: WebAuthnPrfValues?): ByteArray? {
        if (options.prf == null) return null
        val output = linkedMapOf<String, Any>("hmac-secret" to true)
        if (prf != null && results != null) output["hmac-secret-mc"] = prf.encrypt(results)
        return HybridCbor.encode(output)
    }

    fun clear() {
        prf?.clear()
    }
}

/** A successful value transfers ownership of its signature and PRF buffers to the caller. */
sealed interface HybridResult<out T> {
    data class Success<T>(
        val value: T,
    ) : HybridResult<T>

    data object Denied : HybridResult<Nothing>

    data object NoCredentials : HybridResult<Nothing>

    data object CredentialExcluded : HybridResult<Nothing>
}

interface HybridAuthenticator {
    suspend fun getAssertion(request: HybridAssertionRequest): HybridResult<WebAuthnAssertionResult>

    /** Return success only after the credential has been saved. */
    suspend fun makeCredential(request: HybridRegistrationRequest): HybridResult<WebAuthnCredentialCreationResult>
}

internal class HybridCtapException(
    val status: Int,
) : IllegalArgumentException("Invalid CTAP request")
