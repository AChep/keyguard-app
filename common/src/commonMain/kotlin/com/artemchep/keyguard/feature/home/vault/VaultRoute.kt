package com.artemchep.keyguard.feature.home.vault

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res

data class VaultRoute(
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
        fun by(accounts: Collection<DAccount>) = VaultRoute(
            args = Args(
                appBar = Args.AppBar(
                    title = accounts.joinToString { it.username },
                    subtitle = if (accounts.size > 1) {
                        "Accounts"
                    } else {
                        "Account"
                    },
                ),
                filter = DFilter.Or(
                    filters = accounts
                        .map { account ->
                            DFilter.ById(
                                id = account.accountId(),
                                what = DFilter.ById.What.ACCOUNT,
                            )
                        },
                ),
                preselect = false,
                canAddSecrets = false,
            ),
        )

        //
        // Organization
        //

        fun by(translator: TranslatorScope, organization: DOrganization) = by(
            translator = translator,
            organizations = listOf(organization),
        )

        @JvmName("byOrganizations")
        fun by(translator: TranslatorScope, organizations: Collection<DOrganization>) = VaultRoute(
            args = Args(
                appBar = Args.AppBar(
                    title = organizations.joinToString { it.name },
                    subtitle = if (organizations.size > 1) {
                        translator.translate(Res.strings.organizations)
                    } else {
                        translator.translate(Res.strings.organization)
                    },
                ),
                filter = DFilter.Or(
                    filters = organizations
                        .map { organization ->
                            DFilter.And(
                                filters = listOf(
                                    DFilter.ById(
                                        id = organization.accountId,
                                        what = DFilter.ById.What.ACCOUNT,
                                    ),
                                    DFilter.ById(
                                        id = organization.id,
                                        what = DFilter.ById.What.ORGANIZATION,
                                    ),
                                ),
                            )
                        },
                ),
                preselect = false,
                canAddSecrets = false,
            ),
        )

        //
        // Collection
        //

        fun by(translator: TranslatorScope, collection: DCollection) = by(
            translator = translator,
            collections = listOf(collection),
        )

        @JvmName("byCollections")
        fun by(translator: TranslatorScope, collections: Collection<DCollection>) = VaultRoute(
            args = Args(
                appBar = Args.AppBar(
                    title = collections.joinToString { it.name },
                    subtitle = if (collections.size > 1) {
                        translator.translate(Res.strings.collections)
                    } else {
                        translator.translate(Res.strings.collection)
                    },
                ),
                filter = DFilter.Or(
                    filters = collections
                        .map { collection ->
                            DFilter.And(
                                filters = listOf(
                                    DFilter.ById(
                                        id = collection.accountId,
                                        what = DFilter.ById.What.ACCOUNT,
                                    ),
                                    DFilter.ById(
                                        id = collection.id,
                                        what = DFilter.ById.What.COLLECTION,
                                    ),
                                ),
                            )
                        },
                ),
                preselect = false,
                canAddSecrets = false,
            ),
        )

        //
        // Folder
        //

        fun by(translator: TranslatorScope, folder: DFolder) = by(
            translator = translator,
            folders = listOf(folder),
        )

        @JvmName("byFolders")
        fun by(translator: TranslatorScope, folders: Collection<DFolder>) = VaultRoute(
            args = Args(
                appBar = Args.AppBar(
                    title = folders.joinToString { it.name },
                    subtitle = if (folders.size > 1) {
                        translator.translate(Res.strings.folders)
                    } else {
                        translator.translate(Res.strings.folder)
                    },
                ),
                filter = DFilter.Or(
                    filters = folders
                        .map { folder ->
                            DFilter.And(
                                filters = listOf(
                                    DFilter.ById(
                                        id = folder.accountId,
                                        what = DFilter.ById.What.ACCOUNT,
                                    ),
                                    DFilter.ById(
                                        id = folder.id,
                                        what = DFilter.ById.What.FOLDER,
                                    ),
                                ),
                            )
                        },
                ),
                preselect = false,
                canAddSecrets = false,
            ),
        )

        //
        // Watchtower
        //

        fun watchtower(
            title: String,
            subtitle: String?,
            filter: DFilter? = null,
            trash: Boolean? = false,
            sort: Sort? = null,
            searchBy: Args.SearchBy = Args.SearchBy.ALL,
        ) = VaultRoute(
            args = Args(
                appBar = Args.AppBar(
                    title = title,
                    subtitle = subtitle ?: "Watchtower",
                ),
                filter = filter,
                sort = sort,
                trash = trash,
                searchBy = searchBy,
                preselect = false,
                canAddSecrets = false,
            ),
        )
    }

    data class Args(
        val appBar: AppBar? = null,
        val filter: DFilter? = null,
        val sort: Sort? = null,
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
        VaultScreen(
            args = args,
        )
    }
}
