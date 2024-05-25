package com.artemchep.keyguard.feature.sync

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DMeta
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.feature.auth.AccountViewRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultListRoute
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceSyncState(
) = with(localDI().direct) {
    produceSyncState(
        dateFormatter = instance(),
        getMetas = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getCiphers = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getFolders = instance(),
    )
}

private data class CombinedAccountInfo(
    val id: String,
    val account: DAccount?,
    val profile: DProfile?,
    val meta: DMeta?,
    val pendingCiphersCount: Int,
    val pendingFoldersCount: Int,
    val failedCiphersCount: Int,
    val failedFoldersCount: Int,
)

@Composable
fun produceSyncState(
    dateFormatter: DateFormatter,
    getMetas: GetMetas,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getFolders: GetFolders,
): Loadable<SyncState> = produceScreenState(
    key = "sync_state",
    initial = Loadable.Loading,
) {
    val ciphersByAccountIdFlow = getCiphers()
        .map { ciphers ->
            ciphers
                .groupBy { it.accountId }
        }
    val foldersByAccountIdFlow = getFolders()
        .map { folders ->
            folders
                .groupBy { it.accountId }
        }
    val f = combine(
        getAccounts(),
        getProfiles(),
        getMetas(),
        ciphersByAccountIdFlow,
        foldersByAccountIdFlow,
    ) { accounts, profiles, metas, ciphersByAccountId, foldersByAccountId ->
        val accountsById = accounts.groupBy { it.id.id }
        val profilesById = profiles.groupBy { it.accountId }
        val metasById = metas.groupBy { it.accountId.id }

        // Collect all of the account IDs.
        val accountIds = sequence<String> {
            accountsById.keys.forEach { accountId ->
                yield(accountId)
            }
            profilesById.keys.forEach { accountId ->
                yield(accountId)
            }
            metasById.keys.forEach { accountId ->
                yield(accountId)
            }
            ciphersByAccountId.keys.forEach { accountId ->
                yield(accountId)
            }
            foldersByAccountId.keys.forEach { accountId ->
                yield(accountId)
            }
        }.toSet()
        accountIds
            .map { accountId ->
                val account = accountsById[accountId]?.firstOrNull()
                val profile = profilesById[accountId]?.firstOrNull()
                val meta = metasById[accountId]?.firstOrNull()
                // Items
                val failedCiphers = ciphersByAccountId[accountId].orEmpty()
                val failedFolders = foldersByAccountId[accountId].orEmpty()
                CombinedAccountInfo(
                    id = accountId,
                    account = account,
                    profile = profile,
                    meta = meta,
                    pendingCiphersCount = failedCiphers.count { !it.synced },
                    pendingFoldersCount = failedFolders.count { !it.synced },
                    failedCiphersCount = failedCiphers.count { it.hasError },
                    failedFoldersCount = failedFolders.count { it.hasError },
                )
            }
            .map { c ->
                val lastSyncTimestamp = kotlin.run {
                    val timestamp = c.meta?.lastSyncTimestamp
                    if (timestamp != null) {
                        val date = dateFormatter.formatDateTime(timestamp)
                        translate(Res.string.account_last_synced_at, date)
                    } else {
                        null
                    }
                }
                val status = kotlin.run {
                    val failed = c.failedCiphersCount > 0 ||
                            c.failedFoldersCount > 0 ||
                            c.meta?.lastSyncResult is DMeta.LastSyncResult.Failure
                    val pending = c.pendingCiphersCount > 0 ||
                            c.pendingFoldersCount > 0
                    if (failed) {
                        SyncState.Item.Status.Failed
                    } else if (pending) {
                        SyncState.Item.Status.Pending(
                            text = (c.pendingCiphersCount + c.pendingFoldersCount)
                                .toString(),
                        )
                    } else {
                        SyncState.Item.Status.Ok
                    }
                }
                val items = buildContextItems {
                    if (c.failedCiphersCount > 0) {
                        this += FlatItemAction(
                            title = Res.string.items.wrap(),
                            onClick = onClick {
                                val filter = DFilter.And(
                                    listOf(
                                        DFilter.ById(
                                            id = c.id,
                                            what = DFilter.ById.What.ACCOUNT,
                                        ),
                                        DFilter.ByError(
                                            error = true,
                                        ),
                                    ),
                                )
                                val route = VaultListRoute(
                                    args = VaultRoute.Args(
                                        appBar = VaultRoute.Args.AppBar(
                                            title = translate(Res.string.items),
                                            subtitle = translate(Res.string.syncstatus_header_title),
                                        ),
                                        filter = filter,
                                        trash = null,
                                        preselect = false,
                                        canAddSecrets = false,
                                    ),
                                )
                                val intent = NavigationIntent.NavigateToRoute(route)
                                navigate(intent)
                            },
                        )
                    }
                    if (c.failedFoldersCount > 0) {
                        this += FlatItemAction(
                            title = Res.string.folders.wrap(),
                            onClick = {
                                val filter = DFilter.And(
                                    listOf(
                                        DFilter.ById(
                                            id = c.id,
                                            what = DFilter.ById.What.ACCOUNT,
                                        ),
                                        DFilter.ByError(
                                            error = true,
                                        ),
                                    ),
                                )
                                val route = FoldersRoute(
                                    args = FoldersRoute.Args(
                                        filter = filter,
                                    ),
                                )
                                val intent = NavigationIntent.NavigateToRoute(route)
                                navigate(intent)
                            },
                        )
                    }
                }
                SyncState.Item(
                    key = c.id,
                    email = c.profile?.email.orEmpty(),
                    host = c.account?.host.orEmpty(),
                    status = status,
                    items = items,
                    lastSyncTimestamp = lastSyncTimestamp,
                    onClick = {
                        val route = AccountViewRoute(
                            accountId = AccountId(c.id),
                        )
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    },
                )
            }
    }.stateIn(screenScope)


    val state = SyncState(
        itemsFlow = f,
    )
    flowOf(state)
        .map {
            Loadable.Ok(it)
        }
}
