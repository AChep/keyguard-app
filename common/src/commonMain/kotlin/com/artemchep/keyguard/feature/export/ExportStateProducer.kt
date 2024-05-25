package com.artemchep.keyguard.feature.export

import androidx.compose.runtime.Composable
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.usecase.ExportAccount
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.ah
import com.artemchep.keyguard.feature.home.vault.screen.createFilter
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceExportScreenState(
    args: ExportRoute.Args,
) = with(localDI().direct) {
    produceExportScreenState(
        directDI = this,
        args = args,
        getAccounts = instance(),
        getProfiles = instance(),
        getCiphers = instance(),
        getFolders = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        permissionService = instance(),
        exportAccount = instance(),
    )
}

private data class FilteredBoo<T>(
    val list: List<T>,
    val filterConfig: FilterHolder? = null,
)

@Composable
fun produceExportScreenState(
    directDI: DirectDI,
    args: ExportRoute.Args,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    permissionService: PermissionService,
    exportAccount: ExportAccount,
): Loadable<ExportState> = produceScreenState(
    key = "export",
    initial = Loadable.Loading,
) {
    val passwordSink = mutablePersistedFlow(
        key = "password",
    ) { "" }
    val passwordState = mutableComposeState(passwordSink)

    fun onExport(
        password: String,
        filter: DFilter,
    ) {
        exportAccount(
            filter,
            password,
        )
            .effectTap {
                val msg = ToastMessage(
                    title = translate(Res.string.exportaccount_export_success),
                    type = ToastMessage.Type.SUCCESS,
                )
                message(msg)

                // Close the screen
                navigatePopSelf()
            }
            .launchIn(appScope)
    }

    val writeDownloadsPermissionFlow = permissionService
        .getState(Permission.WRITE_EXTERNAL_STORAGE)

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

    val filterResult = createFilter(directDI)

    val filteredCiphersFlow = ciphersFlow
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

    val filterRawFlow = ah(
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
                custom = false,
            ),
        ),
    )
    val filterFlow = filterRawFlow
        .map { filterState ->
            ExportState.Filter(
                items = filterState.items,
                onClear = filterState.onClear,
                onSave = filterState.onSave,
            )
        }
        .stateIn(screenScope)
    val itemsFlow = filteredCiphersFlow
        .map { state ->
            ExportState.Items(
                revision = state.filterConfig?.id ?: 0,
                list = state.list,
                count = state.list.size,
                onView = onClick {
                    val filter = state.filterConfig?.filter
                    val route = VaultRoute(
                        args = VaultRoute.Args(
                            appBar = VaultRoute.Args.AppBar(
                                title = translate(Res.string.exportaccount_header_title),
                            ),
                            filter = filter,
                            trash = false,
                            preselect = false,
                            canAddSecrets = false,
                        ),
                    )
                    val intent = NavigationIntent.NavigateToRoute(route)
                    navigate(intent)
                },
            )
        }
        .stateIn(screenScope)
    val passwordRawFlow = passwordSink
        .validatedPassword(
            scope = this,
            minLength = 1,
        )
        .stateIn(screenScope)
    val passwordFlow = passwordRawFlow
        .map { passwordValidated ->
            val model = TextFieldModel2.of(
                passwordState,
                passwordValidated,
            )
            ExportState.Password(
                model = model,
            )
        }
        .stateIn(screenScope)
    val contentFlow = combine(
        writeDownloadsPermissionFlow,
        passwordRawFlow,
        filterResult
            .filterFlow,
    ) { writeDownloadsPermission, passwordValidated, filterHolder ->
        val export = kotlin.run {
            val canExport = passwordValidated is Validated.Success &&
                    writeDownloadsPermission is PermissionState.Granted
            if (canExport) {
                val filter = if (args.filter != null) {
                    DFilter.And(
                        filters = listOf(
                            args.filter,
                            filterHolder.filter,
                        ),
                    )
                } else {
                    filterHolder.filter
                }
                ::onExport
                    .partially1(passwordValidated.model)
                    .partially1(filter)
            } else {
                null
            }
        }
        ExportState.Content(
            writePermission = writeDownloadsPermission,
            onExportClick = export,
        )
    }
        .stateIn(screenScope)

    val state = ExportState(
        itemsFlow = itemsFlow,
        filterFlow = filterFlow,
        passwordFlow = passwordFlow,
        contentFlow = contentFlow,
    )
    flowOf(Loadable.Ok(state))
}
