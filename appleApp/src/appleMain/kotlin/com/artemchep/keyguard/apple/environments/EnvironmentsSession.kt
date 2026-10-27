package com.artemchep.keyguard.apple.environments

import com.artemchep.keyguard.apple.add.AddOwnershipSnapshot
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.agent.snapshotCallerName
import com.artemchep.keyguard.apple.agent.snapshotCallerPath
import com.artemchep.keyguard.common.service.agent.MAX_AGENT_CALLER_NAME_LENGTH
import com.artemchep.keyguard.common.service.agent.sanitizedAgentDisplayValue
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.environment.EnvironmentAccount
import com.artemchep.keyguard.common.service.environment.EnvironmentCallerIdentity
import com.artemchep.keyguard.common.service.environment.EnvironmentCapability
import com.artemchep.keyguard.common.service.environment.EnvironmentMount
import com.artemchep.keyguard.common.service.environment.EnvironmentMountStatus
import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentMcp
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.environment.environmentReferenceTargets
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.feature.add.ownershipConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.environments.EnvironmentEditorState
import com.artemchep.keyguard.feature.environments.EnvironmentFieldReference
import com.artemchep.keyguard.feature.environments.EnvironmentResolver
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.feature.environments.setOwnership
import com.artemchep.keyguard.feature.environments.suggestEnvironmentVariableName
import com.artemchep.keyguard.feature.environments.toOwnership2
import com.artemchep.keyguard.feature.home.vault.link.field.CipherFieldPickerResult
import com.artemchep.keyguard.feature.home.vault.link.field.CipherFieldPickerRoute
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.environments.ui.EnvironmentEditor
import com.artemchep.keyguard.feature.environments.ui.EnvironmentsState
import com.artemchep.keyguard.feature.environments.ui.environmentsStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.save_to
import org.koin.core.scope.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class EnvironmentItemSnapshot(val id: String, val name: String)
enum class EnvironmentVariableKind {
    VALUE,
    REFERENCE,
    UNSET,
    UNSUPPORTED,
}

/** A referenced field, never its value. A `null` [customFieldName] is the password. */
class EnvironmentReferenceSnapshot(
    /** The referenced item, `null` if it is not available. */
    val itemName: String?,
    val customFieldName: String?,
)

class EnvironmentVariableSnapshot(
    val name: String,
    /** The name breaks the naming rules; an empty name is not reported. */
    val nameInvalid: Boolean,
    /** Another variable has the same name. */
    val nameDuplicate: Boolean,
    val value: String?,
    val revealed: Boolean,
    val kind: EnvironmentVariableKind,
    val reference: EnvironmentReferenceSnapshot?,
)
enum class EnvironmentMountSnapshotStatus {
    PENDING,
    READY,
    ERROR,
}
class EnvironmentMountSnapshot(val id: String, val path: String, val status: EnvironmentMountSnapshotStatus)
@Suppress("LongParameterList") // Flat native snapshot keeps Swift bridge values explicit.
class EnvironmentEditorSnapshot(
    val id: String?,
    /** Where the environment is stored, picked with [EnvironmentsSession.pickOwnership]. */
    val ownership: AddOwnershipSnapshot,
    val name: String,
    val description: String,
    val editable: Boolean,
    val unsupported: Boolean,
    val canSave: Boolean,
    val exceedsLimits: Boolean,
    val variables: List<EnvironmentVariableSnapshot>,
    val importText: String,
    val importNames: List<String>?,
    val importError: Boolean,
    val mounts: List<EnvironmentMountSnapshot>,
)
@Suppress("LongParameterList") // Flat native snapshot keeps Swift bridge values explicit.
class EnvironmentsSnapshot(
    val unlocked: Boolean,
    /** Whether there is an account to create an environment in. */
    val canCreate: Boolean,
    val environments: List<EnvironmentItemSnapshot>,
    val editor: EnvironmentEditorSnapshot?,
    val busy: Boolean,
    val failed: Boolean,
    val supportsMounts: Boolean,
    val mcpEnabled: Boolean,
    val mcpConfiguration: String,
)

