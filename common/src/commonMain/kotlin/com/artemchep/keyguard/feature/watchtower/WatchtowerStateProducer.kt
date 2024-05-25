package com.artemchep.keyguard.feature.watchtower

import androidx.compose.runtime.Composable
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.formatH2
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.flow.persistingStateIn
import com.artemchep.keyguard.feature.crashlytics.crashlyticsMap
import com.artemchep.keyguard.feature.duplicates.DuplicatesRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.folders.FoldersRoute
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.OurFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.ah
import com.artemchep.keyguard.feature.home.vault.screen.createFilter
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.search.sort.PasswordSort
import com.artemchep.keyguard.feature.justdeleteme.directory.JustDeleteMeServicesRoute
import com.artemchep.keyguard.feature.justgetdata.directory.JustGetMyDataServicesRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.passkeys.directory.PasskeysServicesRoute
import com.artemchep.keyguard.feature.tfa.directory.TwoFaServicesRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.buildContextItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceWatchtowerState(
    args: WatchtowerRoute.Args,
) = with(localDI().direct) {
    produceWatchtowerState(
        directDI = this,
        args = args,
        getCiphers = instance(),
        getAccounts = instance(),
        getProfiles = instance(),
        getFolders = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        getCheckPwnedPasswords = instance(),
        getCheckPwnedServices = instance(),
        getCheckTwoFA = instance(),
        getCheckPasskeys = instance(),
        cipherDuplicatesCheck = instance(),
    )
}

private data class FilteredBoo<T>(
    val list: List<T>,
    val filterConfig: FilterHolder? = null,
)

