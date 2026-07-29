package com.artemchep.keyguard.android.credentialexchange

import androidx.credentials.providerevents.ProviderEventsManager
import androidx.credentials.providerevents.exception.ImportCredentialsCancellationException
import androidx.credentials.providerevents.exception.ImportCredentialsException
import androidx.credentials.providerevents.exception.ImportCredentialsInvalidJsonException
import androidx.credentials.providerevents.exception.ImportCredentialsNoExportOptionException
import androidx.credentials.providerevents.exception.ImportCredentialsProviderConfigurationException
import androidx.credentials.providerevents.exception.ImportCredentialsSystemErrorException
import androidx.credentials.providerevents.transfer.ImportCredentialsRequest
import androidx.credentials.providerevents.transfer.KnownExtensions
import com.artemchep.keyguard.android.closestActivityOrNull
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransport
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransportResult
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.platform.LeContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * Runs the Android 15+/GMS "Transfer passwords & passkeys" flow in the
 * importing role: [ProviderEventsManager.importCredentials] launches the
 * system picker of registered credential-export providers and returns the
 * chosen provider's CXF payload.
 */
class CredentialExchangeImportTransportAndroid(
    private val logRepository: LogRepository,
) : CredentialExchangeImportTransport {
    companion object {
        private const val TAG = "CredentialExchangeImportTransport"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        logRepository = directDI.instance(),
    )

    override suspend fun importCredentials(
        context: LeContext,
        credentialTypes: Set<String>,
    ): CredentialExchangeImportTransportResult {
        val activity = context.context.closestActivityOrNull
            ?: return CredentialExchangeImportTransportResult.Failure(
                kind = CredentialExchangeImportTransportResult.Failure.Kind.Unavailable,
            )
        return runCatchingNonFatal {
            val response = callOptionalCredentialExchangeBackend {
                ProviderEventsManager
                    .create(activity)
                    .importCredentials(
                        context = activity,
                        request = ImportCredentialsRequest(
                            credentialTypes = credentialTypes,
                            knownExtensions = setOf(KnownExtensions.KNOWN_EXTENSION_SHARED),
                        ),
                    )
            }
            val sourcePackageName = runCatchingNonFatal {
                response.callingAppInfo.packageName
            }.getOrNull()
            logRepository.post(
                tag = TAG,
                message = "Received a credential exchange payload " +
                    "from '$sourcePackageName'.",
                level = LogLevel.INFO,
            )
            CredentialExchangeImportTransportResult.Success(
                payload = response.response.responseJson,
                sourcePackageName = sourcePackageName,
            )
        }.getOrElse { e ->
            handleFailure(e)
        }
    }

    /**
     * Maps whatever escaped the transfer call onto a result.
     *
     * Linkage errors from the optional backend have already been converted to
     * [CredentialExchangeBackendUnavailableException]. Cancellation and every
     * fatal error that originated outside that narrow boundary are rethrown.
     */
    internal fun handleFailure(
        e: Throwable,
    ): CredentialExchangeImportTransportResult {
        e.throwIfFatalOrCancellation()
        if (e is ImportCredentialsCancellationException) {
            return CredentialExchangeImportTransportResult.Cancelled
        }
        val kind = when (e) {
            is ImportCredentialsNoExportOptionException ->
                CredentialExchangeImportTransportResult.Failure.Kind.NoExportingProviders

            is ImportCredentialsInvalidJsonException ->
                CredentialExchangeImportTransportResult.Failure.Kind.InvalidData

            is ImportCredentialsProviderConfigurationException,
            is ImportCredentialsSystemErrorException,
            is CredentialExchangeBackendUnavailableException,
            ->
                CredentialExchangeImportTransportResult.Failure.Kind.Unavailable

            is ImportCredentialsException ->
                CredentialExchangeImportTransportResult.Failure.Kind.Unknown

            // An unexpected non-fatal failure outside the provider API.
            else -> CredentialExchangeImportTransportResult.Failure.Kind.Unavailable
        }
        logRepository.post(
            tag = TAG,
            message = "Failed to import credentials: " +
                "${e::class.simpleName} -> $kind.",
            level = LogLevel.WARNING,
        )
        return CredentialExchangeImportTransportResult.Failure(
            kind = kind,
        )
    }
}