enum class EnvironmentCapabilityKind {
    AUTHENTICATE,
    LIST_ENVIRONMENTS,
    CREATE_ENVIRONMENT,
    RENAME_ENVIRONMENT,
    LIST_VARIABLES,
    APPEND_VARIABLES,
    CREATE_LOCAL_ENV_FILE,
    LIST_LOCAL_ENV_FILES,
    READ_LOCAL_ENV_FILE,
}

@Suppress("LongParameterList") // Flat native snapshot keeps Swift bridge values explicit.
class EnvironmentApprovalSnapshot(
    val id: String,
    /**
     * The verified name of the caller, empty if unknown. `null` for
     * a read of a local `.env` file, which does not reveal the reader.
     */
    val callerName: String?,
    /** The executable of the caller, empty if unknown. */
    val callerPath: String,
    /** The name the MCP client reports for itself, escaped for display. */
    val clientName: String?,
    val environmentName: String?,
    val capability: EnvironmentCapabilityKind,
    val path: String?,
    val expiresAtEpochMs: Long,
    val accountName: String?,
)

/** One native presentation. Drafts and pending work are cleared on close or vault lock. */
@Suppress("TooManyFunctions") // Mirrors the shared editor action contract for Swift.
class EnvironmentsSession internal constructor(private val ctx: CoreContext) {
    internal var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null
    private val repository = ctx.koin.get<EnvironmentRepository>()
    private val mounts = ctx.koin.get<EnvironmentMounts>()
    private val mcp = ctx.koin.get<EnvironmentMcp>()
    private val clipboard = ctx.koin.get<ClipboardService>()
    private val scope = CoroutineScope(ctx.scope.coroutineContext + SupervisorJob(ctx.scope.coroutineContext[Job]))
    private val state = MutableStateFlow(EnvironmentsState())
    private var closed = false

    init {
        environmentsStateProducer(repository, ctx.koin.get<EnvironmentAccess>(), mounts, mcp, verifyAccess = {
            navigationInterceptorProvider?.let { ctx.verifyEnvironmentAccess(it) } ?: false
        })
            .onEach { state.value = it }
            .launchIn(scope)
    }

    private val editor: EnvironmentEditor?
        get() = state.value.editor?.takeUnless { closed }

    /** The names of the items the variables of the draft may reference, by provider id. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val referenceNamesFlow = combine(
        repository.state
            .map { it.sessionVersion }
            .distinctUntilChanged()
            .flatMapLatest { sessionVersion ->
                if (sessionVersion == null) return@flatMapLatest flowOf(emptyList())
                flow {
                    val state = ctx.currentState() as? VaultState.Main
                    if (state == null) emit(emptyList()) else emitAll(state.sessionKoin.get<GetCiphers>()())
                }
            },
        state
            .map { state -> state.editor?.draft?.accountId to state.editor?.draft?.id }
            .distinctUntilChanged(),
    ) { ciphers, (accountId, environmentId) ->
        environmentReferenceTargets(ciphers, accountId, environmentId)
            .mapValues { (_, cipher) -> cipher.name }
    }

    fun observe(onChange: (EnvironmentsSnapshot) -> Unit): KeyguardCancellable {
        check(!closed)
        val job = scope.launch {
            combine(
                repository.state,
                mounts.state,
                mcp.state,
                this@EnvironmentsSession.state,
                referenceNamesFlow,
            ) { state, mountsState, mcpState, presentation, referenceNames ->
                EnvironmentsSnapshot(
                    // The editor is ready for the session.
                    unlocked = presentation.onCreate != null,
                    canCreate = state.accounts.isNotEmpty(),
                    environments = state.environments.map { EnvironmentItemSnapshot(it.id, it.name) },
                    editor = presentation.editor?.let { editor ->
                        val draft = editor.draft
                        EnvironmentEditorSnapshot(
                            id = draft.id,
                            ownership = draft.ownershipSnapshot(
                                accounts = state.accounts,
                                canPick = editor.onOwnershipChange != null,
                            ),
                            name = draft.name,
                            description = draft.description,
                            editable = draft.editable,
                            unsupported = draft.unsupported,
                            canSave = draft.canSave,
                            exceedsLimits = draft.exceedsLimits,
                            variables = draft.variableSnapshots(referenceNames),
                            importText = draft.importText,
                            importNames = draft.importPreview?.variables?.map { it.name },
                            importError = draft.error,
                            mounts = mountsState.mounts
                                .filter { it.environmentId == draft.id && it.enabled }
                                .map { it.snapshot(mountsState.statuses[it.id]) },
                        )
                    },
                    busy = presentation.busy,
                    failed = presentation.failed,
                    supportsMounts = mountsState.supported,
                    mcpEnabled = mcpState.enabled,
                    mcpConfiguration = mcpState.configuration,
                )
            }.collect(onChange)
        }
        return KeyguardCancellable(job)
    }

    /** The actions of the open draft, for a result that arrives later. */
    fun currentDraft(): EnvironmentDraftHandle? = editor?.let(::EnvironmentDraftHandle)

