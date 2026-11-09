@file:Suppress("MagicNumber") // CTAP command and status bytes.

package com.artemchep.keyguard.util.fidohybrid

import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import com.artemchep.keyguard.util.webauthn.WebAuthnInvalidStateException
import com.artemchep.keyguard.util.webauthn.WebAuthnNotSupportedException
import kotlinx.coroutines.CancellationException

internal class HybridCtapResponse(
    val bytes: ByteArray,
    val finished: Boolean,
)

/** Command state belongs to one encrypted QR connection. */
internal class HybridCtapSession(
    private val aaguid: ByteArray,
) {
    private val prf = HybridPrfSession()

    suspend fun handle(
        command: ByteArray,
        authenticator: HybridAuthenticator,
    ): HybridCtapResponse {
        require(command.isNotEmpty())
        val operation = command[0].toInt()
        var final = operation == MAKE_CREDENTIAL || operation == GET_ASSERTION

        fun failure(status: Int) = HybridCtapResponse(HybridCtap.status(status), final)

        return try {
            when (operation) {
                GET_INFO -> {
                    HybridCtapResponse(HybridCtap.success(HybridCtap.getInfo(aaguid)), false)
                }

                CLIENT_PIN -> {
                    HybridCtapResponse(prf.keyAgreement(HybridCtap.parameters(command)), false)
                }

                MAKE_CREDENTIAL -> {
                    makeCredential(command, authenticator)
                }

                GET_ASSERTION -> {
                    val parameters = HybridCtap.parameters(command)
                    // The interactive request follows a pre-flight, so it must not end the transaction.
                    final = !HybridCtap.isSilent(parameters)
                    getAssertion(parameters, authenticator, final)
                }

                else -> HybridCtapResponse(HybridCtap.status(HybridCtap.INVALID_COMMAND), false)
            }
        } catch (e: CancellationException) {
            // Cancellation is an IllegalStateException; it must never become a CTAP status.
            throw e
        } catch (e: HybridCtapException) {
            failure(e.status)
        } catch (_: WebAuthnNotSupportedException) {
            failure(HybridCtap.UNSUPPORTED_ALGORITHM)
        } catch (_: WebAuthnInvalidStateException) {
            failure(HybridCtap.CREDENTIAL_EXCLUDED)
        } catch (_: IllegalArgumentException) {
            failure(HybridCtap.INVALID_CBOR)
        } catch (_: IllegalStateException) {
            failure(HybridCtap.INVALID_CBOR)
        }
    }

    private suspend fun makeCredential(
        command: ByteArray,
        authenticator: HybridAuthenticator,
    ): HybridCtapResponse {
        val parameters = HybridCtap.parameters(command)
        // Chromium's CTAP 2.0 touch request. Scanning the QR already selected this phone, and
        // Chromium counts only success or a PIN error as a touch. Never create a passkey for it.
        if ((parameters[2L] as? Map<*, *>)?.get("id") == ".dummy") {
            return HybridCtapResponse(HybridCtap.status(HybridCtap.PIN_AUTH_INVALID), false)
        }
        val request = registrationRequest(parameters, prf)
        return try {
            val response =
                respond(
                    authenticator.makeCredential(request),
                    HybridCtap::registrationResponse,
                    WebAuthnCredentialCreationResult::clear,
                )
            HybridCtapResponse(response, true)
        } finally {
            request.clear()
        }
    }

    private suspend fun getAssertion(
        parameters: Map<*, *>,
        authenticator: HybridAuthenticator,
        userPresent: Boolean,
    ): HybridCtapResponse {
        val request = assertionRequest(parameters, prf, userPresent)
        val userSelected = userPresent && !request.options.allowedCredentials.isAllowCredentialsSupplied
        return try {
            val response =
                respond(
                    authenticator.getAssertion(request),
                    { HybridCtap.assertionResponse(it, userSelected) },
                    WebAuthnAssertionResult::clear,
                )
            HybridCtapResponse(response, userPresent)
        } finally {
            request.clear()
        }
    }

    private fun <T> respond(
        result: HybridResult<T>,
        encode: (T) -> ByteArray,
        clear: (T) -> Unit,
    ): ByteArray =
        when (result) {
            is HybridResult.Success -> {
                try {
                    encode(result.value)
                } finally {
                    clear(result.value)
                }
            }

            HybridResult.Denied -> {
                HybridCtap.status(HybridCtap.DENIED)
            }

            HybridResult.NoCredentials -> {
                HybridCtap.status(HybridCtap.NO_CREDENTIALS)
            }

            HybridResult.CredentialExcluded -> {
                HybridCtap.status(HybridCtap.CREDENTIAL_EXCLUDED)
            }
        }

    fun clear() = prf.clear()
}

// CTAP 2.2 section 6 authenticator command codes.
private const val MAKE_CREDENTIAL = 0x01
private const val GET_ASSERTION = 0x02
private const val GET_INFO = 0x04
private const val CLIENT_PIN = 0x06
