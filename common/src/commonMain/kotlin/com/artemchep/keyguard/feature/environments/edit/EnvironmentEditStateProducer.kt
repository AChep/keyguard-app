package com.artemchep.keyguard.feature.environments.edit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentMcp
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.common.service.environment.environmentReferenceTargets
import com.artemchep.keyguard.common.service.environment.referencedCipherIds
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.feature.add.ownershipPickerFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.environments.EnvironmentEditorState
import com.artemchep.keyguard.feature.environments.EnvironmentResolver
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.setOwnership
import com.artemchep.keyguard.feature.environments.suggestEnvironmentVariableName
import com.artemchep.keyguard.feature.environments.toOwnership2
import com.artemchep.keyguard.feature.environments.dotenv.EnvironmentImportRoute
import com.artemchep.keyguard.feature.environments.ui.EnvironmentsEffect
import com.artemchep.keyguard.feature.environments.ui.EnvironmentsState
import com.artemchep.keyguard.feature.environments.ui.environmentsStateProducer
import com.artemchep.keyguard.feature.environments.ui.verifyEnvironmentAccess
import com.artemchep.keyguard.feature.home.vault.link.field.CipherFieldPickerResult
import com.artemchep.keyguard.feature.home.vault.link.field.CipherFieldPickerRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItemPresentation
import com.artemchep.keyguard.feature.home.vault.screen.toVaultItemPresentation
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.cipher_type_environment
import com.artemchep.keyguard.res.ciphers_action_trash_confirmation_title
import com.artemchep.keyguard.res.ciphers_action_trash_title
import com.artemchep.keyguard.res.environments_create
import com.artemchep.keyguard.res.environments_error_limits_exceeded
import com.artemchep.keyguard.res.environments_error_unsupported_format
import com.artemchep.keyguard.res.environments_import
import com.artemchep.keyguard.res.environments_import_drop_here
import com.artemchep.keyguard.res.environments_read_only
import com.artemchep.keyguard.res.environments_unset
import com.artemchep.keyguard.res.environments_variable_error_duplicate_name
import com.artemchep.keyguard.res.environments_variable_error_invalid_name
import com.artemchep.keyguard.res.environments_variable_name
import com.artemchep.keyguard.res.environments_variable_type_reference
import com.artemchep.keyguard.res.environments_variable_type_value
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.res.error_must_not_be_blank
import com.artemchep.keyguard.res.field_value
import com.artemchep.keyguard.res.password
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.koin.compose.currentKoinScope

@Composable
fun produceEnvironmentEditState(
    environmentId: String?,
): Loadable<EnvironmentEditState> = with(currentKoinScope()) {
    produceEnvironmentEditState(
        environmentId = environmentId,
        environmentRepository = get(),
        environmentAccess = get(),
        environmentMounts = get(),
        environmentMcp = get(),
        confirmationRouteFactory = get(),
        getCiphers = get(),
        getProfiles = get(),
        getOrganizations = get(),
        getCollections = get(),
        getFolders = get(),
        getAppIcons = get(),
        getWebsiteIcons = get(),
    )
}

@Composable
fun produceEnvironmentEditState(
    environmentId: String?,
    environmentRepository: EnvironmentRepository,
    environmentAccess: EnvironmentAccess,
    environmentMounts: EnvironmentMounts,
    environmentMcp: EnvironmentMcp,
    confirmationRouteFactory: ConfirmationRouteFactory,
    getCiphers: GetCiphers,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getFolders: GetFolders,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
): Loadable<EnvironmentEditState> = produceScreenState(
    key = "environment_edit",
    initial = Loadable.Loading,
    args = arrayOf(
        environmentId,
    ),
) {
    environmentEditStateProducer(
        environmentId = environmentId,
        environmentRepository = environmentRepository,
        environmentAccess = environmentAccess,
        environmentMounts = environmentMounts,
        environmentMcp = environmentMcp,
        confirmationRouteFactory = confirmationRouteFactory,
        getCiphers = getCiphers,
        getProfiles = getProfiles,
        getOrganizations = getOrganizations,
        getCollections = getCollections,
        getFolders = getFolders,
        getAppIcons = getAppIcons,
        getWebsiteIcons = getWebsiteIcons,
    )
}

private class EnvironmentEditStrings(
    val title: String,
    val createTitle: String,
    val nameBlank: String,
    val variable: EnvironmentVariableStrings,
    val readOnly: String,
    val unsupported: String,
    val error: String,
    val limitsExceeded: String,
    val trash: String,
    val dropHere: String,
)