    fun open(id: String) {
        if (!closed) state.value.onOpen?.invoke(id)
    }

    fun create() {
        if (!closed) state.value.onCreate?.invoke()
    }

    fun clear() {
        if (!closed) state.value.onClose?.invoke()
    }

    fun setName(value: String) {
        editor?.onNameChange?.invoke(value)
    }

    fun setDescription(value: String) {
        editor?.onDescriptionChange?.invoke(value)
    }

    fun addValueVariable() {
        editor?.onAddValue?.invoke()
    }

    fun addReferenceVariable() {
        val editor = editor
            ?: return
        val onAddReference = editor.onAddReference
            ?: return
        pickReference(editor.draft, onAddReference)
    }

    /** Changes the type of a variable; a reference is picked first. */
    fun setVariableType(index: Int, kind: EnvironmentVariableKind) {
        val editor = editor
            ?: return
        val source = editor.draft.definition.variables.getOrNull(index)?.source
            ?: return
        when (kind) {
            EnvironmentVariableKind.VALUE -> if (source !is EnvironmentValueSource.Literal) {
                editor.onVariableSourceChange?.invoke(index, EnvironmentValueSource.Literal(""))
            }
            EnvironmentVariableKind.REFERENCE -> pickVariableReference(index)
            EnvironmentVariableKind.UNSET -> editor.onVariableSourceChange?.invoke(index, EnvironmentValueSource.Unset)
            // Unsupported sources are never written by the editor.
            EnvironmentVariableKind.UNSUPPORTED -> Unit
        }
    }

    fun pickVariableReference(index: Int) {
        val editor = editor
            ?: return
        val onReferenceChange = editor.onVariableReferenceChange
            ?: return
        pickReference(editor.draft) { reference, name ->
            onReferenceChange(index, reference, name)
        }
    }

    /**
     * Opens the "Save to" picker of the account, organization, collections and
     * folder. An existing environment can only move to another folder.
     */
    fun pickOwnership() {
        val editor = editor
            ?.takeIf { it.onOwnershipChange != null }
            ?: return
        val draft = editor.draft
        scope.launch {
            val title = textResource(Res.string.save_to, ctx.koin.get<LeContext>())
            navigateToRoute {
                registerRouteResultReceiver(
                    ownershipConfirmationRoute(
                        title = title,
                        readOnly = draft.id != null,
                        ownership = draft.toOwnership2(),
                        readOnlyAccount = !draft.canChangeAccount,
                    ),
                ) { result ->
                    if (result !is OrganizationConfirmationResult.Confirm || closed) {
                        return@registerRouteResultReceiver
                    }
                    // Does nothing once the draft is gone.
                    editor.setOwnership(result)
                }
            }
        }
    }

