package com.artemchep.keyguard.feature.environments.ui

import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentDocument
import com.artemchep.keyguard.common.service.environment.EnvironmentMcp
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.common.service.environment.ensureElevatedAccess
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.EnvironmentEditorState
import com.artemchep.keyguard.feature.environments.EnvironmentOwnership
import com.artemchep.keyguard.feature.environments.EnvironmentValueSource
import com.artemchep.keyguard.presentation.operation.SingleOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

/**
 * Produces the environments editor; one operation runs at a time. The draft
 * lives only while the flow is collected and the vault session lasts, so
 * neither the draft nor its secrets reach any persisted state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun environmentsStateProducer(
    repository: EnvironmentRepository,
    access: EnvironmentAccess,
    mounts: EnvironmentMounts,
    mcp: EnvironmentMcp,
    verifyAccess: suspend () -> Boolean = { false },
    onEffect: (EnvironmentsEffect) -> Unit = {},
): Flow<EnvironmentsState> = repository.state
    .map { state -> state.unlocked to state.sessionVersion }
    .distinctUntilChanged()
    .flatMapLatest { (unlocked, sessionVersion) ->
        if (unlocked) {
            sessionStateProducer(
                services = Services(repository, access, mounts, mcp),
                sessionVersion = sessionVersion,
                verifyAccess = verifyAccess,
                onEffect = onEffect,
            )
        } else {
            flowOf(EnvironmentsState())
        }
    }

private class Services(
    val repository: EnvironmentRepository,
    val access: EnvironmentAccess,
    val mounts: EnvironmentMounts,
    val mcp: EnvironmentMcp,
)

private class Draft(
    /** Identity of the draft; a replaced draft never comes back. */
    val key: Any,
    /** Identity of the account of the draft, including after an A → B → A switch. */
    val accountKey: Any,
    val state: EnvironmentEditorState,
)

