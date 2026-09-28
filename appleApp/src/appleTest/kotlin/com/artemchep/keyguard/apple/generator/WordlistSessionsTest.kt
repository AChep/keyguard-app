package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.apple.core.EntryListQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WordlistSessionsTest {
    @Test
    fun `selection frames and action requests stay isolated between owners`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MutableStateFlow(
            (1L..120L).map { DGeneratorWordlist(it, "List $it", it, Instant.fromEpochSeconds(0)) },
        )
        val firstFrames = mutableListOf<WordlistListSnapshot>()
        val secondFrames = mutableListOf<WordlistListSnapshot>()
        val first = WordlistListSession(this, { source }, MutableStateFlow(emptySet()),
            { "${it.wordCount} words" }, { it }, {}, { firstFrames += it })
        val second = WordlistListSession(this, { source }, MutableStateFlow(emptySet()),
            { "${it.wordCount} words" }, { it }, {}, { secondFrames += it })
        try {
            advanceUntilIdle()
            first.toggleSelection("1")
            first.toggleSelection("2")
            advanceUntilIdle()
            val frame = firstFrames.last()
            assertEquals(2, frame.selectionCount)
            assertEquals(listOf("1", "2"), frame.items.filter { it.selected }.map { it.id })
            assertTrue(frame.items.all { it.selecting })
            assertEquals(0, secondFrames.last().selectionCount)
            assertNull(first.requestAction("wordlist.selection.edit", null))
            val request = first.requestAction("wordlist.selection.delete", null)!!
            source.value = source.value.drop(1)
            advanceUntilIdle()
            assertEquals(1, firstFrames.last().selectionCount)
            assertEquals(listOf(1L, 2L), request.items.map { it.id })
            assertNull(first.requestAction("wordlist.selection.delete", "1"))
            first.close()
            val frameCount = firstFrames.size
            first.selectAll()
            first.retry()
            assertNull(first.requestAction("wordlist.selection.delete", "2"))
            second.toggleSelection("2")
            advanceUntilIdle()
            assertEquals(frameCount, firstFrames.size)
            assertEquals(1, secondFrames.last().selectionCount)
        } finally {
            first.close()
            second.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `detail sessions use shared matching live metadata and independent query stores`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MutableStateFlow(listOf(DGeneratorWordlist(1, "Words", 3, Instant.fromEpochSeconds(0))))
        val words = MutableStateFlow(listOf("😀 apple", "pear", "😀 apple"))
        val getWordlists = object : GetWordlists { override fun invoke() = source }
        val getWords = object : GetWordlistPrimitive { override fun invoke(id: Long) = words }
        val firstFrames = mutableListOf<WordlistDetailSnapshot>()
        val secondFrames = mutableListOf<WordlistDetailSnapshot>()
        val first = WordlistDetailSession(this, 1, getWordlists, getWords,
            MutableStateFlow(EntryListQuery()), { firstFrames += it })
        val second = WordlistDetailSession(this, 1, getWordlists, getWords,
            MutableStateFlow(EntryListQuery()), { secondFrames += it })
        try {
            advanceUntilIdle()
            first.setQuery("apple")
            advanceUntilIdle()
            assertEquals(2, firstFrames.last().words.size)
            assertEquals(2, firstFrames.last().words.map { it.id }.toSet().size)
            assertEquals(listOf(WordlistTextRangeSnapshot(3, 8)), firstFrames.last().words.first().highlights)
            assertEquals(3, secondFrames.last().words.size)
            source.value = listOf(source.value.single().copy(name = "Renamed"))
            advanceUntilIdle()
            assertEquals("Renamed", firstFrames.last().title)
            first.close()
            val frameCount = firstFrames.size
            first.setQuery("late")
            source.value = emptyList()
            advanceUntilIdle()
            assertEquals(frameCount, firstFrames.size)
            assertTrue(secondFrames.last().notFound)
            assertTrue(secondFrames.last().words.isEmpty())
        } finally {
            first.close()
            second.close()
            Dispatchers.resetMain()
        }
    }
    @Test
    fun `list retries preserve selection and actions capture current metadata before cancellation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val parent = Job(coroutineContext[Job])
        val rows = MutableStateFlow(listOf(wordlist()))
        var fail = true
        val source = { flow { if (fail) error("offline") else rows.collect { emit(it) } } }
        val selected = MutableStateFlow(setOf("1"))
        val frames = mutableListOf<WordlistListSnapshot>()
        val opened = mutableListOf<Long>()
        val session = WordlistListSession(CoroutineScope(coroutineContext + parent), source, selected,
            { "${it.wordCount}" }, { it }, { opened += it.idRaw }, { frames += it })
        try {
            advanceUntilIdle()
            assertEquals(WordlistLoadStatus.FAILED, frames.last().status)
            assertEquals(setOf("1"), selected.value)
            fail = false
            session.retry()
            advanceUntilIdle()
            assertEquals(1, frames.last().selectionCount)
            assertEquals(
                listOf(WORDLIST_ACTION_RENAME, WORDLIST_ACTION_DELETE),
                frames.last().selectionActions.map { it.id },
            )
            val request = session.requestAction(WORDLIST_ACTION_RENAME, null)!!
            rows.value = listOf(wordlist().copy(name = "Updated"))
            advanceUntilIdle()
            assertEquals("Words", request.items.single().name)
            assertEquals("Updated", session.requestAction(WORDLIST_ACTION_RENAME, "1")?.items?.single()?.name)
            session.open("1")
            session.open("missing")
            assertEquals(listOf(1L), opened)
            session.clearSelection()
            session.toggleSelection("missing")
            advanceUntilIdle()
            assertEquals(0, frames.last().selectionCount)
            session.selectAll()
            advanceUntilIdle()
            assertEquals(1, frames.last().selectionCount)
            parent.cancel()
            val count = frames.size
            session.clearSelection()
            session.retry()
            session.open("1")
            assertNull(session.requestAction(WORDLIST_ACTION_DELETE, "1"))
            rows.value = emptyList()
            advanceUntilIdle()
            assertEquals(count, frames.size)
            assertEquals(listOf(1L), opened)
        } finally {
            session.close()
            parent.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `metadata and word failures retry independently and empty differs from missing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var metadataFails = true
        var wordsFail = false
        val metadata = MutableStateFlow(listOf(wordlist()))
        val words = MutableStateFlow(emptyList<String>())
        val getWordlists = object : GetWordlists {
            override fun invoke() = flow { if (metadataFails) error("metadata") else metadata.collect { emit(it) } }
        }
        val getWords = object : GetWordlistPrimitive {
            override fun invoke(id: Long) = flow { if (wordsFail) error("words") else words.collect { emit(it) } }
        }
        val query = MutableStateFlow(EntryListQuery("apple", 3))
        val frames = mutableListOf<WordlistDetailSnapshot>()
        val session = WordlistDetailSession(this, 1, getWordlists, getWords, query, { frames += it })
        try {
            advanceUntilIdle()
            assertEquals(WordlistLoadStatus.FAILED, frames.last().status)
            metadataFails = false
            wordsFail = true
            session.retry()
            advanceUntilIdle()
            assertEquals(WordlistLoadStatus.FAILED, frames.last().status)
            wordsFail = false
            session.retry()
            advanceUntilIdle()
            assertEquals(WordlistLoadStatus.READY, frames.last().status)
            assertEquals(false, frames.last().notFound)
            assertEquals("apple", frames.last().query)
            assertEquals(3, frames.last().queryRevision)
            assertTrue(frames.last().words.isEmpty())
            words.value = listOf("apple", "pear")
            advanceUntilIdle()
            assertEquals(listOf("apple"), frames.last().words.map { it.text })
            metadata.value = emptyList()
            advanceUntilIdle()
            assertTrue(frames.last().notFound)
            assertTrue(frames.last().words.isEmpty())
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `detail cancels obsolete search and replacement keeps the host query`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val getWordlists = object : GetWordlists { override fun invoke() = MutableStateFlow(listOf(wordlist())) }
        val getWords = object : GetWordlistPrimitive {
            override fun invoke(id: Long) = MutableStateFlow(listOf("apple", "pear", "pear", "pear#1"))
        }
        val query = MutableStateFlow(EntryListQuery())
        val frames = mutableListOf<WordlistDetailSnapshot>()
        val session = WordlistDetailSession(this, 1, getWordlists, getWords, query, { frames += it })
        try {
            advanceUntilIdle()
            assertEquals(listOf("apple", "pear", "pear#1", "pear#1#1"), frames.last().words.map { it.id })
            session.setQuery("app")
            runCurrent()
            advanceTimeBy(100)
            session.setQuery("pear")
            advanceUntilIdle()
            assertTrue(frames.none { it.resultQuery == "app" })
            assertEquals("pear", frames.last().resultQuery)
            query.value = EntryListQuery("apple", 4)
            advanceUntilIdle()
            session.close()
            val count = frames.size
            val replacementFrames = mutableListOf<WordlistDetailSnapshot>()
            val replacement = WordlistDetailSession(this, 1, getWordlists, getWords, query, { replacementFrames += it })
            try {
                advanceUntilIdle()
                assertEquals("apple", replacementFrames.last().resultQuery)
                assertEquals(4, replacementFrames.last().queryRevision)
                assertEquals(count, frames.size)
            } finally {
                replacement.close()
            }
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `action targets follow live rows while a localized snapshot is suspended`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val counterReady = CompletableDeferred<Unit>()
        val rows = MutableStateFlow(listOf(wordlist()))
        val frames = mutableListOf<WordlistListSnapshot>()
        var blockCounter = false
        val counter: suspend (DGeneratorWordlist) -> String = {
            if (blockCounter) counterReady.await()
            "${it.wordCount}"
        }
        val session = WordlistListSession(this, { rows }, MutableStateFlow(emptySet()),
            counter, { it }, {}, { frames += it })
        try {
            advanceUntilIdle()
            blockCounter = true
            rows.value = listOf(wordlist().copy(name = "Renamed"))
            runCurrent()
            assertEquals("Words", frames.last().items.single().title)
            assertEquals("Renamed", session.requestAction(WORDLIST_ACTION_RENAME, "1")?.items?.single()?.name)
            rows.value = emptyList()
            runCurrent()
            assertNull(session.requestAction(WORDLIST_ACTION_DELETE, "1"))
            counterReady.complete(Unit)
            advanceUntilIdle()
            assertTrue(frames.last().items.isEmpty())
        } finally {
            counterReady.complete(Unit)
            session.close()
            Dispatchers.resetMain()
        }
    }

    private fun wordlist() = DGeneratorWordlist(1, "Words", 3, Instant.fromEpochSeconds(0))

}
