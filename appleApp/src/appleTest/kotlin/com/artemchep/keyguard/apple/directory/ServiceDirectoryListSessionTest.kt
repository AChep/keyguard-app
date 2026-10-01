package com.artemchep.keyguard.apple.directory

import com.artemchep.keyguard.apple.core.EntryListQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceDirectoryListSessionTest {
    @Test
    fun `same directory instances publish and route independently`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val firstFrames = mutableListOf<ServiceDirectorySnapshot>()
        val secondFrames = mutableListOf<ServiceDirectorySnapshot>()
        val firstOpened = mutableListOf<String>()
        val secondOpened = mutableListOf<String>()
        val entries = listOf(entry("Alpha"), entry("Beta"))
        val first = ServiceDirectoryListSession(
            this, MutableStateFlow(EntryListQuery()), { entries }, { firstOpened += it }, { firstFrames += it },
        )
        val second = ServiceDirectoryListSession(
            this, MutableStateFlow(EntryListQuery()), { entries }, { secondOpened += it }, { secondFrames += it },
        )
        try {
            advanceUntilIdle()
            first.setQuery("alpha")
            advanceUntilIdle()
            assertEquals(listOf("Alpha"), firstFrames.last().items.map { it.name })
            assertEquals(listOf("Alpha", "Beta"), secondFrames.last().items.map { it.name })
            assertEquals(listOf(DirectoryTextRangeSnapshot(0, 5)), firstFrames.last().items.single().highlights)
            first.open("Beta")
            second.open("Beta")
            assertTrue(firstOpened.isEmpty())
            assertEquals(listOf("Beta"), secondOpened)
            first.close()
            val frameCount = firstFrames.size
            first.setQuery("late")
            first.retry()
            second.setQuery("beta")
            advanceUntilIdle()
            assertEquals(frameCount, firstFrames.size)
            assertEquals("beta", secondFrames.last().resultQuery)
        } finally {
            first.close()
            second.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed source is exported as failure and can be retried`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = mutableListOf<ServiceDirectorySnapshot>()
        var attempts = 0
        val session = ServiceDirectoryListSession(
            this,
            MutableStateFlow(EntryListQuery()),
            { if (attempts++ == 0) error("offline") else emptyList() },
            {},
            { frames += it },
        )
        try {
            advanceUntilIdle()
            assertEquals(ServiceDirectoryLoadStatus.FAILED, frames.last().status)
            session.retry()
            advanceUntilIdle()
            assertEquals(ServiceDirectoryLoadStatus.READY, frames.last().status)
            assertTrue(frames.last().items.isEmpty())
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `new queries cancel pending results and preserve the host revision`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val query = MutableStateFlow(EntryListQuery("", 7))
        val frames = mutableListOf<ServiceDirectorySnapshot>()
        val session = ServiceDirectoryListSession(this, query,
            { listOf(entry("Alpha"), entry("Beta")) }, {}, { frames += it })
        try {
            advanceUntilIdle()
            session.setQuery("alp")
            runCurrent()
            assertTrue(frames.last().searching)
            assertEquals("", frames.last().resultQuery)
            advanceTimeBy(100)
            session.setQuery("beta")
            runCurrent()
            advanceTimeBy(88)
            runCurrent()
            assertEquals("beta", frames.last().resultQuery)
            assertEquals(7, frames.last().queryRevision)
            assertTrue(frames.none { it.resultQuery == "alp" })
            query.value = EntryListQuery("alpha", 8)
            advanceUntilIdle()
            assertEquals(8, frames.last().queryRevision)
            assertEquals("alpha", frames.last().resultQuery)
            session.close()
            val count = frames.size
            val replacementFrames = mutableListOf<ServiceDirectorySnapshot>()
            val replacement = ServiceDirectoryListSession(this, query,
                { listOf(entry("Alpha")) }, {}, { replacementFrames += it })
            try {
                advanceUntilIdle()
                assertEquals("alpha", replacementFrames.last().resultQuery)
                assertEquals(8, replacementFrames.last().queryRevision)
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
    fun `directory and detail IDs agree for duplicate and suffixed keys`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entries = listOf(entry("Gamma").copy(key = "a#1"), entry("Beta").copy(key = "a"),
            entry("Alpha").copy(key = "a"))
        val frames = mutableListOf<ServiceDirectorySnapshot>()
        val session = ServiceDirectoryListSession(this, MutableStateFlow(EntryListQuery()),
            { entries }, {}, { frames += it })
        try {
            advanceUntilIdle()
            assertEquals(listOf("a", "a#1", "a#1#1"), frames.last().items.map { it.id })
            val details = directoryRows(entries).associate { it.id to it.entry.detail.title }
            assertEquals(listOf("Alpha", "Beta", "Gamma"), frames.last().items.map { details[it.id] })
            session.retry()
            advanceUntilIdle()
            assertEquals(details.keys.toList(), frames.last().items.map { it.id })
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `ranked search keeps repeated section headings distinct and stable`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entries = (1..26).map { i -> entry("${if (i % 2 == 0) 'B' else 'A'} ${"x".repeat(i)} needle") }
        val frames = mutableListOf<ServiceDirectorySnapshot>()
        val session = ServiceDirectoryListSession(this, MutableStateFlow(EntryListQuery("needle")),
            { entries }, {}, { frames += it })
        try {
            advanceUntilIdle()
            val rows = frames.last().items
            assertEquals(
                entries.map { it.name },
                rows.filter { it.kind == ServiceDirectoryItemKind.CONTENT }.map { it.name },
            )
            assertEquals(26, rows.count { it.kind == ServiceDirectoryItemKind.SECTION })
            assertEquals(rows.size, rows.map { it.id }.toSet().size)
            session.retry()
            advanceUntilIdle()
            assertEquals(rows, frames.last().items)
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `parent cancellation stops an in flight catalog and rejects commands`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val parent = Job(coroutineContext[Job])
        val query = MutableStateFlow(EntryListQuery())
        val loaded = CompletableDeferred<List<DirectoryEntry>>()
        val frames = mutableListOf<ServiceDirectorySnapshot>()
        val session = ServiceDirectoryListSession(CoroutineScope(coroutineContext + parent), query,
            { loaded.await() }, { error("Must not open after cancellation") }, { frames += it })
        try {
            runCurrent()
            parent.cancel()
            val count = frames.size
            loaded.complete(listOf(entry("Late")))
            session.setQuery("late")
            session.retry()
            session.open("Late")
            advanceUntilIdle()
            assertEquals(count, frames.size)
            assertEquals("", query.value.text)
        } finally {
            session.close()
            parent.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `rows resolve the favicon url from the entry host`() {
        fun favicon(url: String?) = directoryRows(listOf(entry("Alpha", url))).single().snapshot().faviconUrl
        assertEquals("https://icons.duckduckgo.com/ip3/example.com.ico", favicon("https://example.com/docs?q=1"))
        assertEquals("https://icons.duckduckgo.com/ip3/example.com.ico", favicon("example.com/docs"))
        assertNull(favicon("http://localhost/docs"))
        assertNull(favicon(null))
    }

    private fun entry(name: String, faviconUrl: String? = null) = DirectoryEntry(
        key = name,
        name = name,
        faviconUrl = faviconUrl,
        detail = ServiceDirectoryDetailSnapshot(true, name, emptyList(), null, emptyList()),
    )
}