    /**
     * Opens the drill-down picker of a field of another item of the draft's
     * account. The [onPicked] action of the draft checks that it is still current.
     */
    private fun pickReference(
        draft: EnvironmentEditorState,
        onPicked: (EnvironmentValueSource.Reference, String) -> Unit,
    ) {
        scope.launch {
            navigateToRoute {
                registerRouteResultReceiver(
                    CipherFieldPickerRoute(
                        CipherFieldPickerRoute.Args(
                            accountId = draft.accountId,
                            excludedCipherId = draft.id,
                        ),
                    ),
                ) { result ->
                    if (result !is CipherFieldPickerResult.Confirm || closed) {
                        return@registerRouteResultReceiver
                    }
                    val reference = EnvironmentValueSource.Reference(result.cipherId, result.field)
                    onPicked(reference, suggestEnvironmentVariableName(result.field, result.cipherName))
                }
            }
        }
    }

    /** Opens the route that [createRoute] registers, while the vault is unlocked. */
    private suspend fun navigateToRoute(createRoute: () -> Route) {
        val state = ctx.currentState() as? VaultState.Main
            ?: return
        ctx.publishOnMain {
            navigationInterceptorProvider?.invoke(state.sessionKoin)
                ?.invoke(NavigationIntent.NavigateToRoute(createRoute()))
        }
    }

    fun removeVariable(index: Int) {
        editor?.onRemoveVariable?.invoke(index)
    }

    fun setVariableName(index: Int, value: String) {
        editor?.onVariableNameChange?.invoke(index, value)
    }

    fun setVariableValue(index: Int, value: String) {
        editor?.onVariableValueChange?.invoke(index, value)
    }

    fun reveal(index: Int) {
        editor?.onRevealToggle?.invoke(index)
    }

    fun copyVariable(index: Int) {
        val value = editor?.literalValue?.invoke(index)
            ?: return
        clipboard.setPrimaryClip(value, concealed = true)
    }

    fun setImportText(value: String) {
        editor?.onImportTextChange?.invoke(value)
    }

    fun previewImport() {
        editor?.onPreviewImport?.invoke()
    }

    fun applyImport() {
        editor?.onApplyImport?.invoke()
    }

    fun save() {
        editor?.onSave?.invoke()
    }

    fun trash() {
        editor?.onTrash?.invoke()
    }

    fun disableMount(id: String) {
        if (!closed) state.value.onDisableMount?.invoke(id)
    }

    fun setMcpEnabled(enabled: Boolean) {
        if (!closed) state.value.onMcpEnabledChange?.invoke(enabled)
    }

    fun copyMcpConfiguration() = clipboard.setPrimaryClip(mcp.state.value.configuration, concealed = false)
    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        // Secrets must not outlive the presentation.
        state.value = EnvironmentsState()
    }
}

/**
 * The actions of one draft, for a result that arrives later, such as
 * a file panel. They do nothing once the draft is gone.
 */
class EnvironmentDraftHandle internal constructor(
    private val editor: EnvironmentEditor,
) {
    fun setImportText(value: String) {
        editor.onImportTextChange?.invoke(value)
    }

    fun previewImport() {
        editor.onPreviewImport?.invoke()
    }

    fun mount(path: String, bookmark: String?) {
        editor.onMount?.invoke(path, bookmark)
    }
}

internal fun CoreContext.observeEnvironmentApprovals(
    onChange: (List<EnvironmentApprovalSnapshot>) -> Unit,
): KeyguardCancellable = launchObserver {
    combine(
        koin.get<EnvironmentAccess>().approvals,
        koin.get<EnvironmentRepository>().state,
    ) { requests, state ->
        requests.map {
            val environment = state.environments.firstOrNull { environment -> environment.id == it.environmentId }
            val accountId = environment?.accountId ?: it.accountId
            val caller = it.caller?.let(::EnvironmentCallerIdentity)
            EnvironmentApprovalSnapshot(
                id = it.id,
                callerName = caller?.snapshotCallerName(),
                callerPath = caller.snapshotCallerPath(),
                // The client reports its own name, so it is shown apart
                // from the verified caller.
                clientName = it.caller?.clientName.sanitizedAgentDisplayValue(MAX_AGENT_CALLER_NAME_LENGTH),
                environmentName = it.environmentName ?: environment?.name,
                capability = it.capability.kind(),
                path = it.path,
                expiresAtEpochMs = it.expiresAt.toEpochMilliseconds(),
                accountName = state.accounts.firstOrNull { account -> account.id == accountId }?.name,
            )
        }
    }.collect { snapshots -> publishOnMain { onChange(snapshots) } }
}

