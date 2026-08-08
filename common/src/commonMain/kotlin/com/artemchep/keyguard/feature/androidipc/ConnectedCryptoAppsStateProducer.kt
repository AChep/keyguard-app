package com.artemchep.keyguard.feature.androidipc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegisteredApp
import com.artemchep.keyguard.common.service.androidipc.AndroidIpcRegistrationService
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.util.UniqueKeyBuilder
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.connected_crypto_apps_revoke_message
import com.artemchep.keyguard.res.connected_crypto_apps_revoke_title
import com.artemchep.keyguard.res.connected_crypto_apps_unknown
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.time.Instant

@Composable
fun produceConnectedCryptoAppsState(): Loadable<ConnectedCryptoAppsState> =
    with(localDI().direct) {
        produceConnectedCryptoAppsState(
            registrations = instance(),
            dateFormatter = instance(),
            confirmationRouteFactory = instance(),
        )
    }

@Composable
fun produceConnectedCryptoAppsState(
    registrations: AndroidIpcRegistrationService,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Loadable<ConnectedCryptoAppsState> = produceScreenState(
    key = "connected_apps",
    initial = Loadable.Loading,
    args = arrayOf(
        registrations,
        dateFormatter,
        confirmationRouteFactory,
    ),
) {
    connectedCryptoAppsStateProducer(
        registrations = registrations,
        dateFormatter = dateFormatter,
        confirmationRouteFactory = confirmationRouteFactory,
    )
}

suspend fun RememberStateFlowScope.connectedCryptoAppsStateProducer(
    registrations: AndroidIpcRegistrationService,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Flow<Loadable<ConnectedCryptoAppsState>> {
    suspend fun promptRevoke(app: AndroidIpcRegisteredApp) {
        val title = translate(
            Res.string.connected_crypto_apps_revoke_title,
            app.appLabel,
        )
        val message = translate(
            Res.string.connected_crypto_apps_revoke_message,
        )

        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ) {
            registrations.revoke(app.packageName)
                .launchIn(appScope)
        }
        navigate(intent)
    }

    return registrations.registrations().map { apps ->
        val keyBuilder = UniqueKeyBuilder()
        val stateApps = apps.map { app ->
            val registeredAt = app.registeredAtEpochMilliseconds
                .asDisplayInstant(dateFormatter) {
                    translate(Res.string.connected_crypto_apps_unknown)
                }
            val lastUsedAt = app.lastUsedAtEpochMilliseconds
                .asDisplayInstant(dateFormatter) {
                    translate(Res.string.connected_crypto_apps_unknown)
                }
            ConnectedCryptoAppsState.App(
                key = keyBuilder.build(app.packageName),
                packageName = app.packageName,
                label = app.appLabel,
                signer = app.certificateDigests.joinToString(),
                registeredAt = registeredAt,
                lastUsedAt = lastUsedAt,
                installed = app.installed,
                signerMismatch = app.signerMismatch,
                onRevoke = onClick {
                    promptRevoke(app)
                },
            )
        }.mapListShape()
        ConnectedCryptoAppsState(
            apps = stateApps,
        ).let { state ->
            Loadable.Ok(state)
        }
    }
}

private suspend fun Long.asDisplayInstant(
    dateFormatter: DateFormatter,
    unknown: suspend () -> String,
): String = runCatching {
    Instant.fromEpochMilliseconds(this)
        .let(dateFormatter::formatDateTime)
}.getOrElse {
    unknown()
}
