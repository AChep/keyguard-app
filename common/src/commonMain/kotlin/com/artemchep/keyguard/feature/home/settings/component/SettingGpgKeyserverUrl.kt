package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val GPG_KEYSERVER_URL_ITEM_KEY = "gpg_keyserver_url"

fun settingGpgKeyserverUrlProvider(
    directDI: DirectDI,
) = settingGpgKeyserverUrlProvider(
    getGpgKeyserverConfig = directDI.instance(),
    putGpgKeyserverConfig = directDI.instance(),
    confirmationRouteFactory = directDI.instance(),
    showMessage = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingGpgKeyserverUrlProvider(
    getGpgKeyserverConfig: GetGpgKeyserverConfig,
    putGpgKeyserverConfig: PutGpgKeyserverConfig,
    confirmationRouteFactory: ConfirmationRouteFactory,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getGpgKeyserverConfig()
    .map { config ->
        val onUrlChange = { url: String ->
            putGpgKeyserverConfig(config.copy(url = url))
                .launchIn(windowCoroutineScope)
            Unit
        }
        val onUrlError = { error: String ->
            val message = ToastMessage(
                title = error,
                type = ToastMessage.Type.ERROR,
            )
            showMessage.copy(message)
            Unit
        }

        SettingIi(
            platformClasses = listOf(
                Platform.Mobile.Android::class,
                Platform.Desktop.Linux::class,
                Platform.Desktop.MacOS::class,
                Platform.Desktop.Windows::class,
                Platform.Desktop.Other::class,
            ),
            search = SettingIi.Search(
                group = "security",
                tokens = listOf(
                    "gpg",
                    "keyserver",
                    "url",
                    "openpgp",
                ),
            ),
        ) {
            SettingGpgKeyserverUrl(
                url = config.url,
                confirmationRouteFactory = confirmationRouteFactory,
                onUrlChange = onUrlChange,
                onUrlError = onUrlError,
            )
        }
    }

@Composable
private fun SettingGpgKeyserverUrl(
    url: String,
    confirmationRouteFactory: ConfirmationRouteFactory,
    onUrlChange: (String) -> Unit,
    onUrlError: (String) -> Unit,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val updatedUrl by rememberUpdatedState(url)
    val updatedConfirmationRouteFactory by rememberUpdatedState(confirmationRouteFactory)
    val updatedOnUrlChange by rememberUpdatedState(onUrlChange)
    val updatedOnUrlError by rememberUpdatedState(onUrlError)

    val title = stringResource(Res.string.pref_item_gpg_keyserver_url_title)
    val text = stringResource(Res.string.pref_item_gpg_keyserver_url_text)
    val error = stringResource(Res.string.error_invalid_url)
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Public,
        title = title,
        text = updatedUrl,
        onClick = {
            val route = updatedConfirmationRouteFactory.registerRouteResultReceiver(
                args = ConfirmationRoute.Args(
                    icon = icon(Icons.Outlined.Public),
                    title = title,
                    message = text,
                    items = listOf(
                        ConfirmationRoute.Args.Item.StringItem(
                            key = GPG_KEYSERVER_URL_ITEM_KEY,
                            value = updatedUrl,
                            title = title,
                            type = ConfirmationRoute.Args.Item.StringItem.Type.URI,
                            canBeEmpty = false,
                        ),
                    ),
                ),
            ) { result ->
                if (result !is ConfirmationResult.Confirm) {
                    return@registerRouteResultReceiver
                }

                val newUrl = result.data[GPG_KEYSERVER_URL_ITEM_KEY] as? String
                    ?: return@registerRouteResultReceiver
                val normalizedUrl = newUrl.trim()
                val isValid = normalizedUrl.isNotBlank() &&
                        (normalizedUrl.startsWith("http://") ||
                                normalizedUrl.startsWith("https://"))
                if (isValid) {
                    updatedOnUrlChange(normalizedUrl)
                } else {
                    updatedOnUrlError(error)
                }
            }
            val intent = NavigationIntent.NavigateToRoute(
                route = route,
            )
            navigationController.queue(intent)
        },
    )
}
