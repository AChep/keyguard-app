package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.export.ExportRoute
import com.artemchep.keyguard.feature.export.ExportState
import com.artemchep.keyguard.feature.export.exportScreenStateProducer
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.SettingOptionSnapshot
import com.artemchep.keyguard.apple.model.VaultFilterItemKind
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot
import com.artemchep.keyguard.apple.model.mapFilterItemsToSnapshots
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.koin.core.scope.Scope

data class ExportSnapshot(
    val loaded: Boolean,
    val title: String,
    val itemsCount: Int,
    val attachmentsCount: Int,
    val attachmentsSize: String?,
    val attachmentsEnabled: Boolean,
    val attachmentsNote: String,
    val canToggleAttachments: Boolean,
    /** The export formats; each [SettingOptionSnapshot.id] is an action id. */
    val formats: List<SettingOptionSnapshot>,
    val formatTitle: String,
    val formatNote: String,
    val passwordLabel: String,
    val passwordValue: String,
    val passwordRevision: Int,
    val passwordError: String?,
    val passwordHint: String?,
    val confirmationPasswordLabel: String,
    val confirmationPasswordValue: String,
    val confirmationPasswordRevision: Int,
    val confirmationPasswordError: String?,
    /** The password is set and confirmed. */
    val passwordValid: Boolean,
    val canExport: Boolean,
    val running: Boolean,
    val progress: Double?,
    val canCancel: Boolean,
    val filters: List<VaultFilterItemSnapshot>,
    val activeFilterCount: Int,
    val canClearFilters: Boolean,
    val canSaveFilters: Boolean,
) {
    companion object {
        val empty = ExportSnapshot(
            loaded = false,
            title = "",
            itemsCount = 0,
            attachmentsCount = 0,
            attachmentsSize = null,
            attachmentsEnabled = false,
            attachmentsNote = "",
            canToggleAttachments = false,
            formats = emptyList(),
            formatTitle = "",
            formatNote = "",
            passwordLabel = "",
            passwordValue = "",
            passwordRevision = 0,
            passwordError = null,
            passwordHint = null,
            confirmationPasswordLabel = "",
            confirmationPasswordValue = "",
            confirmationPasswordRevision = 0,
            confirmationPasswordError = null,
            passwordValid = false,
            canExport = false,
            running = false,
            progress = null,
            canCancel = false,
            filters = emptyList(),
            activeFilterCount = 0,
            canClearFilters = false,
            canSaveFilters = false,
        )
    }
}

/** The values of the [ExportState] flows at one moment. */
internal data class ExportFormInput(
    val items: ExportState.Items,
    val attachments: ExportState.Attachments,
    val filter: ExportState.Filter,
    val format: ExportState.Format,
    val password: ExportState.Password,
    val confirmation: ExportState.Password,
    val content: ExportState.Content,
)

/** Maps the form to a snapshot and the handlers of its action ids. */
internal suspend fun ExportFormInput.toSnapshot(
    title: String,
    translate: suspend (TextHolder) -> String,
): Pair<ExportSnapshot, Map<String, () -> Unit>> {
    val handlers = LinkedHashMap<String, () -> Unit>()
    val filterSnapshots = mapFilterItemsToSnapshots(filter.items, handlers)
    val formatKeys = ActionKeyAllocator("export:format")
    val formatSnapshots = format.options
        .map { action ->
            val optionTitle = translate(action.title)
            val id = formatKeys.keyFor(action, optionTitle)
            action.onClick?.let { handlers[id] = it }
            SettingOptionSnapshot(
                id = id,
                title = optionTitle,
                selected = action.selected,
            )
        }
    content.onExportClick?.let { handlers["export:run"] = it }
    attachments.onToggle?.let { handlers["export:atts:toggle"] = it }
    filter.onClear?.let { handlers["export:filters:clear"] = it }
    filter.onSave?.let { handlers["export:filters:save"] = it }
    items.onView?.let { handlers["export:view:items"] = it }
    attachments.onView?.let { handlers["export:view:atts"] = it }
    val passwordModel = password.model
    val confirmationModel = confirmation.model
    val snapshot = ExportSnapshot(
        loaded = true,
        title = title,
        itemsCount = items.count,
        attachmentsCount = attachments.count,
        attachmentsSize = attachments.size,
        attachmentsEnabled = attachments.enabled,
        attachmentsNote = format.attachmentsNote,
        canToggleAttachments = attachments.onToggle != null,
        formats = formatSnapshots,
        formatTitle = format.title,
        formatNote = format.note,
        passwordLabel = format.passwordLabel,
        passwordValue = passwordModel.text,
        passwordRevision = passwordModel.textRevision,
        passwordError = passwordModel.error,
        passwordHint = passwordModel.hint,
        confirmationPasswordLabel = format.confirmPasswordLabel,
        confirmationPasswordValue = confirmationModel.text,
        confirmationPasswordRevision = confirmationModel.textRevision,
        confirmationPasswordError = confirmationModel.error,
        passwordValid = passwordModel.text.isNotEmpty() &&
                passwordModel.error == null &&
                confirmationModel.text == passwordModel.text,
        canExport = content.onExportClick != null,
        running = false,
        progress = null,
        canCancel = false,
        filters = filterSnapshots,
        activeFilterCount = filterSnapshots.count { it.kind == VaultFilterItemKind.ITEM && it.checked },
        canClearFilters = filter.onClear != null,
        canSaveFilters = filter.onSave != null,
    )
    return snapshot to handlers
}

