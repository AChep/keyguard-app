package com.artemchep.keyguard.feature.export

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.model.ExportFormat
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.permission.Permission
import com.artemchep.keyguard.common.service.permission.PermissionService
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.util.validatedPassword
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountSI
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.createFilter
import com.artemchep.keyguard.feature.home.vault.screen.createFilterItemsFlow
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.currentKoinScope

@Composable
fun produceExportScreenState(
    args: ExportRoute.Args,
) = with(currentKoinScope()) {
    produceExportScreenState(
        filterContext = get(),
        addCipherFilter = get(),
        confirmationRouteFactory = get(),
        getCipherFilters = get(),
        args = args,
        getAccounts = get(),
        getProfiles = get(),
        getCiphers = get(),
        getFolders = get(),
        getTags = get(),
        getCollections = get(),
        getOrganizations = get(),
        permissionService = get(),
        exportManager = get(),
        vaultRouteFactory = get(),
    )
}

private data class FilteredList<T>(
    val list: List<T>,
    val filterConfig: FilterHolder? = null,
)

@Composable
fun produceExportScreenState(
    filterContext: CipherFilterContext,
    addCipherFilter: AddCipherFilter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    getCipherFilters: GetCipherFilters,
    args: ExportRoute.Args,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getTags: GetTags,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    permissionService: PermissionService,
    exportManager: ExportManager,
    vaultRouteFactory: VaultRouteFactory,
): Loadable<ExportState> = produceScreenState(
    key = "export",
    initial = Loadable.Loading,
) {
    exportScreenStateProducer(
        filterContext = filterContext,
        addCipherFilter = addCipherFilter,
        confirmationRouteFactory = confirmationRouteFactory,
        getCipherFilters = getCipherFilters,
        args = args,
        getAccounts = getAccounts,
        getProfiles = getProfiles,
        getCiphers = getCiphers,
        getFolders = getFolders,
        getTags = getTags,
        getCollections = getCollections,
        getOrganizations = getOrganizations,
        permissionService = permissionService,
        exportManager = exportManager,
        vaultRouteFactory = vaultRouteFactory,
    )
}

