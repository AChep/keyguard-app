package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.platform.LeContext

/**
 * The platform side of the credential-exchange import flow: asks the OS to
 * let the user pick a source credential provider and returns the provider's
 * raw CXF payload.
 *
 * Only bound on platforms with a transfer broker, so consumers resolve it with
 * `instanceOrNull` and report the flow as unavailable when it is absent.
 */
interface CredentialExchangeImportTransport {
    /**
     * Launches the platform provider picker requesting the given CXF
     * [credentialTypes] (their serial names) and suspends until the transfer
     * finishes. [context] must be anchored to the current UI, so the platform
     * can attach its picker to the running Activity.
     */
    suspend fun importCredentials(
        context: LeContext,
        credentialTypes: Set<String>,
    ): CredentialExchangeImportTransportResult
}

sealed interface CredentialExchangeImportTransportResult {
    /**
     * The source provider answered with a CXF JSON [payload].
     * [sourcePackageName] identifies the exporting application when the
     * platform reports it.
     */
    data class Success(
        val payload: String,
        val sourcePackageName: String?,
    ) : CredentialExchangeImportTransportResult

    /**
     * The user dismissed the picker or cancelled the transfer in the source
     * application. Not an error — the caller returns to its idle state.
     */
    data object Cancelled : CredentialExchangeImportTransportResult

    data class Failure(
        val kind: Kind,
    ) : CredentialExchangeImportTransportResult {
        enum class Kind {
            /**
             * No installed application is registered as a credential-export
             * provider matching the request.
             */
            NoExportingProviders,

            /**
             * The transfer broker is unavailable — e.g. a build without the
             * Play Services backend, or a device without a compatible
             * Play Services version.
             */
            Unavailable,

            /**
             * The source provider produced data the broker rejected.
             */
            InvalidData,

            Unknown,
        }
    }
}
