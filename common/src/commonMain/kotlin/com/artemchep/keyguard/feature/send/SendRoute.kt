package com.artemchep.keyguard.feature.send

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSendFilter
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.send.search.SendSort

data class SendRoute(
    val args: Args = Args(),
) : Route {
    companion object {
        //
        // Account
        //

        fun by(account: DAccount) = by(
            accounts = listOf(account),
        )

        @JvmName("byAccounts")
        fun by(accounts: Collection<DAccount>) = SendRoute(
            args = Args(
                appBar = Args.AppBar(
                    title = accounts.joinToString { it.username },
                    subtitle = if (accounts.size > 1) {
                        "Accounts"
                    } else {
                        "Account"
                    },
                ),
                filter = DSendFilter.Or(
                    filters = accounts
                        .map { account ->
                            DSendFilter.ById(
                                id = account.accountId(),
                                what = DSendFilter.ById.What.ACCOUNT,
                            )
                        },
                ),
                preselect = false,
                canAddSecrets = false,
            ),
        )
    }

    data class Args(
        val appBar: AppBar? = null,
        val filter: DSendFilter? = null,
        val sort: SendSort? = null,
        val main: Boolean = false,
        val searchBy: SearchBy = SearchBy.ALL,
        /**
         * `true` to show only trashed items, `false` to show
         * only active items, `null` to show both.
         */
        val trash: Boolean? = false,
        val preselect: Boolean = true,
        val canAddSecrets: Boolean = true,
    ) {
        enum class SearchBy {
            ALL,
            PASSWORD,
        }

        val canAlwaysShowKeyboard: Boolean get() = appBar?.title == null

        val canQuickFilter: Boolean get() = appBar?.title == null

        data class AppBar(
            val title: String? = null,
            val subtitle: String? = null,
        )
    }

    @Composable
    override fun Content() {
        SendScreen(args)
    }
}
