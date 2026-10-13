package com.artemchep.keyguard.feature.auth.bitwarden.sso

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.url
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl
import com.artemchep.keyguard.res.*
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith

/** An attempt is deliberately not serializable and never includes secrets in toString(). */
class SsoRequest(
    val env: ServerEnv,
    val email: String,
    val state: String,
    val verifier: String,
    val challenge: String,
    val organizationIdentifier: String? = null,
) {
    fun browserUrl(redirectUri: String): String {
        val query = Parameters.build {
            append("clientId", CLIENT_ID)
            append("redirectUri", redirectUri)
            append("state", state)
            append("codeChallenge", challenge)
            append("email", email)
            organizationIdentifier?.let { append("identifier", it) }
        }.formUrlEncode()
        return env.buildWebVaultUrl() + "#/sso?" + query
    }

    fun accept(callback: String, redirectUri: String): SsoAuthorization {
        val expected = Url(redirectUri)
        val actual = runCatching { Url(callback) }.getOrNull()
            ?: invalidCallback()
        if (!matchesCallback(actual, expected)) invalidCallback()
        val states = actual.parameters.getAll("state")
        val received = states?.singleOrNull() ?: invalidCallback()
        if (received.substringBefore("_identifier=") != state) invalidCallback()
        if (actual.parameters.contains("error")) throw SsoException(SsoFailure.AUTHENTICATION)
        val code = actual.parameters.getAll("code")?.singleOrNull()?.takeIf { it.isNotBlank() }
            ?: invalidCallback()
        val identifier = received.substringAfter("_identifier=", "").takeIf { it.isNotBlank() }
        if (organizationIdentifier != null && organizationIdentifier != identifier) {
            invalidCallback()
        }
        return SsoAuthorization(this, code, redirectUri, identifier)
    }

    private fun matchesCallback(actual: Url, expected: Url): Boolean {
        val originMatches = actual.protocol == expected.protocol && actual.host == expected.host &&
            actual.port == expected.port
        // HTTP browsers normalize an empty redirect path to '/', including localhost callbacks.
        val pathMatches = actual.encodedPath.ifEmpty { "/" } == expected.encodedPath.ifEmpty { "/" } &&
            actual.fragment.isEmpty()
        val hasCredentials = !actual.user.isNullOrEmpty() || !actual.password.isNullOrEmpty()
        return originMatches && pathMatches && !hasCredentials
    }

    private fun invalidCallback(): Nothing = throw SsoException(SsoFailure.CALLBACK)

    companion object {
        private const val RANDOM_BYTES = 32
        const val CLIENT_ID = "desktop"
        const val NATIVE_CALLBACK = "bitwarden://sso-callback"
        const val TIMEOUT_MS = 300_000L

        fun create(
            env: ServerEnv,
            email: String,
            crypto: CryptoGenerator,
            base64: Base64Service,
            organizationIdentifier: String? = null,
        ): SsoRequest {
            fun encode(bytes: ByteArray) = base64.url(base64.encodeToString(bytes))
            val verifier = encode(crypto.seed(RANDOM_BYTES))
            return SsoRequest(
                env = env,
                email = email,
                state = encode(crypto.seed(RANDOM_BYTES)),
                verifier = verifier,
                challenge = encode(crypto.hashSha256(verifier.encodeToByteArray())),
                organizationIdentifier = organizationIdentifier,
            )
        }
    }
}

class SsoAuthorization(
    val request: SsoRequest,
    val code: String,
    val redirectUri: String,
    val organizationIdentifier: String?,
)

class SsoBrowserPrompt(val request: SsoRequest) {
    val result = CompletableDeferred<SsoAuthorization>()
    fun complete(callback: String, redirectUri: String) {
        result.completeWith(runCatching { request.accept(callback, redirectUri) })
    }

    /** Maps a native browser session result: a callback, a failure, or a cancellation. */
    fun finish(callback: String?, failed: Boolean) = when {
        callback != null -> complete(callback, SsoRequest.NATIVE_CALLBACK)
        failed -> fail()
        else -> cancel()
    }

    fun cancel() { result.cancel() }
    fun fail() { result.completeExceptionally(SsoException(SsoFailure.AUTHENTICATION)) }

    /** Runs the browser hand-off: a failure completes the prompt, a cancellation cancels it. */
    // Presentation boundary: surface browser, network, and protocol failures; cancellation is rethrown.
    @Suppress("TooGenericExceptionCaught")
    suspend fun drive(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            cancel()
            throw error
        } catch (error: Exception) {
            result.completeExceptionally(error)
        }
    }
}

enum class SsoFailure { CALLBACK, AUTHENTICATION, UNSUPPORTED, PASSWORD, KEY_CONNECTOR, ACCOUNT, PORTS }

class SsoException(val failure: SsoFailure) : RuntimeException("SSO: ${failure.name}"), Readable {
    override val title: TextHolder = TextHolder.Res(when (failure) {
        SsoFailure.CALLBACK -> Res.string.sso_error_callback
        SsoFailure.AUTHENTICATION -> Res.string.sso_error_authentication
        SsoFailure.UNSUPPORTED -> Res.string.sso_error_unsupported
        SsoFailure.PASSWORD -> Res.string.sso_error_password
        SsoFailure.KEY_CONNECTOR -> Res.string.sso_error_key_connector
        SsoFailure.ACCOUNT -> Res.string.sso_error_account
        SsoFailure.PORTS -> Res.string.sso_error_ports
    })
}
