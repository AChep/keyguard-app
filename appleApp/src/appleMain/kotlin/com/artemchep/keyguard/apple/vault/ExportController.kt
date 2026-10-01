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
import com.artemchep.keyguard.apple.model.VaultFilterItemSnapshot
import com.artemchep.keyguard.apple.model.mapFilterItemsToSnapshots
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
    val canToggleAttachments: Boolean,
    val passwordValue: String,
    val passwordRevision: Int,
    val passwordError: String?,
    val passwordHint: String?,
    val canExport: Boolean,
    val running: Boolean,
    val progress: Double?,
    val canCancel: Boolean,
    val filters: List<VaultFilterItemSnapshot>,
    val canClearFilters: Boolean,
) {
    companion object {
        val empty = ExportSnapshot(
            loaded = false,
            title = "",
            itemsCount = 0,
            attachmentsCount = 0,
            attachmentsSize = null,
            attachmentsEnabled = false,
            canToggleAttachments = false,
            passwordValue = "",
            passwordRevision = 0,
            passwordError = null,
            passwordHint = null,
            canExport = false,
            running = false,
            progress = null,
            canCancel = false,
            filters = emptyList(),
            canClearFilters = false,
        )
    }
}

/**
 * The producer's [ExportState] holds five inner StateFlows, so they are combined into one snapshot
 * stream. The live state is published too: the password edit carries a String, so it cannot go
 * through the action-handler map.
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
                    state.passwordFlow,
                    state.contentFlow,
                ) { items, attachments, filterState, password, content ->
                    val handlers = LinkedHashMap<String, () -> Unit>()
                    val filterSnapshots = mapFilterItemsToSnapshots(filterState.items, handlers)
                    content.onExportClick?.let { handlers["export:run"] = it }
                    attachments.onToggle?.let { handlers["export:atts:toggle"] = it }
                    filterState.onClear?.let { handlers["export:filters:clear"] = it }
                    items.onView?.let { handlers["export:view:items"] = it }
                    attachments.onView?.let { handlers["export:view:atts"] = it }
                    val snapshot = ExportSnapshot(
                        loaded = true,
                        title = screenTitle,
                        itemsCount = items.count,
                        attachmentsCount = attachments.count,
                        attachmentsSize = attachments.size,
                        attachmentsEnabled = attachments.enabled,
                        canToggleAttachments = attachments.onToggle != null,
                        passwordValue = password.model.text,
                        passwordRevision = password.model.textRevision,
                        passwordError = password.model.error,
                        passwordHint = password.model.hint,
                        canExport = content.onExportClick != null,
                        running = false,
                        progress = null,
                        canCancel = false,
                        filters = filterSnapshots,
                        canClearFilters = filterState.onClear != null,
                    )
                    Triple(snapshot, handlers as Map<String, () -> Unit>, state)
                }
                combine(formFlow, execution.state) { (snapshot, handlers, liveState), run ->
                    val activeHandlers = if (run.running) {
                        mapOf("export:cancel" to execution::cancel)
                    } else handlers
                    val progress = run.total?.takeIf { it > 0L }?.let { total ->
                        run.downloaded?.let { (it.toDouble() / total).coerceIn(0.0, 1.0) }
                    }
                    Triple(
                        snapshot.copy(
                            canExport = snapshot.canExport && !run.running,
                            running = run.running,
                            progress = progress,
                            canCancel = run.exportId != null,
                        ),
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
