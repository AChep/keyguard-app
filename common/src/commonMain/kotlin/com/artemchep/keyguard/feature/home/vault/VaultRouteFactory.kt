package com.artemchep.keyguard.feature.home.vault

import kotlin.jvm.JvmName

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.feature.home.vault.VaultRoute.Args
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.account
import com.artemchep.keyguard.res.accounts
import com.artemchep.keyguard.res.collection
import com.artemchep.keyguard.res.collections
import com.artemchep.keyguard.res.folder
import com.artemchep.keyguard.res.folders
import com.artemchep.keyguard.res.organization
import com.artemchep.keyguard.res.organizations
import com.artemchep.keyguard.res.tag
import com.artemchep.keyguard.res.tags
import com.artemchep.keyguard.res.watchtower_header_title

interface VaultRouteFactory {
    fun create(
        args: VaultRoute.Args,
    ): Route
}

object VaultRouteFactoryDefault : VaultRouteFactory {
    override fun create(
        args: VaultRoute.Args,
    ): Route {
        return VaultRoute(
            args = args,
        )
    }
}

//
// Account
//

fun VaultRouteFactory.by(account: DAccount) = by(
    accounts = listOf(account),
)

@JvmName("byAccounts")
fun VaultRouteFactory.by(accounts: Collection<DAccount>) = create(
    args = Args(
        appBar = Args.AppBar(
            title = accounts.joinToString { it.username ?: it.host },
            subtitle = if (accounts.size > 1) {
                TextHolder.Res(Res.string.accounts)
            } else {
                TextHolder.Res(Res.string.account)
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

fun VaultRouteFactory.by(organization: DOrganization) = by(
    organizations = listOf(organization),
)

@JvmName("byOrganizations")
fun VaultRouteFactory.by(organizations: Collection<DOrganization>) = create(
    args = Args(
        appBar = Args.AppBar(
            title = organizations.joinToString { it.name },
            subtitle = if (organizations.size > 1) {
                TextHolder.Res(Res.string.organizations)
            } else {
                TextHolder.Res(Res.string.organization)
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

fun VaultRouteFactory.by(collection: DCollection) = by(
    collections = listOf(collection),
)

@JvmName("byCollections")
fun VaultRouteFactory.by(collections: Collection<DCollection>) = create(
    args = Args(
        appBar = Args.AppBar(
            title = collections.joinToString { it.name },
            subtitle = if (collections.size > 1) {
                TextHolder.Res(Res.string.collections)
            } else {
                TextHolder.Res(Res.string.collection)
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

fun VaultRouteFactory.by(folder: DFolder) = by(
    folders = listOf(folder),
)

@JvmName("byFolders")
fun VaultRouteFactory.by(folders: Collection<DFolder>) = create(
    args = Args(
        appBar = Args.AppBar(
            title = folders.joinToString { it.name },
            subtitle = if (folders.size > 1) {
                TextHolder.Res(Res.string.folders)
            } else {
                TextHolder.Res(Res.string.folder)
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
// Tag
//

fun VaultRouteFactory.by(tag: String) = by(
    tags = listOf(tag),
)

@JvmName("byTags")
fun VaultRouteFactory.by(tags: Collection<String>) = create(
    args = Args(
        appBar = Args.AppBar(
            title = tags.joinToString { it },
            subtitle = if (tags.size > 1) {
                TextHolder.Res(Res.string.tags)
            } else {
                TextHolder.Res(Res.string.tag)
            },
        ),
        filter = DFilter.Or(
            filters = tags
                .map { tag ->
                    DFilter.ById(
                        id = tag,
                        what = DFilter.ById.What.TAG,
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

fun VaultRouteFactory.watchtower(
    title: String,
    subtitle: String?,
    filter: DFilter? = null,
    trash: Boolean? = false,
    sort: Sort? = null,
    searchBy: Args.SearchBy = Args.SearchBy.ALL,
) = create(
    args = Args(
        appBar = Args.AppBar(
            title = title,
            subtitle = subtitle?.let(TextHolder::Value)
                ?: TextHolder.Res(Res.string.watchtower_header_title),
        ),
        filter = filter,
        sort = sort,
        trash = trash,
        searchBy = searchBy,
        preselect = false,
        canAddSecrets = false,
    ),
)
