package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AgentApprovalCacheConfigStateTest {
    @Test
    fun `successful policy and window writes publish versioned config`() = runTest {
        val persisted = mutableListOf<String>()
        val state = createState()

        assertEquals(
            AgentApprovalCacheConfig(
                approvalWindow = 5.minutes,
                cachePolicy = TestPolicy.Application,
                revision = 0L,
            ),
            state.get(),
        )

        state.updateCachePolicy(
            cachePolicy = TestPolicy.Connection,
            persist = { persisted += "policy" },
        )()
        assertEquals(
            AgentApprovalCacheConfig(
                approvalWindow = 5.minutes,
                cachePolicy = TestPolicy.Connection,
                revision = 1L,
            ),
            state.get(),
        )

        state.updateApprovalWindow(
            approvalWindow = 15.minutes,
            persist = { persisted += "window" },
        )()
        assertEquals(
            AgentApprovalCacheConfig(
                approvalWindow = 15.minutes,
                cachePolicy = TestPolicy.Connection,
                revision = 2L,
            ),
            state.get(),
        )
        assertEquals(15.minutes, state.approvalWindow().first())
        assertEquals(TestPolicy.Connection, state.cachePolicy().first())
        assertEquals(listOf("policy", "window"), persisted)
    }

    @Test
    fun `policy away and back advances revision twice`() = runTest {
        val state = createState()
        state.get()

        state.updateCachePolicy(
            cachePolicy = TestPolicy.Connection,
            persist = {},
        )()
        state.updateCachePolicy(
            cachePolicy = TestPolicy.Application,
            persist = {},
        )()

        assertEquals(
            AgentApprovalCacheConfig(
                approvalWindow = 5.minutes,
                cachePolicy = TestPolicy.Application,
                revision = 2L,
            ),
            state.get(),
        )
    }

    @Test
    fun `setter before first get loads and publishes config`() = runTest {
        var approvalWindowLoads = 0
        var cachePolicyLoads = 0
        var persisted = false
        val state = AgentApprovalCacheConfigState(
            loadApprovalWindow = {
                approvalWindowLoads += 1
                5.minutes
            },
            loadCachePolicy = {
                cachePolicyLoads += 1
                TestPolicy.Application
            },
        )

        state.updateCachePolicy(
            cachePolicy = TestPolicy.Connection,
            persist = { persisted = true },
        )()

        assertTrue(persisted)
        assertEquals(1, approvalWindowLoads)
        assertEquals(1, cachePolicyLoads)
        assertEquals(
            AgentApprovalCacheConfig(
                approvalWindow = 5.minutes,
                cachePolicy = TestPolicy.Connection,
                revision = 1L,
            ),
            state.get(),
        )
        assertEquals(1, approvalWindowLoads)
        assertEquals(1, cachePolicyLoads)
    }

    @Test
    fun `read waits for an in-flight write and observes its published config`() = runTest {
        val state = createState()
        state.get()
        val persistenceStarted = CompletableDeferred<Unit>()
        val finishPersistence = CompletableDeferred<Unit>()

        val update = async {
            state.updateCachePolicy(
                cachePolicy = TestPolicy.Connection,
                persist = {
                    persistenceStarted.complete(Unit)
                    finishPersistence.await()
                },
            )()
        }
        persistenceStarted.await()

        val read = async { state.get() }
        yield()
        assertFalse(read.isCompleted)

        finishPersistence.complete(Unit)
        update.await()
        assertEquals(TestPolicy.Connection, read.await().cachePolicy)
    }

    @Test
    fun `persistence failure preserves values and invalidates approvals`() = runTest {
        val state = createState()
        val initial = state.get()

        assertFailsWith<PersistenceException> {
            state.updateCachePolicy(
                cachePolicy = TestPolicy.Connection,
                persist = { throw PersistenceException() },
            )()
        }
        assertEquals(
            initial.copy(revision = initial.revision + 1L),
            state.get(),
        )

        assertFailsWith<PersistenceException> {
            state.updateApprovalWindow(
                approvalWindow = 15.minutes,
                persist = { throw PersistenceException() },
            )()
        }
        assertEquals(
            initial.copy(revision = initial.revision + 2L),
            state.get(),
        )
    }

    private fun createState() = AgentApprovalCacheConfigState(
        loadApprovalWindow = { 5.minutes },
        loadCachePolicy = { TestPolicy.Application },
    )

    private enum class TestPolicy {
        Connection,
        Application,
    }

    private class PersistenceException : RuntimeException()
}
