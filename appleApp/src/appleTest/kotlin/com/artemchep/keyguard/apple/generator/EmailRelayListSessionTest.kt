package com.artemchep.keyguard.apple.generator

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import kotlinx.collections.immutable.persistentMapOf
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmailRelayListSessionTest {
    @Test
    fun `large lists publish coherent selection independently of another entry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rows = MutableStateFlow((1..120).map { entry(it.toString()) })
        val firstFrames = mutableListOf<EmailRelayListSnapshot>()
        val secondFrames = mutableListOf<EmailRelayListSnapshot>()
        val first = EmailRelayListSession(this, { rows }, mapOf("provider" to "Provider"),
            MutableStateFlow(emptySet()), { it.name }, { firstFrames += it })
        val second = EmailRelayListSession(this, { rows }, mapOf("provider" to "Provider"),
            MutableStateFlow(emptySet()), { it.name }, { secondFrames += it })
        try {
            advanceUntilIdle()
            first.toggleSelection("1")
            first.toggleSelection("2")
            advanceUntilIdle()
            val frame = firstFrames.last()
            assertEquals(2, frame.selectionCount)
            assertEquals(listOf("1", "2"), frame.items.filter { it.selected }.map { it.id })
            assertEquals(0, secondFrames.last().selectionCount)
            val request = first.requestAction(EmailRelayActionKind.DELETE.actionId, null)!!
            first.selectAll()
            advanceUntilIdle()
            assertEquals(120, firstFrames.last().selectionCount)
            rows.value = rows.value.drop(1)
            advanceUntilIdle()
            assertEquals(119, firstFrames.last().selectionCount)
            assertEquals(listOf("1", "2"), request.items.map { it.id })
            assertNull(first.requestAction(EmailRelayActionKind.DELETE.actionId, "1"))
            first.close()
            val frameCount = firstFrames.size
            first.retry()
            first.clearSelection()
            assertNull(first.requestAction(EmailRelayActionKind.DUPLICATE.actionId, "2"))
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
    fun `exported actions and commands agree for an unknown provider`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rows = MutableStateFlow(listOf(entry("unknown").copy(type = "unknown")))
        val frames = mutableListOf<EmailRelayListSnapshot>()
        val session = EmailRelayListSession(this, { rows }, mapOf("provider" to "Provider"),
            MutableStateFlow(emptySet()), { it.name }, { frames += it })
        try {
            advanceUntilIdle()
            assertEquals(listOf(EmailRelayActionKind.DUPLICATE.actionId, EmailRelayActionKind.DELETE.actionId),
                frames.last().items.single().actions.map { it.id })
            assertNull(session.requestAction(EmailRelayActionKind.EDIT.actionId, "unknown"))
            assertNull(session.requestAction("invalid", "unknown"))
            assertEquals(EmailRelayActionKind.DUPLICATE,
                session.requestAction(EmailRelayActionKind.DUPLICATE.actionId, "unknown")?.kind)
            rows.value = listOf(entry("unknown").copy(name = "Updated"))
            advanceUntilIdle()
            val edit = session.requestAction(EmailRelayActionKind.EDIT.actionId, "unknown")!!
            assertEquals(EmailRelayActionKind.EDIT, edit.kind)
            assertEquals("Updated", edit.items.single().name)
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed load retries and replacement retains selection without late publications`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fail = true
        val selected = MutableStateFlow(setOf("a"))
        val source = { flow { if (fail) error("offline") else emit(listOf(entry("a"))) } }
        val frames = mutableListOf<EmailRelayListSnapshot>()
        val session = EmailRelayListSession(this, source, mapOf("provider" to "Provider"), selected,
            { it.name }, { frames += it })
        try {
            advanceUntilIdle()
            assertEquals(EmailRelayLoadStatus.FAILED, frames.last().status)
            fail = false
            session.retry()
            advanceUntilIdle()
            assertEquals(EmailRelayLoadStatus.READY, frames.last().status)
            assertEquals(1, frames.last().selectionCount)
            session.close()
            val frameCount = frames.size
            val replacementFrames = mutableListOf<EmailRelayListSnapshot>()
            val replacement = EmailRelayListSession(this, source, mapOf("provider" to "Provider"), selected,
                { it.name }, { replacementFrames += it })
            try {
                advanceUntilIdle()
                assertEquals(1, replacementFrames.last().selectionCount)
                assertEquals(frameCount, frames.size)
            } finally {
                replacement.close()
            }
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `invalid IDs are omitted and row snapshots contain metadata only`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rows = listOf(entry("known").copy(data = persistentMapOf("token" to "secret-token")),
            entry("unknown").copy(type = "removed-provider"), entry(""), entry("missing").copy(id = null))
        val frames = mutableListOf<EmailRelayListSnapshot>()
        val session = EmailRelayListSession(this, { flowOf(rows) }, mapOf("provider" to "Provider"),
            MutableStateFlow(emptySet()), { it.name }, { frames += it })
        try {
            advanceUntilIdle()
            assertEquals(listOf("known", "unknown"), frames.last().items.map { it.id })
            assertEquals(listOf("Provider", "removed-provider"), frames.last().items.map { it.service })
            assertTrue("secret-token" !in frames.last().toString())
            session.toggleSelection("unknown")
            assertNull(session.requestAction(EmailRelayActionKind.EDIT.actionId, null))
            assertEquals(
                listOf("unknown"),
                session.requestAction(EmailRelayActionKind.DELETE.actionId, null)?.items?.map { it.id },
            )
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `removed selections stay pruned on reinsertion and cancellation rejects commands`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val parent = Job(coroutineContext[Job])
        val rows = MutableStateFlow(listOf(entry("a"), entry("b")))
        val selected = MutableStateFlow(emptySet<String>())
        val frames = mutableListOf<EmailRelayListSnapshot>()
        val session = EmailRelayListSession(CoroutineScope(coroutineContext + parent), { rows },
            mapOf("provider" to "Provider"), selected, { it.name }, { frames += it })
        try {
            advanceUntilIdle()
            session.toggleSelection("a")
            session.toggleSelection("missing")
            assertEquals(setOf("a"), selected.value)
            rows.value = listOf(entry("b"))
            advanceUntilIdle()
            rows.value = listOf(entry("a"), entry("b"))
            advanceUntilIdle()
            assertEquals(0, frames.last().selectionCount)
            session.selectAll()
            advanceUntilIdle()
            session.clearSelection()
            advanceUntilIdle()
            assertEquals(0, frames.last().selectionCount)
            parent.cancel()
            val count = frames.size
            session.selectAll()
            session.toggleSelection("b")
            session.retry()
            assertNull(session.requestAction(EmailRelayActionKind.DELETE.actionId, "b"))
            rows.value = emptyList()
            advanceUntilIdle()
            assertEquals(emptySet(), selected.value)
            assertEquals(count, frames.size)
        } finally {
            session.close()
            parent.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun entry(id: String) =
        DGeneratorEmailRelay(id, "Forwarder $id", "provider", persistentMapOf(), Instant.fromEpochSeconds(0))
}
