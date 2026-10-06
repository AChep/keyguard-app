package com.artemchep.keyguard.apple.directory

import com.artemchep.keyguard.URL_DUCKDUCKGO_ICONS
import com.artemchep.keyguard.apple.core.EntryListQuery
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.model.uniqueListIds
import com.artemchep.keyguard.common.io.parallelSearch
import com.artemchep.keyguard.common.util.ensureUrlScheme
import com.artemchep.keyguard.feature.favicon.FaviconAccountServer
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.find
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.search.search.searchDebounceMillis
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Loading, search and publication belong to one navigation entry. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooGenericExceptionCaught") // Catalog/search failures are recoverable screen state.
internal class ServiceDirectoryListSession(
    scope: CoroutineScope,
    private val query: MutableStateFlow<EntryListQuery>,
    load: suspend () -> List<DirectoryEntry>,
    private val onOpen: (String) -> Unit,
    onChange: (ServiceDirectorySnapshot) -> Unit,
) {
    private val job = Job(scope.coroutineContext[Job])
    private val refresh = MutableStateFlow(0)
    private val latest = MutableStateFlow(ServiceDirectorySnapshot.empty)

    init {
        CoroutineScope(scope.coroutineContext + job).launch {
            val catalogs = refresh.flatMapLatest {
                flow {
                    emit(DirectoryCatalog(ServiceDirectoryLoadStatus.LOADING))
                    try {
                        emit(DirectoryCatalog(ServiceDirectoryLoadStatus.READY, directoryRows(load())))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordException(e)
                        emit(DirectoryCatalog(ServiceDirectoryLoadStatus.FAILED))
                    }
                }
            }
            combine(catalogs, query) { catalog, field -> catalog to field }
                .transformLatest { (catalog, field) ->
                    if (catalog.status != ServiceDirectoryLoadStatus.READY) {
                        emit(ServiceDirectorySnapshot.empty.copy(
                            status = catalog.status, query = field.text, queryRevision = field.textRevision,
                        ))
                        return@transformLatest
                    }
                    emit(latest.value.copy(
                        status = ServiceDirectoryLoadStatus.READY,
                        query = field.text, queryRevision = field.textRevision, searching = true,
                    ))
                    try {
                        val text = field.text.trim()
                        val rows = if (text.isEmpty()) {
                            catalog.rows.map { it.snapshot() }
                        } else {
                            delay(searchDebounceMillis(text))
                            val index = IndexedText(text)
                            catalog.rows.parallelSearch { row -> row.index.find(index)?.let { row to it } }
                                .sortedByDescending { (_, match) -> match.score }
                                .map { (row, match) ->
                                    row.snapshot().copy(highlights = match.highlightedText.spanStyles.map {
                                        DirectoryTextRangeSnapshot(it.start, it.end)
                                    })
                                }
                        }
                        currentCoroutineContext().ensureActive()
                        emit(ServiceDirectorySnapshot(
                            ServiceDirectoryLoadStatus.READY, field.text, field.textRevision,
                            text, false, directorySections(rows),
                        ))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordException(e)
                        emit(ServiceDirectorySnapshot.empty.copy(
                            status = ServiceDirectoryLoadStatus.FAILED,
                            query = field.text, queryRevision = field.textRevision,
                        ))
                    }
                }.collectOnMain { snapshot ->
                    if (job.isActive) {
                        latest.value = snapshot
                        onChange(snapshot)
                    }
                }
        }
    }

    fun setQuery(text: String) {
        if (job.isActive) query.update { it.copy(text = text) }
    }

    fun retry() {
        if (!job.isActive) return
        latest.value = ServiceDirectorySnapshot.empty
        refresh.update { it + 1 }
    }

    fun open(id: String) {
        if (!job.isActive || latest.value.status != ServiceDirectoryLoadStatus.READY) return
        if (latest.value.items.any { it.kind == ServiceDirectoryItemKind.CONTENT && it.id == id }) onOpen(id)
    }

    fun close() {
        job.cancel()
        latest.value = ServiceDirectorySnapshot.empty
    }
}

private data class DirectoryCatalog(
    val status: ServiceDirectoryLoadStatus,
    val rows: List<DirectoryRow> = emptyList(),
)

internal data class DirectoryRow(val id: String, val entry: DirectoryEntry) {
    val index = IndexedText(entry.name)
    private val faviconUrl = entry.faviconUrl
        ?.let { DirectoryFaviconServer.transform(ensureUrlScheme(it)) }

    fun snapshot() = ServiceDirectoryItemSnapshot(
        id, ServiceDirectoryItemKind.CONTENT, entry.name, faviconUrl,
    )
}

// Unlike the JVM image loader, Apple can't scrape a site
// for its own icon, so the rows use DuckDuckGo's icons.
private val DirectoryFaviconServer = FaviconAccountServer(
    id = "duckduckgo",
    transformer = { host -> "$URL_DUCKDUCKGO_ICONS$host.ico" },
)

/** List and detail lookup must allocate identities from the same sorted catalog. */
internal fun directoryRows(entries: List<DirectoryEntry>): List<DirectoryRow> {
    val sorted = entries.sortedWith { a, b -> AlphabeticalSort.compareStr(a.name, b.name) }
    val ids = uniqueListIds(sorted.map { it.key })
    return sorted.mapIndexed { index, entry -> DirectoryRow(ids[index], entry) }
}

private fun directorySections(rows: List<ServiceDirectoryItemSnapshot>): List<ServiceDirectoryItemSnapshot> {
    if (rows.size < AlphabeticalSortMinItemsSize) return rows
    val usedIds = rows.mapTo(mutableSetOf()) { it.id }
    var previous: String? = null
    return buildList {
        for (row in rows) {
            val title = (row.name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#').toString()
            if (title != previous) {
                var id = "section:${row.id}"
                while (!usedIds.add(id)) id += "#"
                add(ServiceDirectoryItemSnapshot(id, ServiceDirectoryItemKind.SECTION, title, null))
                previous = title
            }
            add(row)
        }
    }
}