@Suppress("CyclomaticComplexMethod", "LongMethod") // Local actions share one session lifetime.
private fun sessionStateProducer(
    services: Services,
    sessionVersion: String?,
    verifyAccess: suspend () -> Boolean,
    onEffect: (EnvironmentsEffect) -> Unit,
): Flow<EnvironmentsState> = channelFlow {
    val repository = services.repository
    val access = services.access
    val mounts = services.mounts
    val mcp = services.mcp
    val operation = SingleOperation(this)
    val draftFlow = MutableStateFlow<Draft?>(null)
    // The latest requested draft, also while it loads.
    var draftKey = Any()

    // Click events may still refer to a previous rendered state, so
    // every action checks that its session is still current. A
    // conflated lock might never restart this flow.
    fun isCurrentSession(): Boolean = isActive && repository.state.value
        .let { it.unlocked && it.sessionVersion == sessionVersion }

    fun isCurrentDraft(key: Any): Boolean = key === draftKey && isCurrentSession()

    fun currentDraft(key: Any): Draft? = draftFlow.value
        ?.takeIf { it.key === key && isCurrentSession() }

    /** @return `null` while an operation that can not be interrupted runs. */
    fun replaceDraft(): Any? {
        if (!operation.interrupt()) return null
        val key = Any()
        draftKey = key
        draftFlow.value = null
        return key
    }

    fun edit(
        key: Any,
        accountKey: Any? = null,
        transform: (EnvironmentEditorState) -> EnvironmentEditorState,
    ) {
        val draft = currentDraft(key)
            ?.takeIf { accountKey == null || accountKey === it.accountKey }
            ?: return
        if (operation.state.value.busy) return
        val state = transform(draft.state)
        draftFlow.value = Draft(
            key = key,
            accountKey = if (state.accountId != draft.state.accountId) Any() else draft.accountKey,
            state = state,
        )
    }

    fun create() {
        if (!isCurrentSession()) return
        val key = replaceDraft()
            ?: return
        draftFlow.value = Draft(
            key = key,
            accountKey = Any(),
            state = EnvironmentEditorState(
                accountId = repository.state.value.accounts.firstOrNull()?.id.orEmpty(),
            ),
        )
    }

    fun open(id: String) {
        if (!isCurrentSession()) return
        val key = replaceDraft()
            ?: return
        // Opening another draft interrupts the load.
        operation.launch(interruptible = true) {
            if (!access.ensureElevatedAccess(id, verify = verifyAccess)) {
                if (isCurrentDraft(key)) onEffect(EnvironmentsEffect.Denied)
                return@launch
            }
            val document = repository.load(id)
            if (!isCurrentDraft(key)) return@launch
            draftFlow.value = Draft(
                key = key,
                accountKey = Any(),
                state = document.toEditorState(),
            )
        }
    }

    fun close() {
        if (isCurrentSession()) replaceDraft()
    }

    fun save(key: Any) {
        val draft = currentDraft(key)?.state
            ?.takeIf { it.canSave }
            ?: return
        operation.launch {
            // The confirmed access might have been revoked since
            // the environment was opened.
            if (draft.id != null && !access.ensureElevatedAccess(draft.id, verify = verifyAccess)) return@launch
            val id = repository.save(
                draft.id, draft.accountId, draft.name, draft.definition, draft.description, draft.ownership,
            )
            val current = currentDraft(key)
                ?: return@launch
            draftFlow.value = Draft(
                key = key,
                accountKey = current.accountKey,
                state = draft.copy(id = id, revealed = emptySet()),
            )
            onEffect(EnvironmentsEffect.Saved(id))
        }
    }

    fun trash(key: Any) {
        val id = currentDraft(key)?.state?.id
            ?: return
        operation.launch {
            if (!access.ensureElevatedAccess(id, verify = verifyAccess)) return@launch
            repository.trash(id)
            if (!isCurrentDraft(key)) return@launch
            draftFlow.value = null
            onEffect(EnvironmentsEffect.Trashed)
        }
    }

    fun mount(key: Any, path: String, bookmark: String?) {
        val id = currentDraft(key)?.state?.id
            ?: return
        operation.launch { mounts.create(id, path, bookmark) }
    }

    fun launchInSession(block: suspend () -> Unit) {
        if (isCurrentSession()) operation.launch(block = block)
    }

    fun editor(draft: Draft, busy: Boolean): EnvironmentEditor {
        val key = draft.key
        val accountKey = draft.accountKey
        val state = draft.state
        val canEdit = state.editable && !busy
        fun <T> T.takeIfCanEdit(): T? = takeIf { canEdit }
        return EnvironmentEditor(
            draft = state,
            onNameChange = { value: String -> edit(key) { it.withName(value) } }
                .takeIfCanEdit(),
            onDescriptionChange = { value: String -> edit(key) { it.withDescription(value) } }
                .takeIfCanEdit(),
            onOwnershipChange = { accountId: String, ownership: EnvironmentOwnership ->
                edit(key) { it.withOwnership(accountId, ownership) }
            }.takeIfCanEdit(),
            onImportTextChange = { value: String -> edit(key) { it.withImportText(value) } }
                .takeIfCanEdit(),
            onPreviewImport = { edit(key, transform = EnvironmentEditorState::previewImport) }
                .takeIfCanEdit(),
            onApplyImport = { edit(key, transform = EnvironmentEditorState::applyImport) }
                .takeIfCanEdit(),
            onAddValue = { edit(key) { it.addVariable(EnvironmentValueSource.Literal("")) } }
                .takeIfCanEdit(),
            onAddReference = { reference: EnvironmentValueSource.Reference, name: String ->
                edit(key, accountKey) { it.addVariable(reference, name) }
            }.takeIfCanEdit(),
            onRemoveVariable = { index: Int -> edit(key) { it.removeVariable(index) } }
                .takeIfCanEdit(),
            onMoveVariable = { from: Int, to: Int -> edit(key) { it.moveVariable(from, to) } }
                .takeIfCanEdit(),
            onVariableNameChange = { index: Int, value: String -> edit(key) { it.withVariableName(index, value) } }
                .takeIfCanEdit(),
            onVariableValueChange = { index: Int, value: String -> edit(key) { it.withVariableValue(index, value) } }
                .takeIfCanEdit(),
            onVariableSourceChange = { index: Int, source: EnvironmentValueSource ->
                if (source !is EnvironmentValueSource.Reference) {
                    edit(key) { it.withVariableSource(index, source) }
                }
            }.takeIfCanEdit(),
            onVariableReferenceChange = { index: Int, reference: EnvironmentValueSource.Reference, name: String ->
                edit(key, accountKey) { draft ->
                    val next = draft.withVariableSource(index, reference)
                    val unnamed = next.definition.variables.getOrNull(index)?.name?.isBlank() == true
                    if (unnamed) next.withVariableName(index, name) else next
                }
            }.takeIfCanEdit(),
            onRevealToggle = { index -> edit(key) { it.toggleRevealed(index) } },
            literalValue = { index -> currentDraft(key)?.state?.literalValue(index) },
            onSave = { save(key) }
                .takeIf { !busy && state.canSave },
            onTrash = { trash(key) }
                .takeIf { !busy && state.id != null },
            onMount = { path: String, bookmark: String? -> mount(key, path, bookmark) }
                .takeIf { !busy && state.id != null },
        )
    }

    val onCreate = ::create
    val onOpen = ::open
    val onClose = ::close
    val onDisableMount = { id: String -> launchInSession { mounts.disable(id) } }
    val onMcpEnabledChange = { enabled: Boolean -> launchInSession { mcp.setEnabled(enabled) } }
    combine(
        draftFlow,
        operation.state,
    ) { draft, operationState ->
        val busy = operationState.busy
        EnvironmentsState(
            busy = busy,
            failed = operationState.failed,
            editor = draft?.let { editor(it, busy) },
            onCreate = onCreate,
            onOpen = onOpen,
            onClose = onClose,
            onDisableMount = onDisableMount.takeUnless { busy },
            onMcpEnabledChange = onMcpEnabledChange.takeUnless { busy },
        )
    }.collect(::send)
}

private fun EnvironmentDocument.toEditorState() = EnvironmentEditorState(
    id = id,
    accountId = accountId,
    ownership = ownership,
    name = name,
    description = description,
    definition = definition ?: EnvironmentDefinition(),
    editable = editable,
    unsupported = unsupported,
)
