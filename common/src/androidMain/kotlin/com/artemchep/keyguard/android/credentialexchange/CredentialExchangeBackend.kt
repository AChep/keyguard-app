package com.artemchep.keyguard.android.credentialexchange

/**
 * A missing or incompatible optional Play Services credential-exchange backend.
 */
internal class CredentialExchangeBackendUnavailableException(
    cause: LinkageError,
) : Exception(cause)

/**
 * Isolates linkage failures to the optional Play Services backend call.
 *
 * Keep [block] limited to [androidx.credentials.providerevents.ProviderEventsManager]
 * calls so unrelated linkage failures retain their normal fatal semantics.
 */
internal inline fun <T> callOptionalCredentialExchangeBackend(
    block: () -> T,
): T {
    try {
        return block()
    } catch (e: LinkageError) {
        throw CredentialExchangeBackendUnavailableException(e)
    }
}
