package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.browserextension.ui.BrowserExtensionSettingsState
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.home.settings.browser.LocalBrowserExtensionSettingsState
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.browser_extension_approval_pair_name_label
import com.artemchep.keyguard.res.browser_extension_pairing_connected
import com.artemchep.keyguard.res.browser_extension_pairing_last_used_at
import com.artemchep.keyguard.res.browser_extension_pairing_paired_at
import com.artemchep.keyguard.res.browser_extension_pairing_remove_text
import com.artemchep.keyguard.res.browser_extension_pairing_remove_title
import com.artemchep.keyguard.res.browser_extension_pairings_empty
import com.artemchep.keyguard.res.browser_extension_pairings_error
import com.artemchep.keyguard.res.remove
import com.artemchep.keyguard.res.rename
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.ok
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

private const val PAIRING_NAME_ITEM_KEY = "name"

fun settingBrowserExtensionPairingsProvider(
    koinScope: Scope,
) = browserExtensionSettingProvider {
    settingBrowserExtensionPairingsProvider(
        dateFormatter = koinScope.get(),
        confirmationRouteFactory = koinScope.get(),
    )
}

/**
 * The paired browsers, on the browser extension settings screen only.
 * Not searchable: the screen reads them.
 */
fun settingBrowserExtensionPairingsProvider(
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
): SettingComponent = flowOf(
    SettingIi(
        platformClasses = browserExtensionPlatformClasses,
    ) {
        SettingBrowserExtensionPairings(
            dateFormatter = dateFormatter,
            confirmationRouteFactory = confirmationRouteFactory,
        )
    },
)

@Composable
private fun SettingBrowserExtensionPairings(
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
) {
    val state = LocalBrowserExtensionSettingsState.current
        ?: return
    Column {
        if (state.pairings.isEmpty()) {
            SettingBrowserExtensionText(
                text = stringResource(Res.string.browser_extension_pairings_empty),
            )
        }
        SettingBrowserExtensionRows(
            items = state.pairings,
            itemKey = { pairing -> pairing.id },
        ) { pairing ->
            SettingBrowserExtensionPairing(
                pairing = pairing,
                dateFormatter = dateFormatter,
                confirmationRouteFactory = confirmationRouteFactory,
            )
        }
        if (state.pairingFailed) {
            FlatSimpleNote(
                type = SimpleNote.Type.ERROR,
                text = stringResource(Res.string.browser_extension_pairings_error),
            )
        }
    }
}

@Composable
private fun SettingBrowserExtensionPairing(
    pairing: BrowserExtensionSettingsState.Pairing,
    dateFormatter: DateFormatter,
    confirmationRouteFactory: ConfirmationRouteFactory,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    // The state may change while a dialog is open. A dialog acts
    // only on the pairing it was opened for.
    val updatedPairing by rememberUpdatedState(pairing)

    val nameLabel = stringResource(Res.string.browser_extension_approval_pair_name_label)
    val renameTitle = stringResource(Res.string.rename)
    val removeTitle = stringResource(Res.string.browser_extension_pairing_remove_title)
    val removeText = stringResource(Res.string.browser_extension_pairing_remove_text, pairing.name)
    val onRename = pairing.onRename?.let {
        // lambda
        {
            val intent = confirmationRouteFactory.renamePairingIntent(
                title = renameTitle,
                label = nameLabel,
                name = pairing.name,
            ) { name ->
                updatedPairing.takeIf { it.id == pairing.id }?.onRename?.invoke(name)
            }
            navigationController.queue(intent)
        }
    }
    val onRemove = pairing.onRemove?.let {
        // lambda
        {
            val intent = confirmationRouteFactory.removePairingIntent(
                title = removeTitle,
                message = removeText,
            ) {
                updatedPairing.takeIf { it.id == pairing.id }?.onRemove?.invoke()
            }
            navigationController.queue(intent)
        }
    }
    LocalSettingPaneComponents.current.KgPicker(
        icon = Icons.Outlined.Language,
        title = {
            Text(pairing.name)
        },
        titleText = pairing.name,
        text = {
            SettingBrowserExtensionPairingDates(
                pairing = pairing,
                dateFormatter = dateFormatter,
            )
        },
        trailing = if (pairing.connected) {
            // composable
            {
                Text(
                    text = stringResource(Res.string.browser_extension_pairing_connected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.ok,
                )
            }
        } else {
            null
        },
        dropdown = pairingDropdown(
            id = pairing.id,
            onRename = onRename,
            onRemove = onRemove,
        ),
    )
}

@Composable
private fun SettingBrowserExtensionPairingDates(
    pairing: BrowserExtensionSettingsState.Pairing,
    dateFormatter: DateFormatter,
) {
    Column {
        Text(
            stringResource(
                Res.string.browser_extension_pairing_paired_at,
                dateFormatter.formatDate(pairing.createdAt),
            ),
        )
        Text(
            stringResource(
                Res.string.browser_extension_pairing_last_used_at,
                dateFormatter.formatDate(pairing.lastUsedAt),
            ),
        )
    }
}

private fun pairingDropdown(
    id: String,
    onRename: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) = buildContextItems {
    section {
        if (onRename != null) {
            this += FlatItemAction(
                id = "settings.browserExtension.pairing.$id.rename",
                icon = Icons.Outlined.Edit,
                title = Res.string.rename.wrap(),
                onClick = onRename,
            )
        }
        if (onRemove != null) {
            this += FlatItemAction(
                id = "settings.browserExtension.pairing.$id.remove",
                icon = Icons.Outlined.Delete,
                title = Res.string.remove.wrap(),
                danger = true,
                onClick = onRemove,
            )
        }
    }
}

/** Asks for the new name of a pairing. */
private fun ConfirmationRouteFactory.renamePairingIntent(
    title: String,
    label: String,
    name: String,
    onRename: (String) -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        args = ConfirmationRoute.Args(
            icon = icon(Icons.Outlined.Edit),
            title = title,
            items = listOf(
                ConfirmationRoute.Args.Item.StringItem(
                    key = PAIRING_NAME_ITEM_KEY,
                    value = name,
                    title = label,
                    canBeEmpty = false,
                ),
            ),
        ),
    ) { result ->
        val newName = (result as? ConfirmationResult.Confirm)
            ?.data
            ?.get(PAIRING_NAME_ITEM_KEY) as? String
        if (newName != null) {
            onRename(newName)
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}

/** Asks to confirm removing a pairing. */
private fun ConfirmationRouteFactory.removePairingIntent(
    title: String,
    message: String,
    onRemove: () -> Unit,
): NavigationIntent {
    val route = registerRouteResultReceiver(
        args = ConfirmationRoute.Args(
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ),
    ) { result ->
        if (result is ConfirmationResult.Confirm) {
            onRemove()
        }
    }
    return NavigationIntent.NavigateToRoute(route)
}
