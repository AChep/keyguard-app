package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.apple.core.EntryListQuery
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.model.uniqueListIds
import com.artemchep.keyguard.common.io.parallelSearch
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.find
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooGenericExceptionCaught") // Metadata and word loading fail independently and can be retried.
internal class WordlistDetailSession(
    scope: CoroutineScope,
    wordlistId: Long,
    getWordlists: GetWordlists,
    getWords: GetWordlistPrimitive,
    private val query: MutableStateFlow<EntryListQuery>,
    onChange: (WordlistDetailSnapshot) -> Unit,
) {
    private val job = Job(scope.coroutineContext[Job])
    private val refresh = MutableStateFlow(0)

    init {
        CoroutineScope(scope.coroutineContext + job).launch {
            val metadata = refresh.flatMapLatest {
                flow {
                    emit(WordlistMetadata(WordlistLoadStatus.LOADING))
                    try {
                        getWordlists().collect { entries ->
                            val item = entries.firstOrNull { it.idRaw == wordlistId }
                            emit(WordlistMetadata(WordlistLoadStatus.READY, item))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordException(e)
                        emit(WordlistMetadata(WordlistLoadStatus.FAILED))
                    }
                }
            }
            val words = refresh.flatMapLatest {
                flow {
                    emit(WordlistWords(WordlistLoadStatus.LOADING))
                    try {
                        getWords(wordlistId).collect { items ->
                            val ids = uniqueListIds(items)
                            emit(WordlistWords(WordlistLoadStatus.READY, items.mapIndexed { index, text ->
                                IndexedWord(ids[index], text, IndexedText(text))
                            }))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordException(e)
                        emit(WordlistWords(WordlistLoadStatus.FAILED))
                    }
                }
            }
            var previous = WordlistDetailSnapshot.empty
            val search = combine(words, query) { rows, field -> rows to field }
                .transformLatest { (rows, field) ->
                    if (rows.status != WordlistLoadStatus.READY) {
                        emit(WordlistDetailSnapshot.empty.copy(
                            status = rows.status, query = field.text, queryRevision = field.textRevision,
                        ))
                        return@transformLatest
                    }
                    emit(previous.copy(
                        status = WordlistLoadStatus.READY, query = field.text,
                        queryRevision = field.textRevision, searching = true,
                    ))
                    try {
                        val text = field.text.trim()
                        val matches = if (text.isEmpty()) {
                            rows.items.map { WordlistWordSnapshot(it.id, it.text) }
                        } else {
                            delay(if (text.length <= 3) 200L else 88L)
                            val index = IndexedText(text)
                            rows.items.parallelSearch { word -> word.index.find(index)?.let { word to it } }
                                .sortedByDescending { (_, match) -> match.score }
                                .map { (word, match) ->
                                    WordlistWordSnapshot(word.id, word.text, match.highlightedText.spanStyles.map {
                                        WordlistTextRangeSnapshot(it.start, it.end)
                                    })
                                }
                        }
                        currentCoroutineContext().ensureActive()
                        emit(WordlistDetailSnapshot.empty.copy(
                            status = WordlistLoadStatus.READY, query = field.text,
                            queryRevision = field.textRevision, resultQuery = text, words = matches,
                        ))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        recordException(e)
                        emit(WordlistDetailSnapshot.empty.copy(
                            status = WordlistLoadStatus.FAILED, query = field.text, queryRevision = field.textRevision,
                        ))
                    }
                }.onEach { previous = it }
            combine(metadata, search) { meta, frame ->
                val missing = meta.status == WordlistLoadStatus.READY && meta.item == null
                val status = when {
                    missing -> WordlistLoadStatus.READY
                    meta.status == WordlistLoadStatus.FAILED ||
                        frame.status == WordlistLoadStatus.FAILED -> WordlistLoadStatus.FAILED
                    meta.status == WordlistLoadStatus.LOADING -> WordlistLoadStatus.LOADING
                    else -> frame.status
                }
                frame.copy(
                    status = status, title = meta.item?.name.orEmpty(),
                    wordCount = meta.item?.wordCount?.toInt() ?: 0, notFound = missing,
                    words = if (missing) emptyList() else frame.words,
                    searching = !missing && frame.searching,
                )
            }.collectOnMain { snapshot ->
                if (job.isActive) onChange(snapshot)
            }
        }
    }

    fun setQuery(text: String) {
        if (job.isActive) query.update { it.copy(text = text) }
    }

    fun retry() {
        if (job.isActive) refresh.update { it + 1 }
    }

    fun close() {
        job.cancel()
    }
}

private data class WordlistMetadata(val status: WordlistLoadStatus, val item: DGeneratorWordlist? = null)
private data class WordlistWords(val status: WordlistLoadStatus, val items: List<IndexedWord> = emptyList())
private data class IndexedWord(val id: String, val text: String, val index: IndexedText)
