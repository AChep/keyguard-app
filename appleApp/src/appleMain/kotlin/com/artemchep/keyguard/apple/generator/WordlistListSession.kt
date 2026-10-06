package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.common.model.DGeneratorWordlist
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

internal const val WORDLIST_ACTION_RENAME = "wordlist.selection.edit"
internal const val WORDLIST_ACTION_DELETE = "wordlist.selection.delete"

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooGenericExceptionCaught") // Repository failures are recoverable screen state.
internal class WordlistListSession(
    scope: CoroutineScope,
    source: () -> Flow<List<DGeneratorWordlist>>,
    private val selectedIds: MutableStateFlow<Set<String>>,
    counter: suspend (DGeneratorWordlist) -> String,
    actionTitle: suspend (String) -> String,
    private val onOpen: (DGeneratorWordlist) -> Unit,
    onChange: (WordlistListSnapshot) -> Unit,
) {
    private val job = Job(scope.coroutineContext[Job])
    private val refresh = MutableStateFlow(0)
    // Commands read current rows even while localized counters are being built.
    private val latest = MutableStateFlow(WordlistCatalog(WordlistLoadStatus.LOADING))

    init {
        CoroutineScope(scope.coroutineContext + job).launch {
            val actions = listOf(WORDLIST_ACTION_RENAME, WORDLIST_ACTION_DELETE).associateWith { id ->
                VaultActionSnapshot(id, actionTitle(id), isCopy = false,
                    iconName = if (id == WORDLIST_ACTION_RENAME) "pencil" else "trash",
                    danger = id == WORDLIST_ACTION_DELETE)
            }
            val catalogs = refresh.flatMapLatest {
                flow {
                    emit(WordlistCatalog(WordlistLoadStatus.LOADING))
                    try {
                        source().collect { items ->
                            val available = items.mapTo(mutableSetOf()) { it.id }
                            selectedIds.update { it.intersect(available) }
                            emit(WordlistCatalog(WordlistLoadStatus.READY, items))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordException(e)
                        emit(WordlistCatalog(WordlistLoadStatus.FAILED))
                    }
                }
            }
            combine(catalogs.onEach { latest.value = it }, selectedIds) { catalog, selected ->
                catalog to selected.intersect(catalog.items.mapTo(mutableSetOf()) { it.id })
            }.map { (catalog, selected) ->
                val actionIds = when (selected.size) {
                    0 -> emptyList()
                    1 -> listOf(WORDLIST_ACTION_RENAME, WORDLIST_ACTION_DELETE)
                    else -> listOf(WORDLIST_ACTION_DELETE)
                }
                WordlistListSnapshot(
                    status = catalog.status,
                    items = catalog.items.map { item ->
                        WordlistListItemSnapshot(
                            item.id, item.idRaw, item.name, counter(item), item.id in selected, selected.isNotEmpty(),
                        )
                    },
                    selectionCount = selected.size,
                    selectionActions = actionIds.map(actions::getValue),
                    canSelectAll = selected.size < catalog.items.size,
                )
            }.collectOnMain { snapshot ->
                if (job.isActive) onChange(snapshot)
            }
        }
    }

    fun toggleSelection(id: String) {
        if (!ready() || latest.value.items.none { it.id == id }) return
        selectedIds.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        if (ready()) selectedIds.value = emptySet()
    }

    fun selectAll() {
        if (ready()) selectedIds.value = latest.value.items.mapTo(mutableSetOf()) { it.id }
    }

    fun retry() {
        if (!job.isActive) return
        latest.value = WordlistCatalog(WordlistLoadStatus.LOADING)
        refresh.update { it + 1 }
    }

    fun open(id: String) {
        if (ready()) latest.value.items.firstOrNull { it.id == id }?.let(onOpen)
    }

    fun requestAction(actionId: String, itemId: String?): WordlistActionRequestSnapshot? {
        val items = if (ready() && actionId in listOf(WORDLIST_ACTION_RENAME, WORDLIST_ACTION_DELETE)) {
            latest.value.items.filter { if (itemId == null) it.id in selectedIds.value else it.id == itemId }
        } else {
            emptyList()
        }
        if (items.isEmpty() || actionId == WORDLIST_ACTION_RENAME && items.size != 1) return null
        return WordlistActionRequestSnapshot(actionId, items.map { WordlistActionTargetSnapshot(it.idRaw, it.name) })
    }

    private fun ready() = job.isActive && latest.value.status == WordlistLoadStatus.READY

    fun close() {
        job.cancel()
        latest.value = WordlistCatalog(WordlistLoadStatus.LOADING)
    }
}

private data class WordlistCatalog(
    val status: WordlistLoadStatus,
    val items: List<DGeneratorWordlist> = emptyList(),
)