@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
suspend fun RememberStateFlowScope.environmentEditStateProducer(
    environmentId: String?,
    environmentRepository: EnvironmentRepository,
    environmentAccess: EnvironmentAccess,
    environmentMounts: EnvironmentMounts,
    environmentMcp: EnvironmentMcp,
    confirmationRouteFactory: ConfirmationRouteFactory,
    getCiphers: GetCiphers,
    getProfiles: GetProfiles,
    getOrganizations: GetOrganizations,
    getCollections: GetCollections,
    getFolders: GetFolders,
    getAppIcons: GetAppIcons,
    getWebsiteIcons: GetWebsiteIcons,
): Flow<Loadable<EnvironmentEditState>> {
    val strings = EnvironmentEditStrings(
        title = translate(Res.string.cipher_type_environment),
        createTitle = translate(Res.string.environments_create),
        nameBlank = translate(Res.string.error_must_not_be_blank),
        variable = EnvironmentVariableStrings(
            nameHint = translate(Res.string.environments_variable_name),
            valueHint = translate(Res.string.field_value),
            unsetHint = translate(Res.string.environments_unset),
            nameInvalid = translate(Res.string.environments_variable_error_invalid_name),
            nameDuplicate = translate(Res.string.environments_variable_error_duplicate_name),
            password = translate(Res.string.password),
        ),
        readOnly = translate(Res.string.environments_read_only),
        unsupported = translate(Res.string.environments_error_unsupported_format),
        error = translate(Res.string.error_failed_unknown),
        limitsExceeded = translate(Res.string.environments_error_limits_exceeded),
        trash = translate(Res.string.ciphers_action_trash_confirmation_title),
        dropHere = translate(Res.string.environments_import_drop_here),
    )

    // Required fields reveal their errors only after
    // the first attempt to save.
    val submitAttemptedFlow = MutableStateFlow(false)
    // Rows are keyed by their index, so every programmatic change of
    // the rows bumps the revision for the text fields to adopt it.
    val revisionFlow = MutableStateFlow(0)
    fun bumpRevision() = revisionFlow.update { it + 1 }

    var closed = false
    fun close() {
        if (closed) return
        closed = true
        navigatePopSelf()
    }

    // The draft lives only in the shared producer: secrets must
    // never reach the persisted screen state.
    val stateFlow = MutableStateFlow(EnvironmentsState())
    environmentsStateProducer(
        repository = environmentRepository,
        access = environmentAccess,
        mounts = environmentMounts,
        mcp = environmentMcp,
        verifyAccess = {
            verifyEnvironmentAccess(navigate = ::navigate)
        },
        onEffect = { effect ->
            when (effect) {
                EnvironmentsEffect.Denied,
                EnvironmentsEffect.Trashed,
                is EnvironmentsEffect.Saved,
                -> close()
            }
        },
    )
        .onEach { stateFlow.value = it }
        .launchIn(screenScope)
    screenScope.coroutineContext[Job]?.invokeOnCompletion {
        stateFlow.value = EnvironmentsState()
    }
    fun latestEditor() = stateFlow.value.editor

    // Waits for the vault to unlock.
    val session = stateFlow.first { it.onOpen != null }
    if (environmentId != null) {
        session.onOpen?.invoke(environmentId)
    } else {
        session.onCreate?.invoke()
    }

    stateFlow
        .map { it.failed }
        .distinctUntilChanged()
        .filter { it }
        .onEach {
            val msg = ToastMessage(
                type = ToastMessage.Type.ERROR,
                title = strings.error,
            )
            message(msg)
        }
        .launchIn(screenScope)
    // A draft only disappears once the vault locks or the
    // environment is trashed, both close the editor.
    var hadDraft = false
    stateFlow
        .map { it.editor != null }
        .distinctUntilChanged()
        .onEach { hasDraft ->
            if (hasDraft) {
                hadDraft = true
            } else if (hadDraft) {
                close()
            }
        }
        .launchIn(screenScope)

    fun onSave() {
        val editor = latestEditor()
            ?: return
        if (editor.draft.canSave) {
            editor.onSave?.invoke()
            return
        }

        submitAttemptedFlow.value = true
        // Some failures, such as an oversized environment,
        // have no inline error to point at.
        if (!editor.draft.hasInlineErrors()) {
            val msg = ToastMessage(
                type = ToastMessage.Type.ERROR,
                title = strings.error,
            )
            message(msg)
        }
    }

    fun onImport(fileUri: String? = null) {
        // The vault might be locked while the dialog
        // is open: the actions of the draft then do nothing.
        val editor = latestEditor()
            ?: return
        val route = registerRouteResultReceiver(
            route = EnvironmentImportRoute(
                fileUri = fileUri,
            ),
        ) { text ->
            editor.onImportTextChange?.invoke(text)
            editor.onPreviewImport?.invoke()
            editor.onApplyImport?.invoke()
            bumpRevision()
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    fun onTrash(name: String, trash: () -> Unit) {
        val intent = createConfirmationDialogIntent(
            confirmationRouteFactory = confirmationRouteFactory,
            icon = icon(Icons.Outlined.Delete),
            title = strings.trash,
            message = name,
        ) {
            trash()
        }
        navigate(intent)
    }

    // Picks a field of another item of the draft's account.
    fun pickReference(
        draft: EnvironmentEditorState,
        onPicked: (EnvironmentValueSource.Reference, String) -> Unit,
    ) {
        val route = registerRouteResultReceiver(
            route = CipherFieldPickerRoute(
                args = CipherFieldPickerRoute.Args(
                    accountId = draft.accountId,
                    excludedCipherId = draft.id,
                ),
            ),
        ) { result ->
            if (result !is CipherFieldPickerResult.Confirm) {
                return@registerRouteResultReceiver
            }
            val reference = EnvironmentValueSource.Reference(
                cipherId = result.cipherId,
                field = result.field,
            )
            onPicked(reference, suggestEnvironmentVariableName(result.field, result.cipherName))
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    // The picked reference belongs to the account of the draft
    // at the time of the click, see the editor's actions.
    fun onPickReference(index: Int) {
        val editor = latestEditor()
            ?: return
        val onReferenceChange = editor.onVariableReferenceChange
            ?: return
        pickReference(editor.draft) { reference, name ->
            onReferenceChange(index, reference, name)
            bumpRevision()
        }
    }

    fun onAddReference() {
        val editor = latestEditor()
            ?: return
        val onAddReference = editor.onAddReference
            ?: return
        pickReference(editor.draft, onAddReference)
    }

    val copier = copier()
    // The items the variables reference: the account of a new
    // draft can change, so the references follow the draft.
    val referenceItemsFlow = combine(
        getCiphers(),
        stateFlow
            .map { state ->
                val draft = state.editor?.draft
                Triple(draft?.accountId, draft?.id, draft?.definition?.referencedCipherIds().orEmpty())
            }
            .distinctUntilChanged(),
        getAppIcons(),
        getWebsiteIcons(),
    ) { ciphers, (accountId, id, referencedIds), appIcons, websiteIcons ->
        environmentReferenceTargets(
            ciphers = ciphers,
            accountId = accountId,
            environmentId = id,
        )
            .filterKeys { it in referencedIds }
            .mapValues { (_, cipher) ->
                cipher.toVaultItemPresentation(
                    appIcons = appIcons,
                    websiteIcons = websiteIcons,
                )
            }
    }
    val savedNamesFlow = environmentRepository.state
        .map { state ->
            state.environments.associate { it.id to it.name }
        }
        .distinctUntilChanged()
    // An existing environment can only move
    // to another folder, like other items.
    val ownershipFlow = ownershipPickerFlow(
        ownershipFlow = stateFlow
            .map { state ->
                // Not shown until the draft loads, but the
                // editor must still report a failed load.
                (state.editor?.draft ?: EnvironmentEditorState()).toOwnership2()
            }
            .distinctUntilChanged(),
        profilesFlow = getProfiles(),
        organizationsFlow = getOrganizations(),
        collectionsFlow = getCollections(),
        foldersFlow = getFolders(),
        readOnly = environmentId != null,
        readOnlyAccountFlow = stateFlow
            .map { it.editor?.draft?.canChangeAccount != true }
            .distinctUntilChanged(),
        onChange = { result ->
            // The vault might have been locked while the
            // dialog was open: the actions then do nothing.
            latestEditor()?.setOwnership(result)
        },
    ) { _, ui -> ui }
    val variableCallbacks = EnvironmentVariableCallbacks(
        onCopy = { index ->
            val value = latestEditor()?.literalValue(index)
            if (value != null) {
                copier.copy(
                    text = value,
                    hidden = true,
                )
            }
        },
        onSourceChange = { index, source ->
            latestEditor()?.onVariableSourceChange?.invoke(index, source)
            bumpRevision()
        },
        onPickReference = ::onPickReference,
        onMove = { from, to ->
            latestEditor()?.onMoveVariable?.invoke(from, to)
            bumpRevision()
        },
        onRemove = { index ->
            latestEditor()?.onRemoveVariable?.invoke(index)
            bumpRevision()
        },
        onNameChange = { index, value ->
            latestEditor()?.onVariableNameChange?.invoke(index, value)
        },
        onValueChange = { index, value ->
            latestEditor()?.onVariableValueChange?.invoke(index, value)
        },
        onReveal = { index ->
            latestEditor()?.onRevealToggle?.invoke(index)
        },
    )
    val addActions = buildContextItems {
        this += FlatItemAction(
            id = "environment.variable.add.value",
            icon = Icons.Outlined.TextFields,
            title = Res.string.environments_variable_type_value.wrap(),
            onClick = {
                latestEditor()?.onAddValue?.invoke()
            },
        )
        this += FlatItemAction(
            id = "environment.variable.add.reference",
            icon = Icons.Outlined.Link,
            title = Res.string.environments_variable_type_reference.wrap(),
            onClick = ::onAddReference,
        )
    }
    val formFlow = combine(
        submitAttemptedFlow,
        revisionFlow,
        referenceItemsFlow,
    ) { submitAttempted, revision, referenceItems ->
        EnvironmentEditForm(
            submitAttempted = submitAttempted,
            revision = revision,
            referenceItems = referenceItems,
        )
    }
    return combine(
        stateFlow,
        ownershipFlow,
        savedNamesFlow,
        formFlow,
    ) { state, ownership, savedNames, form ->
        val submitAttempted = form.submitAttempted
        val busy = state.busy
        val editor = state.editor
        if (editor == null) {
            // Failing to load leaves the editor without a draft.
            if (!busy && state.failed) {
                val state = EnvironmentEditState(
                    title = strings.title,
                    actions = persistentListOf(),
                    content = null,
                    onSave = null,
                    fileDrop = null,
                    busy = false,
                )
                return@combine Loadable.Ok(state)
            }
            return@combine Loadable.Loading
        }

        val draft = editor.draft
        val canEdit = draft.editable && !busy
        val title = if (draft.id == null) {
            strings.createTitle
        } else {
            savedNames[draft.id]
                ?.takeIf { it.isNotBlank() }
                ?: strings.title
        }
        val actions = buildContextItems {
            section {
                if (draft.editable) {
                    this += FlatItemAction(
                        id = "environment.import",
                        icon = Icons.Outlined.FileUpload,
                        title = Res.string.environments_import.wrap(),
                        onClick = if (canEdit) {
                            // lambda
                            {
                                onImport()
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            section {
                // Unsupported environments can not be edited, but
                // can still be trashed by anyone who can write.
                if (draft.id != null && (draft.editable || draft.unsupported)) {
                    this += FlatItemAction(
                        id = "environment.trash",
                        icon = Icons.Outlined.Delete,
                        title = Res.string.ciphers_action_trash_title.wrap(),
                        danger = true,
                        onClick = editor.onTrash?.let { trash ->
                            // lambda
                            {
                                onTrash(title, trash)
                            }
                        },
                    )
                }
            }
        }
        val content = EnvironmentEditState.Content(
            create = draft.id == null,
            note = when {
                draft.unsupported -> SimpleNote(
                    text = strings.unsupported,
                    type = SimpleNote.Type.ERROR,
                )

                !draft.editable -> SimpleNote(
                    text = strings.readOnly,
                    type = SimpleNote.Type.WARNING,
                )

                draft.exceedsLimits -> SimpleNote(
                    text = strings.limitsExceeded,
                    type = SimpleNote.Type.ERROR,
                )

                else -> null
            },
            ownership = ownership.copy(
                onClick = ownership.onClick
                    ?.takeIf { canEdit },
            ),
            name = TextFieldModel(
                text = draft.name,
                error = strings.nameBlank
                    .takeIf { submitAttempted && draft.name.isBlank() },
                onChange = editor.onNameChange,
            ),
            description = TextFieldModel(
                text = draft.description,
                onChange = editor.onDescriptionChange,
            ),
            variables = createVariableStates(
                draft = draft,
                canEdit = canEdit,
                submitAttempted = submitAttempted,
                revision = form.revision,
                referenceItems = form.referenceItems,
                strings = strings.variable,
                callbacks = variableCallbacks,
            ),
            addActions = if (canEdit) addActions else persistentListOf(),
        )
        val state = EnvironmentEditState(
            title = title,
            actions = actions,
            content = content,
            onSave = if (draft.editable && !draft.unsupported) ::onSave else null,
            fileDrop = if (canEdit) {
                EnvironmentEditState.FileDrop(
                    text = strings.dropHere,
                    onFileDrop = { info ->
                        onImport(fileUri = info.uri.toString())
                    },
                )
            } else {
                null
            },
            busy = busy,
        )
        Loadable.Ok(state)
    }
}

private class EnvironmentEditForm(
    val submitAttempted: Boolean,
    val revision: Int,
    val referenceItems: Map<String, VaultItemPresentation>,
)

/**
 * `true` if the editor shows an inline error explaining
 * why the draft can not be saved.
 */
private fun EnvironmentEditorState.hasInlineErrors(): Boolean {
    if (name.isBlank()) {
        return true
    }
    val names = mutableSetOf<String>()
    return definition.variables.any { variable ->
        !EnvironmentResolver.isValidName(variable.name) || !names.add(variable.name)
    }
}
