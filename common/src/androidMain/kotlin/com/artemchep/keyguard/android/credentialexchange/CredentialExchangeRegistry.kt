package com.artemchep.keyguard.android.credentialexchange

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmapOrNull
import androidx.credentials.providerevents.ProviderEventsManager
import androidx.credentials.providerevents.transfer.ClearExportRequest
import androidx.credentials.providerevents.transfer.ExportEntry
import androidx.credentials.providerevents.transfer.RegisterExportRequest
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRegistration
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI
import org.kodein.di.instance

internal interface CredentialExchangeRegistrationBackend {
    suspend fun register(
        registrations: List<ExposedAccountRegistration>,
    ): Boolean

    suspend fun unregister(): Boolean
}

/**
 * Wraps the `androidx.credentials.providerevents` export-registration APIs so that
 * Keyguard advertises itself as a source for the Android 15+/GMS "Transfer passwords
 * & passkeys" flow.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class CredentialExchangeRegistry(
    private val context: Context,
    private val logRepository: LogRepository,
) : CredentialExchangeRegistrationBackend {
    companion object {
        private const val TAG = "CredentialExchangeRegistry"

        // The CXF v1.0 type strings Keyguard can export. Derived from the format
        // layer's capability set — the androidx `CredentialTypes` constants carry
        // the same values — rather than kept as a second list by hand.
        private val SUPPORTED_CREDENTIAL_TYPES = CxfCredentialType.EXPORTABLE
            .mapTo(mutableSetOf()) { it.serialName }
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
        logRepository = directDI.instance(),
    )

    /**
     * Registers Keyguard as a credential-export provider.
     *
     * @return `true` if the registration succeeded, `false` if it failed softly (e.g.
     * on a build without the Play Services backend).
     */
    override suspend fun register(
        registrations: List<ExposedAccountRegistration>,
    ): Boolean = runCatchingNonFatal {
        // Called on the process lifecycle scope, i.e. the main dispatcher, while
        // every step below blocks: `RegisterExportRequest.create` is annotated
        // `@WorkerThread` and reads a matcher blob out of the assets, and the
        // icon is decoded and rescaled.
        withContext(Dispatchers.IO) {
            val icon = ContextCompat
                .getDrawable(context, R.mipmap.ic_launcher)
                ?.toBitmapOrNull()
                ?: run {
                    logRepository.post(
                        tag = TAG,
                        message = "Failed to load the launcher icon for the export entry.",
                        level = LogLevel.WARNING,
                    )
                    return@withContext false
                }

            val appName = context.applicationInfo
                .loadLabel(context.packageManager)
                .toString()

            if (registrations.isEmpty()) {
                // Nothing to advertise. Registering an empty entry list would be
                // rejected by the platform, and there is a dedicated call for this.
                logRepository.post(
                    tag = TAG,
                    message = "No accounts to register for credential exchange export.",
                    level = LogLevel.WARNING,
                )
                return@withContext false
            }

            val request = RegisterExportRequest.create(
                context = context,
                entries = registrations.map { registration ->
                    ExportEntry(
                        id = registration.entryId,
                        accountDisplayName = registration.label
                            .ifBlank { appName },
                        userDisplayName = "", // sub-header for some reason
                        icon = icon,
                        supportedCredentialTypes = SUPPORTED_CREDENTIAL_TYPES,
                    )
                },
            )

            callOptionalCredentialExchangeBackend {
                ProviderEventsManager
                    .create(context)
                    .registerExport(request)
            }
            logRepository.post(
                tag = TAG,
                message = "Registered ${registrations.size} account(s) " +
                    "for credential exchange export.",
                level = LogLevel.INFO,
            )
            true
        }
    }.getOrElse { e ->
        logRepository.post(
            tag = TAG,
            message = "Failed to register for credential exchange export: ${e.message}",
            level = LogLevel.WARNING,
        )
        false
    }

    /**
     * Removes Keyguard's credential-export registration.
     *
     * @return `true` if the un-registration succeeded,
     * `false` if it failed softly.
     */
    override suspend fun unregister(): Boolean = runCatchingNonFatal {
        withContext(Dispatchers.IO) {
            callOptionalCredentialExchangeBackend {
                ProviderEventsManager
                    .create(context)
                    .clearExport(ClearExportRequest())
            }
            logRepository.post(
                tag = TAG,
                message = "Unregistered from credential exchange export.",
                level = LogLevel.INFO,
            )
            true
        }
    }.getOrElse { e ->
        logRepository.post(
            tag = TAG,
            message = "Failed to unregister from credential exchange export: ${e.message}",
            level = LogLevel.WARNING,
        )
        false
    }
}