private class WatchtowerUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceWatchtowerState(
    directDI: DirectDI,
    args: WatchtowerRoute.Args,
    getCiphers: GetCiphers,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    getCheckPwnedPasswords: GetCheckPwnedPasswords,
    getCheckPwnedServices: GetCheckPwnedServices,
    getCheckTwoFA: GetCheckTwoFA,
    getCheckPasskeys: GetCheckPasskeys,
    cipherDuplicatesCheck: CipherDuplicatesCheck,
): WatchtowerState = produceScreenState(
    initial = WatchtowerState(),
    key = "watchtower",
    args = arrayOf(
        getCiphers,
    ),
) {
    val storage = kotlin.run {
        val disk = loadDiskHandle(
            key = "cache",
        )
        PersistedStorage.InDisk(disk)
    }

    val ciphersRawFlow = filterHiddenProfiles(
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        filter = args.filter,
    )
        .map { ciphers ->
            if (args.filter != null) {
                val predicate = args.filter.prepare(directDI, ciphers)
                ciphers
                    .filter { predicate(it) }
            } else {
                ciphers
            }
        }
    val ciphersFlow = ciphersRawFlow
        .map { secrets ->
            secrets
                .filter { secret -> !secret.deleted }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)
    val foldersFlow = getFolders()
        .map { folders ->
            folders
                .filter { folder -> !folder.deleted }
                .let { list ->
                    if (args.filter != null) {
                        val predicate = args.filter.prepareFolders(directDI, list)
                        list
                            .filter { predicate(it) }
                    } else {
                        list
                    }
                }
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val filterResult = createFilter(directDI)

    fun filteredCiphers(ciphersFlow: Flow<List<DSecret>>) = ciphersFlow
        .map {
            FilteredBoo(
                list = it,
            )
        }
        .combine(
            flow = filterResult.filterFlow,
        ) { state, filterConfig ->
            // Fast path: if the there are no filters, then
            // just return original list of items.
            if (filterConfig.state.isEmpty()) {
                return@combine state.copy(
                    filterConfig = filterConfig,
                )
            }

            val filteredItems = kotlin.run {
                val allItems = state.list
                val predicate = filterConfig.filter.prepare(directDI, allItems)
                allItems.filter(predicate)
            }
            state.copy(
                list = filteredItems,
                filterConfig = filterConfig,
            )
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    fun filteredFolders(foldersFlow: Flow<List<DFolder>>) = foldersFlow
        .map {
            FilteredBoo(
                list = it,
            )
        }
        .combine(
            flow = filterResult.filterFlow,
        ) { state, filterConfig ->
            // Fast path: if the there are no filters, then
            // just return original list of items.
            if (filterConfig.state.isEmpty()) {
                return@combine state.copy(
                    filterConfig = filterConfig,
                )
            }

            val filteredItems = kotlin.run {
                val allItems = state.list
                val predicate = filterConfig.filter.prepareFolders(directDI, allItems)
                allItems.filter(predicate)
            }
            state.copy(
                list = filteredItems,
                filterConfig = filterConfig,
            )
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val filteredCiphersFlow = filteredCiphers(
        ciphersFlow = ciphersFlow,
    )
    val filteredTrashedCiphersFlow = filteredCiphers(
        ciphersFlow = ciphersRawFlow
            .map { secrets ->
                secrets
                    .filter { secret -> secret.deleted }
            },
    )

    val filteredFoldersFlow = filteredFolders(
        foldersFlow = foldersFlow,
    )

    val filterFlow = ah(
        directDI = directDI,
        outputGetter = ::identity,
        outputFlow = filteredCiphersFlow
            .map { state ->
                state.list
            },
        accountGetter = ::identity,
        accountFlow = getAccounts(),
        profileFlow = getProfiles(),
        cipherGetter = ::identity,
        cipherFlow = ciphersFlow,
        folderGetter = ::identity,
        folderFlow = getFolders(),
        collectionGetter = ::identity,
        collectionFlow = getCollections(),
        organizationGetter = ::identity,
        organizationFlow = getOrganizations(),
        input = filterResult,
        params = FilterParams(
            section = FilterParams.Section(
                misc = false,
                type = false,
                custom = false,
            ),
        ),
    )
        .stateIn(this, SharingStarted.WhileSubscribed(), OurFilterResult())

    fun <S, T> boose(
        source: Flow<FilteredBoo<S>>,
        key: String,
        enabledFlow: Flow<Boolean>,
        counterFlow: (FilteredBoo<S>) -> Flow<Int>,
        onCreate: (FilteredBoo<S>?, Int) -> T,
    ): StateFlow<Loadable<T?>> {
        val cachedCounterSink = mutablePersistedFlow(key, storage) {
            -1
        }
        // Start with displaying cached counter, and then load
        // the actual value.
        val initialValue = kotlin.run {
            val cachedCounter = cachedCounterSink.value
            if (cachedCounter >= 0) {
                val state = onCreate(null, cachedCounter)
                Loadable.Ok(state)
            } else {
                Loadable.Loading
            }
        }
        return enabledFlow
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                if (!enabled) {
                    // Reset the cache when the feature is
                    // not enabled.
                    cachedCounterSink.value = -1

                    val result = Loadable.Ok(null)
                    return@flatMapLatest flowOf(result)
                }

                source
                    .flatMapLatest { holder ->
                        counterFlow(holder)
                            .onEach {
                                val revision = holder.filterConfig?.id
                                if (revision == null || revision == 0) {
                                    cachedCounterSink.value = it
                                }
                            }
                            .map { count ->
                                val state = onCreate(holder, count)
                                Loadable.Ok(state)
                            }
                    }
            }
            .crashlyticsMap(
                transform = { e ->
                    val msg = "Failed to process '$key' metric!"
                    WatchtowerUiException(msg, e)
                },
                orElse = { Loadable.Ok(null) },
            )
            .persistingStateIn(
                scope = screenScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = initialValue,
            )
    }

    fun <S, T> booseBlock(
        source: Flow<FilteredBoo<S>>,
        key: String,
        enabledFlow: Flow<Boolean> = flowOf(true),
        counterBlock: suspend (FilteredBoo<S>) -> Int,
        onCreate: (FilteredBoo<S>?, Int) -> T,
    ) = boose(
        source = source,
        key = key,
        enabledFlow = enabledFlow,
        counterFlow = { holder ->
            flow {
                val count = counterBlock(holder)
                emit(count)
            }
        },
        onCreate = onCreate,
    )

    //
    // Password strength
    //

    suspend fun onClickPasswordStrength(
        filter: DFilter,
        score: PasswordStrength.Score,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(score.formatH2()),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByPasswordStrength(score),
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val passwordStrengthFlow = filteredCiphersFlow
        .map { holder ->
            val items = holder
                .list
                .mapNotNull { secret -> secret.login?.passwordStrength?.score }
                .groupBy { it }
                .mapValues {
                    it.value.size
                }
                .run {
                    val m = toMutableMap()
                    m.getOrPut(PasswordStrength.Score.VeryStrong) { 0 }
                    m.getOrPut(PasswordStrength.Score.Strong) { 0 }
                    m.getOrPut(PasswordStrength.Score.Good) { 0 }
                    m.getOrPut(PasswordStrength.Score.Fair) { 0 }
                    m.getOrPut(PasswordStrength.Score.Weak) { 0 }
                    m
                }
                .map {
                    val onClick = if (it.value > 0) {
                        val filter = holder.filterConfig?.filter
                            ?: DFilter.All
                        onClick {
                            onClickPasswordStrength(
                                filter = filter,
                                score = it.key,
                            )
                        }
                    } else {
                        null
                    }
                    WatchtowerState.Content.PasswordStrength.Item(
                        score = it.key,
                        count = it.value,
                        onClick = onClick,
                    )
                }
                .sortedByDescending { it.score }
            val state = WatchtowerState.Content.PasswordStrength(
                revision = holder.filterConfig?.id ?: 0,
                items = items,
            )
            Loadable.Ok(state)
        }
        .crashlyticsMap(
            transform = { e ->
                val msg = "Failed to process password strength list!"
                WatchtowerUiException(msg, e)
            },
            orElse = { Loadable.Ok(null) },
        )
        .persistingStateIn(
            scope = screenScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = Loadable.Loading,
        )

    //
    // Security
    //

    suspend fun onClickPasswordPwned(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_pwned_passwords_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByPasswordPwned,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val passwordPwnedFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByPasswordPwned.key,
        enabledFlow = getCheckPwnedPasswords(),
        counterBlock = { holder ->
            val count = DFilter.ByPasswordPwned.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickPasswordPwned(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.PwnedPasswords(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickUnsecureWebsites(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_unsecure_websites_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByUnsecureWebsites,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val unsecureWebsitesFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByUnsecureWebsites.key,
        counterBlock = { holder ->
            val count = DFilter.ByUnsecureWebsites.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickUnsecureWebsites(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.UnsecureWebsites(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickDuplicateWebsites(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_duplicate_websites_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByDuplicateWebsites,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val duplicateWebsitesFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByDuplicateWebsites.key,
        counterBlock = { holder ->
            val count = DFilter.ByDuplicateWebsites.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickDuplicateWebsites(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.DuplicateWebsites(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickTfaWebsites(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_inactive_2fa_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByTfaWebsites,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val inactiveTwoFactorAuthFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByTfaWebsites.key,
        enabledFlow = getCheckTwoFA(),
        counterBlock = { holder ->
            val count = DFilter.ByTfaWebsites.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickTfaWebsites(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.InactiveTwoFactorAuth(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickPasskeyWebsites(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_inactive_passkey_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByPasskeyWebsites,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val inactivePasskeyFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByPasskeyWebsites.key,
        enabledFlow = getCheckPasskeys(),
        counterBlock = { holder ->
            val count = DFilter.ByPasskeyWebsites.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickPasskeyWebsites(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.InactivePasskey(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickPasswordReused(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_reused_passwords_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByPasswordDuplicates,
                        filter,
                        args.filter,
                    ),
                ),
                sort = PasswordSort,
            ),
        )
        navigate(intent)
    }

    val passwordReusedFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByPasswordDuplicates.key,
        counterBlock = { holder ->
            val count = DFilter.ByPasswordDuplicates.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickPasswordReused(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.ReusedPasswords(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickWebsitePwned(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_vulnerable_accounts_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByWebsitePwned,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val websitePwnedFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByWebsitePwned.key,
        enabledFlow = getCheckPwnedServices(),
        counterBlock = { holder ->
            val count = DFilter.ByWebsitePwned.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickWebsitePwned(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.PwnedWebsites(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )
    val accountCompromisedFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByWebsitePwned.key,
        counterBlock = { holder ->
            val count = DFilter.ByWebsitePwned.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickWebsitePwned(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.CompromisedAccounts(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    //
    // Maintenance
    //

    fun onClickDuplicates(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            DuplicatesRoute(
                args = DuplicatesRoute.Args(
                    filter = filter,
                ),
            ),
        )
        navigate(intent)
    }

    val duplicateItemsFlow = booseBlock(
        source = filteredCiphersFlow,
        key = "by_duplicate",
        counterBlock = { holder ->
            val groups =
                cipherDuplicatesCheck(holder.list, CipherDuplicatesCheck.Sensitivity.NORMAL)
            val count = groups.size
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                ::onClickDuplicates
                    .partially1(filter)
            } else {
                null
            }
            WatchtowerState.Content.DuplicateItems(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickIncomplete(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_incomplete_items_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByIncomplete,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val incompleteItemsFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByIncomplete.key,
        counterBlock = { holder ->
            val count = DFilter.ByIncomplete.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickIncomplete(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.IncompleteItems(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickExpiring(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_expiring_items_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        DFilter.ByExpiring,
                        filter,
                        args.filter,
                    ),
                ),
            ),
        )
        navigate(intent)
    }

    val expiringItemsFlow = booseBlock(
        source = filteredCiphersFlow,
        key = DFilter.ByExpiring.key,
        counterBlock = { holder ->
            val count = DFilter.ByExpiring.count(directDI, holder.list)
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickExpiring(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.ExpiringItems(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    suspend fun onClickTrash(
        filter: DFilter,
    ) {
        val intent = NavigationIntent.NavigateToRoute(
            VaultRoute.watchtower(
                title = translate(Res.string.watchtower_item_trashed_items_title),
                subtitle = translate(Res.string.watchtower_header_title),
                filter = DFilter.And(
                    filters = listOfNotNull(
                        filter,
                        args.filter,
                    ),
                ),
                trash = true,
            ),
        )
        navigate(intent)
    }

    val trashedItemsFlow = booseBlock(
        source = filteredTrashedCiphersFlow,
        key = "by_trash",
        counterBlock = { holder ->
            val count = holder.list.size
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                onClick {
                    onClickTrash(
                        filter = filter,
                    )
                }
            } else {
                null
            }
            WatchtowerState.Content.TrashedItems(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    fun onClickEmpty(
        filter: DFilter,
    ) {
        val route = FoldersRoute(
            args = FoldersRoute.Args(
                filter = DFilter.And(
                    filters = listOfNotNull(
                        filter,
                        args.filter,
                    ),
                ),
                empty = true,
            ),
        )
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    val emptyItemsFlow = booseBlock(
        source = filteredFoldersFlow
            .combine(
                ciphersFlow
                    .map { list ->
                        list
                            .asSequence()
                            .map { it.folderId }
                            .toSet()
                    }
                    .distinctUntilChanged(),
            ) { holder, usedFolderIds ->
                val filteredFolders = holder
                    .list
                    .filter { folder ->
                        val isEmpty = folder.id !in usedFolderIds
                        isEmpty
                    }
                holder.copy(list = filteredFolders)
            },
        key = "by_empty_folder",
        counterBlock = { holder ->
            val count = holder.list.size
            count
        },
        onCreate = { holder, count ->
            val onClick = if (count > 0) {
                val filter = holder?.filterConfig?.filter
                    ?: DFilter.All
                ::onClickEmpty
                    .partially1(filter)
            } else {
                null
            }
            WatchtowerState.Content.EmptyItems(
                revision = holder?.filterConfig?.id ?: 0,
                count = count,
                onClick = onClick,
            )
        },
    )

    val content = WatchtowerState.Content(
        unsecureWebsites = unsecureWebsitesFlow,
        duplicateWebsites = duplicateWebsitesFlow,
        inactiveTwoFactorAuth = inactiveTwoFactorAuthFlow,
        inactivePasskey = inactivePasskeyFlow,
        accountCompromised = accountCompromisedFlow,
        pwned = passwordPwnedFlow,
        pwnedWebsites = websitePwnedFlow,
        reused = passwordReusedFlow,
        incompleteItems = incompleteItemsFlow,
        expiringItems = expiringItemsFlow,
        duplicateItems = duplicateItemsFlow,
        trashedItems = trashedItemsFlow,
        emptyItems = emptyItemsFlow,
        strength = passwordStrengthFlow,
    )
    val actionsFlow = combine(
        getCheckTwoFA(),
        getCheckPasskeys(),
    ) { checkTwoFa, checkPasskeys ->
        val actions = buildContextItems {
            section {
                if (checkTwoFa) {
                    this += TwoFaServicesRoute.actionOrNull(
                        translator = this@produceScreenState,
                        navigate = ::navigate,
                    )
                }
                if (checkPasskeys) {
                    this += PasskeysServicesRoute.actionOrNull(
                        translator = this@produceScreenState,
                        navigate = ::navigate,
                    )
                }
                this += JustGetMyDataServicesRoute.actionOrNull(
                    translator = this@produceScreenState,
                    navigate = ::navigate,
                )
                this += JustDeleteMeServicesRoute.actionOrNull(
                    translator = this@produceScreenState,
                    navigate = ::navigate,
                )
            }
        }
        actions
    }
    filterFlow
        .combine(actionsFlow) { filterState, actions ->
            WatchtowerState(
                revision = filterState.rev,
                content = Loadable.Ok(content),
                filter = WatchtowerState.Filter(
                    items = filterState.items,
                    onClear = filterState.onClear,
                    onSave = filterState.onSave,
                ),
                actions = actions,
            )
        }
}
