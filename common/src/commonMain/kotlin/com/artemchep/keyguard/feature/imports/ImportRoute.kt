package com.artemchep.keyguard.feature.imports

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon

/**
 * The import flow: the user picks a source — another installed credential
 * provider through the platform transfer broker, or an export file of another
 * password manager — and after a review the items are created in the account
 * the flow was launched from.
 *
 * There is no elevated-access gate: the flow only writes new items and reveals
 * nothing from the vault.
 */
class ImportRoute(
    val args: Args,
) : Route {
    companion object {
        /**
         * A per-account action opening the import flow, or `null` on a watch.
         */
        suspend fun actionOrNull(
            translator: TranslatorScope,
            accountId: AccountId,
            navigate: (NavigationIntent) -> Unit,
        ): FlatItemAction? {
            val platform = CurrentPlatform
            val supported = platform !is Platform.Mobile.Android || !platform.isWatch
            if (!supported) {
                return null
            }
            val title = translator.translate(
                res = Res.string.account_action_import_credentials_title,
            )
            return FlatItemAction(
                id = "import.${accountId.id}",
                icon = Icons.Outlined.SystemUpdateAlt,
                trailing = {
                    ChevronIcon()
                },
                title = TextHolder.Value(title),
                onClick = {
                    val route = ImportRoute(
                        args = Args(
                            accountId = accountId,
                        ),
                    )
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                },
            )
        }
    }

    data class Args(
        /**
         * The account the imported items and folders are created in.
         */
        val accountId: AccountId,
        /**
         * Opens the backup configured in Settings › Backups right away,
         * instead of listing the sources.
         */
        val configuredBackup: Boolean = false,
    )

    override val descriptor
        get() = RouteDescriptor.Import(
            accountId = args.accountId.id,
            configuredBackup = args.configuredBackup,
        )

    @Composable
    override fun Content() {
        ImportScreen(
            args = args,
        )
    }
}
