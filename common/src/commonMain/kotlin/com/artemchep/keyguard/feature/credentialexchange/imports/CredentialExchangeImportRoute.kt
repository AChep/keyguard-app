package com.artemchep.keyguard.feature.credentialexchange.imports

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon

/**
 * The credential-exchange import flow: the user picks another installed
 * credential provider, Keyguard receives its CXF payload through the platform
 * transfer broker, and after a review the items are created in the account
 * the flow was launched from.
 *
 * There is no elevated-access gate: the flow only writes new items and reveals
 * nothing from the vault, and the source application performs its own user
 * verification before releasing the data.
 */
class CredentialExchangeImportRoute(
    val args: Args,
) : Route {
    companion object {
        /**
         * A per-account action opening the import flow, or `null` on platforms
         * without a credential-transfer broker. On GMS-free Android builds the
         * action stays visible and the flow degrades to an explanatory error.
         */
        suspend fun actionOrNull(
            translator: TranslatorScope,
            accountId: AccountId,
            navigate: (NavigationIntent) -> Unit,
        ): FlatItemAction? {
            if (CurrentPlatform !is Platform.Mobile.Android) {
                return null
            }
            val title = translator.translate(
                res = Res.string.account_action_import_credentials_title,
            )
            return FlatItemAction(
                id = "credentialExchange.import.${accountId.id}",
                icon = Icons.Outlined.SystemUpdateAlt,
                trailing = {
                    ChevronIcon()
                },
                title = TextHolder.Value(title),
                onClick = {
                    val route = CredentialExchangeImportRoute(
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
    )

    @Composable
    override fun Content() {
        CredentialExchangeImportScreen(
            args = args,
        )
    }
}
