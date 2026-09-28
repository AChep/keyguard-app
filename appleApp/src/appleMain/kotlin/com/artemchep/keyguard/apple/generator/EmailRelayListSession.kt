package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Observes relay metadata and owns one navigation entry's commands. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooGenericExceptionCaught") // Repository failures are recoverable screen state.
internal class EmailRelayListSession(
    scope: CoroutineScope,
    source: () -> Flow<List<DGeneratorEmailRelay>>,
    providerNames: Map<String, String>,
    private val selectedIds: MutableStateFlow<Set<String>>,
    actionTitle: suspend (EmailRelayActionKind) -> String,
    onChange: (EmailRelayListSnapshot) -> Unit,
) {
    private val job = Job(scope.coroutineContext[Job])
    private val refresh = MutableStateFlow(0)
    // Commands read current metadata even while a snapshot is waiting to publish.
    private val latest = MutableStateFlow(RelayCatalog(EmailRelayLoadStatus.LOADING))

    init {
        CoroutineScope(scope.coroutineContext + job).launch {
            val actions = EmailRelayActionKind.entries.associateWith { action ->
                VaultActionSnapshot(
                    id = action.actionId,
                    title = actionTitle(action),
                    isCopy = false,
                    iconName = when (action) {
                        EmailRelayActionKind.EDIT -> "pencil"
                        EmailRelayActionKind.DUPLICATE -> "doc.on.doc"
                        EmailRelayActionKind.DELETE -> "trash"
                    },
                    danger = action == EmailRelayActionKind.DELETE,
                )
            }
            val catalogs = refresh.flatMapLatest {
                flow {
                    emit(RelayCatalog(EmailRelayLoadStatus.LOADING))
                    try {
                        source().collect { entries ->
                            val rows = entries.mapNotNull { entry ->
                                val id = entry.id?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                                RelayRow(id, entry.name, providerNames[entry.type] ?: entry.type,
                                    entry.type in providerNames)
                            }
                            val available = rows.mapTo(mutableSetOf()) { it.id }
                            selectedIds.update { it.intersect(available) }
                            emit(RelayCatalog(EmailRelayLoadStatus.READY, rows))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordException(e)
                        emit(RelayCatalog(EmailRelayLoadStatus.FAILED))
                    }
                }
            }
            combine(catalogs.onEach { latest.value = it }, selectedIds) { catalog, selected ->
                catalog to selected.intersect(catalog.rows.mapTo(mutableSetOf()) { it.id })
            }.map { (catalog, selected) ->
                EmailRelayListSnapshot(
                    status = catalog.status,
                    items = catalog.rows.map { row ->
                        EmailRelayListItemSnapshot(
                            id = row.id, title = row.name, service = row.service, selected = row.id in selected,
                            actions = EmailRelayActionKind.entries
                                .filter { it != EmailRelayActionKind.EDIT || row.canEdit }
                                .map(actions::getValue),
                        )
                    },
                    selectionCount = selected.size,
                    selectionActions = if (selected.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(actions.getValue(EmailRelayActionKind.DELETE))
                    },
                    canSelectAll = selected.size < catalog.rows.size,
                )
            }.collectOnMain { snapshot ->
                if (job.isActive) onChange(snapshot)
            }
        }
    }

    fun toggleSelection(id: String) {
        if (!ready() || latest.value.rows.none { it.id == id }) return
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        if (ready()) selectedIds.value = emptySet()
    }

    fun selectAll() {
        if (ready()) selectedIds.value = latest.value.rows.mapTo(mutableSetOf()) { it.id }
    }

    fun retry() {
        if (!job.isActive) return
        latest.value = RelayCatalog(EmailRelayLoadStatus.LOADING)
        refresh.update { it + 1 }
    }

    fun requestAction(actionId: String, itemId: String?): EmailRelayActionRequestSnapshot? {
        val action = EmailRelayActionKind.entries.firstOrNull { it.actionId == actionId }
        if (!ready() || action == null) return null
        val rows = when {
            itemId != null -> latest.value.rows.filter { it.id == itemId }
            action == EmailRelayActionKind.DELETE -> latest.value.rows.filter { it.id in selectedIds.value }
            else -> emptyList()
        }
        return when {
            rows.isEmpty() -> null
            action == EmailRelayActionKind.EDIT && rows.any { !it.canEdit } -> null
            else -> EmailRelayActionRequestSnapshot(action, rows.map { EmailRelayActionTargetSnapshot(it.id, it.name) })
        }
    }

    private fun ready() = job.isActive && latest.value.status == EmailRelayLoadStatus.READY

    fun close() {
        job.cancel()
        latest.value = RelayCatalog(EmailRelayLoadStatus.LOADING)
    }
}

internal val EmailRelayActionKind.actionId: String get() = when (this) {
    EmailRelayActionKind.EDIT -> "emailRelay.edit"
    EmailRelayActionKind.DUPLICATE -> "emailRelay.duplicate"
    EmailRelayActionKind.DELETE -> "emailRelay.selection.delete"
}

private data class RelayRow(val id: String, val name: String, val service: String, val canEdit: Boolean)

private data class RelayCatalog(val status: EmailRelayLoadStatus, val rows: List<RelayRow> = emptyList())