/** Shows the progress of a running export over the form. */
internal fun ExportSnapshot.withRun(
    run: ExportExecutionState,
): ExportSnapshot {
    val progress = run.total?.takeIf { it > 0L }?.let { total ->
        run.downloaded?.let { (it.toDouble() / total).coerceIn(0.0, 1.0) }
    }
    return copy(
        canExport = canExport && !run.running,
        running = run.running,
        progress = progress,
        canCancel = run.exportId != null,
    )
}

/**
 * The producer's [ExportState] holds several inner StateFlows, so they are combined into one
 * snapshot stream. The live state is published too: the password edits carry a String, so they
 * cannot go through the action-handler map.
 */
internal class ExportController(
    private val ctx: CoreContext,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun produceExportInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        title: String?,
        filter: DFilter?,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (ExportSnapshot, Map<String, () -> Unit>, ExportState?) -> Unit,
    ) {
        val stateScope = ctx.koin.newHeadlessStateFlowScope("export", scope, interceptor)
        val leContext = ctx.koin.get<LeContext>()
        val screenTitle = title ?: stateScope.translate(Res.string.exportaccount_header_title)
        val execution = ExportExecution(
            scope = scope,
            manager = sessionKoin.get<ExportManager>(),
            onSaved = {
                stateScope.message(ToastMessage(
                    title = stateScope.translate(Res.string.exportaccount_export_success),
                    type = ToastMessage.Type.SUCCESS,
                ))
                stateScope.navigatePopSelf()
            },
            onFailure = { error ->
                val readable = getErrorReadableMessage(error, stateScope)
                stateScope.message(ToastMessage(
                    title = stateScope.translate(Res.string.exportaccount_export_failure),
                    text = readable.text ?: readable.title,
                    type = ToastMessage.Type.ERROR,
                ))
            },
        )
        val producerFlow = with(sessionKoin) {
            stateScope.exportScreenStateProducer(
                filterContext = get(),
                addCipherFilter = get(),
                confirmationRouteFactory = get(),
                getCipherFilters = get(),
                args = ExportRoute.Args(title = title, filter = filter),
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
                onExportRequest = execution::start,
            )
        }
        producerFlow
            .flatMapLatest { loadable ->
                val state = loadable.getOrNull()
                    ?: return@flatMapLatest flowOf(
                        Triple(ExportSnapshot.empty, emptyMap<String, () -> Unit>(), null),
                    )
                val formFlow = combine(
                    state.itemsFlow,
                    state.attachmentsFlow,
                    state.filterFlow,
                    combine(state.formatFlow, state.passwordFlow, state.confirmationPasswordFlow, ::Triple),
                    state.contentFlow,
                ) { items, attachments, filterState, (format, password, confirmation), content ->
                    val input = ExportFormInput(
                        items = items,
                        attachments = attachments,
                        filter = filterState,
                        format = format,
                        password = password,
                        confirmation = confirmation,
                        content = content,
                    )
                    val (snapshot, handlers) = input.toSnapshot(screenTitle) { text ->
                        textResource(text, leContext)
                    }
                    Triple(snapshot, handlers, state)
                }
                combine(formFlow, execution.state) { (snapshot, handlers, liveState), run ->
                    val activeHandlers = if (run.running) {
                        mapOf("export:cancel" to execution::cancel)
                    } else handlers
                    Triple(
                        snapshot.withRun(run),
                        activeHandlers,
                        liveState.takeUnless { run.running },
                    )
                }
            }
            .collectOnMain { (snapshot, handlers, state) ->
                publish(snapshot, handlers, state)
            }
    }
}