// Keep the state flows and their session-scoped callbacks in one lifecycle scope.
@Suppress("CyclomaticComplexMethod", "LongMethod")
suspend fun RememberStateFlowScope.exportScreenStateProducer(
    filterContext: CipherFilterContext,
    addCipherFilter: AddCipherFilter,
    confirmationRouteFactory: ConfirmationRouteFactory,
    getCipherFilters: GetCipherFilters,
    args: ExportRoute.Args,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getTags: GetTags,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    permissionService: PermissionService,
    exportManager: ExportManager,
    vaultRouteFactory: VaultRouteFactory,
    onExportRequest: ((ExportRequest) -> Unit)? = null,
): Flow<Loadable<ExportState>> {
    val attachmentsSink = mutablePersistedFlow(
        key = "attachments",
    ) { false }

    val formatSink = mutablePersistedFlow(
        key = "format",
        serialize = { _, format: ExportFormat -> format.name },
        deserialize = { _, name: String -> ExportFormat.valueOf(name) },
    ) { ExportFormat.Zip }

    // The step is kept, so the wizard stays on
    // it after the user views the items.
    val stepSink = mutablePersistedFlow(
        key = "step",
        serialize = { _, step: ExportStep -> step.name },
        deserialize = { _, name: String ->
            ExportStep.entries.firstOrNull { it.name == name }
                ?: ExportStep.Format
        },
    ) { ExportStep.Format }

    val passwordHandle = textFieldHandle(key = "password")
    val confirmationPasswordHandle = textFieldHandle(key = "password_confirmation")
    val mismatchError = translate(Res.string.pref_item_automatic_backups_wizard_password_mismatch_error)

    fun onExport(
        password: String,
        filter: DFilter,
        attachments: Boolean,
        format: ExportFormat,
    ) {
        val request = ExportRequest(
            filter = filter,
            password = password,
            attachments = attachments,
            format = format,
        )
        if (onExportRequest != null) {
            onExportRequest(request)
            return
        }
        ioEffect {
            exportManager.queue(request)
        }
            .effectTap {
                val msg = ToastMessage(
                    title = translate(Res.string.exportaccount_export_started),
                    type = ToastMessage.Type.INFO,
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
                val predicate = args.filter.prepare(filterContext, ciphers)
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

    val filterResult = createFilter(addCipherFilter, confirmationRouteFactory)

    val filteredCiphersFlow = ciphersFlow
        .map {
            FilteredList(
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
                val predicate = filterConfig.filter.prepare(filterContext, allItems)
                allItems.filter(predicate)
            }
            state.copy(
                list = filteredItems,
                filterConfig = filterConfig,
            )
        }
        .shareIn(screenScope, SharingStarted.WhileSubscribed(), replay = 1)

    val filterRawFlow = createFilterItemsFlow(
        getCipherFilters = getCipherFilters,
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
        tagGetter = ::identity,
        tagFlow = getTags(),
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
                    val filter = DFilter.And(
                        listOfNotNull(
                            args.filter,
                            state.filterConfig?.filter,
                        ),
                    )
                    val route = vaultRouteFactory.create(
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
    val attachmentsFlow = filteredCiphersFlow
        .map { state ->
            val attachments = state.list
                .flatMap { it.attachments }
            val attachmentsTotalSizeByte = attachments.sumOf { it.fileSize() ?: 0L }
                .takeIf { it > 0L }
                ?.let { humanReadableByteCountSI(it) }
            ExportState.Attachments(
                revision = state.filterConfig?.id ?: 0,
                list = attachments,
                size = attachmentsTotalSizeByte,
                count = attachments.size,
                onView = onClick {
                    val filter = DFilter.And(
                        listOfNotNull(
                            DFilter.ByAttachments,
                            state.filterConfig?.filter,
                        ),
                    )
                    val route = vaultRouteFactory.create(
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
                enabled = false,
                onToggle = null,
            )
        }
        .combine(attachmentsSink) { state, enableAttachments ->
            if (state.count == 0) {
                return@combine state
            }
            state.copy(
                enabled = enableAttachments,
                onToggle = attachmentsSink::value::set
                    .partially1(!enableAttachments),
            )
        }
        .stateIn(screenScope)
    val formatFlow = formatSink
        .map { format ->
            val options = ExportFormat.entries
                .map { entry ->
                    FlatItemAction(
                        id = entry.extension,
                        icon = entry.icon(),
                        title = entry.titleRes().wrap(),
                        selected = entry == format,
                        onClick = {
                            formatSink.value = entry
                        },
                    )
                }
            ExportState.Format(
                title = translate(format.titleRes()),
                note = translate(format.noteRes()),
                attachmentsNote = translate(format.attachmentsNoteRes()),
                passwordLabel = translate(format.passwordLabelRes()),
                confirmPasswordLabel = translate(format.confirmPasswordLabelRes()),
                options = options,
            )
        }
        .stateIn(screenScope)
    val passwordPairFlow = passwordHandle.sink
        .map { cell ->
            cell to validatedPassword(
                password = cell.text,
                minLength = 1,
            )
        }
        .stateIn(screenScope)
    val passwordRawFlow = passwordPairFlow
        .map { it.second }
    val passwordFlow = passwordPairFlow
        .map { (cell, passwordValidated) ->
            val model = TextFieldModel.of(
                cell = cell,
                handle = passwordHandle,
                validated = passwordValidated,
            )
            ExportState.Password(
                model = model,
            )
        }
        .stateIn(screenScope)
    val passwordMatchesFlow = combine(
        passwordHandle.sink,
        confirmationPasswordHandle.sink,
    ) { password, confirmation ->
        password.text == confirmation.text
    }
    val confirmationPasswordFlow = combine(
        passwordHandle.sink,
        confirmationPasswordHandle.sink,
    ) { password, cell ->
        val validated = if (password.text == cell.text) {
            Validated.Success(cell.text)
        } else {
            Validated.Failure(cell.text, mismatchError)
        }
        val model = TextFieldModel.of(
            cell = cell,
            handle = confirmationPasswordHandle,
            validated = validated,
        )
        ExportState.Password(
            model = model,
        )
    }
        .stateIn(screenScope)
    val wizardFlow = combine(
        stepSink,
        itemsFlow,
        passwordRawFlow,
        passwordMatchesFlow,
    ) { step, items, passwordValidated, passwordMatches ->
        val data = ExportWizardData(
            step = step,
            itemsCount = items.count,
            passwordValid = passwordValidated is Validated.Success,
            passwordMatches = passwordMatches,
        )
        data.toState(stepSink)
    }
        .stateIn(screenScope)
    // Back goes to the previous step; on the
    // first step it leaves the screen.
    interceptBackPress(
        interceptorFlow = wizardFlow
            .map { it.onBack },
    )
    // A password is ready to use once it is
    // confirmed, on every platform.
    val confirmedPasswordFlow = combine(
        passwordRawFlow,
        passwordMatchesFlow,
    ) { passwordValidated, passwordMatches ->
        if (passwordMatches) {
            passwordValidated
        } else {
            Validated.Failure(passwordValidated.model, mismatchError)
        }
    }
    val contentFlow = combine(
        writeDownloadsPermissionFlow,
        attachmentsSink,
        formatSink,
        confirmedPasswordFlow,
        filterResult
            .filterFlow,
    ) { writeDownloadsPermission, enableAttachments, format, passwordValidated, filterHolder ->
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
                    .partially1(enableAttachments)
                    .partially1(format)
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
        wizardFlow = wizardFlow,
        itemsFlow = itemsFlow,
        attachmentsFlow = attachmentsFlow,
        filterFlow = filterFlow,
        formatFlow = formatFlow,
        passwordFlow = passwordFlow,
        confirmationPasswordFlow = confirmationPasswordFlow,
        contentFlow = contentFlow,
    )
    return flowOf(Loadable.Ok(state))
}

internal fun ExportFormat.icon(): ImageVector = when (this) {
    ExportFormat.Zip -> Icons.Outlined.FolderZip
    ExportFormat.Kdbx -> Icons.Outlined.Key
}

internal fun ExportFormat.titleRes(): StringResource = when (this) {
    ExportFormat.Zip -> Res.string.exportaccount_format_zip
    ExportFormat.Kdbx -> Res.string.exportaccount_format_kdbx
}

internal fun ExportFormat.noteRes(): StringResource = when (this) {
    ExportFormat.Zip -> Res.string.exportaccount_format_note
    ExportFormat.Kdbx -> Res.string.exportaccount_format_kdbx_note
}

internal fun ExportFormat.attachmentsNoteRes(): StringResource = when (this) {
    ExportFormat.Zip -> Res.string.exportaccount_attachments_note
    ExportFormat.Kdbx -> Res.string.exportaccount_kdbx_attachments_note
}

internal fun ExportFormat.passwordLabelRes(): StringResource = when (this) {
    ExportFormat.Zip -> Res.string.exportaccount_password_label
    ExportFormat.Kdbx -> Res.string.exportaccount_kdbx_password_label
}

internal fun ExportFormat.confirmPasswordLabelRes(): StringResource = when (this) {
    ExportFormat.Zip -> Res.string.exportaccount_confirm_password_label
    ExportFormat.Kdbx -> Res.string.exportaccount_kdbx_confirm_password_label
}