private fun EnvironmentEditorState.ownershipSnapshot(
    accounts: List<EnvironmentAccount>,
    canPick: Boolean,
) = AddOwnershipSnapshot(
    title = accounts
        .firstOrNull { it.id == accountId }
        ?.name
        .orEmpty(),
    text = null,
    canPick = canPick,
)

private fun EnvironmentEditorState.variableSnapshots(
    referenceNames: Map<String, String>,
): List<EnvironmentVariableSnapshot> {
    val nameCounts = definition.variables.groupingBy { it.name }.eachCount()
    return definition.variables.mapIndexed { index, variable ->
        val source = variable.source
        EnvironmentVariableSnapshot(
            name = variable.name,
            nameInvalid = variable.name.isNotEmpty() &&
                !EnvironmentResolver.isValidName(variable.name),
            nameDuplicate = (nameCounts[variable.name] ?: 0) > 1,
            value = (source as? EnvironmentValueSource.Literal)?.value,
            revealed = index in revealed,
            kind = source.kind(),
            reference = (source as? EnvironmentValueSource.Reference)?.let { reference ->
                EnvironmentReferenceSnapshot(
                    itemName = referenceNames[reference.cipherId],
                    customFieldName = (reference.field as? EnvironmentFieldReference.Custom)?.name,
                )
            },
        )
    }
}

private fun EnvironmentMount.snapshot(status: EnvironmentMountStatus?) = EnvironmentMountSnapshot(
    id = id,
    path = path,
    status = when (status ?: EnvironmentMountStatus.Pending) {
        EnvironmentMountStatus.Pending -> EnvironmentMountSnapshotStatus.PENDING
        EnvironmentMountStatus.Ready -> EnvironmentMountSnapshotStatus.READY
        EnvironmentMountStatus.Error -> EnvironmentMountSnapshotStatus.ERROR
    },
)

private fun EnvironmentCapability.kind(): EnvironmentCapabilityKind = when (this) {
    EnvironmentCapability.Authenticate -> EnvironmentCapabilityKind.AUTHENTICATE
    EnvironmentCapability.ListEnvironments -> EnvironmentCapabilityKind.LIST_ENVIRONMENTS
    EnvironmentCapability.CreateEnvironment -> EnvironmentCapabilityKind.CREATE_ENVIRONMENT
    EnvironmentCapability.RenameEnvironment -> EnvironmentCapabilityKind.RENAME_ENVIRONMENT
    EnvironmentCapability.ListVariables -> EnvironmentCapabilityKind.LIST_VARIABLES
    EnvironmentCapability.AppendVariables -> EnvironmentCapabilityKind.APPEND_VARIABLES
    EnvironmentCapability.CreateLocalEnvFile -> EnvironmentCapabilityKind.CREATE_LOCAL_ENV_FILE
    EnvironmentCapability.ListLocalEnvFiles -> EnvironmentCapabilityKind.LIST_LOCAL_ENV_FILES
    EnvironmentCapability.ReadLocalEnvFile -> EnvironmentCapabilityKind.READ_LOCAL_ENV_FILE
}

private fun EnvironmentValueSource.kind(): EnvironmentVariableKind = when (this) {
    is EnvironmentValueSource.Literal -> EnvironmentVariableKind.VALUE
    is EnvironmentValueSource.Reference -> EnvironmentVariableKind.REFERENCE
    EnvironmentValueSource.Unset -> EnvironmentVariableKind.UNSET
    is EnvironmentValueSource.Unsupported -> EnvironmentVariableKind.UNSUPPORTED
}
