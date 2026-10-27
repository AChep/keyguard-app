package com.artemchep.keyguard.common.service.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AgentApprovalBrokerTest {
    private data class Prompt(
        val id: String,
        val expiresAt: Instant,
        val label: String = "",
    )

    private fun TestScope.now() = Instant.fromEpochMilliseconds(currentTime)

    private fun TestScope.broker() = AgentApprovalBroker<Prompt>(
        idOf = { it.id },
        clock = object : Clock {
            override fun now(): Instant = this@broker.now()
        },
    )

    @Test
    fun `prompts are pending in arrival order and answered independently`() = runTest {
        val broker = broker()
        val first = async { broker.request { Prompt("a", it) } }
        val second = async { broker.request { Prompt("b", it) } }
        runCurrent()
        assertEquals(listOf("a", "b"), broker.pending.value.map { it.id })

        assertTrue(broker.respond("b", false))
        assertEquals(listOf("a"), broker.pending.value.map { it.id }, "An answer removes the prompt at once")
        assertTrue(broker.respond("a", true))

        assertEquals(AgentApprovalBroker.Result.Approved, first.await())
        assertEquals(AgentApprovalBroker.Result.Denied, second.await())
        assertTrue(broker.pending.value.isEmpty())
        assertFalse(broker.respond("a", true), "A prompt can be answered only while it is pending")
    }

    @Test
    fun `a prompt expires on the broker's deadline`() = runTest {
        val broker = broker()
        val shownAt = now()
        val result = async { broker.request { Prompt("a", it) } }
        runCurrent()
        assertEquals(shownAt + broker.promptTimeout, broker.pending.value.single().expiresAt)
        advanceTimeBy(broker.promptTimeout - 1.milliseconds)
        assertEquals(1, broker.pending.value.size)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertEquals(AgentApprovalBroker.Result.Expired, result.await())
        assertTrue(broker.pending.value.isEmpty())
    }

    @Test
    fun `the prompts of one request share its deadline`() = runTest {
        val broker = broker()
        val deadline = broker.deadline()
        val first = async { broker.request(deadline) { Prompt("a", it) } }
        runCurrent()
        advanceTimeBy(40.seconds)
        broker.respond("a", true)
        assertEquals(AgentApprovalBroker.Result.Approved, first.await())

        val second = async { broker.request(deadline) { Prompt("b", it) } }
        runCurrent()
        assertEquals(deadline, broker.pending.value.single().expiresAt, "The second prompt shows the time left")
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(AgentApprovalBroker.Result.Expired, second.await())
    }

    @Test
    fun `a cancelled caller withdraws its prompt`() = runTest {
        val broker = broker()
        val cancelled = async { broker.request { Prompt("a", it) } }
        val other = async { broker.request { Prompt("b", it) } }
        runCurrent()
        cancelled.cancelAndJoin()
        assertEquals(listOf("b"), broker.pending.value.map { it.id })
        assertFalse(broker.respond("a", true))
        broker.respond("b", true)
        assertEquals(AgentApprovalBroker.Result.Approved, other.await())
    }

    @Test
    fun `denying all prompts keeps the ones that arrive later`() = runTest {
        val broker = broker()
        val denied = async { broker.request { Prompt("a", it) } }
        runCurrent()
        broker.denyAll()
        assertTrue(broker.pending.value.isEmpty())
        assertEquals(AgentApprovalBroker.Result.Denied, denied.await())

        val later = async { broker.request { Prompt("b", it) } }
        runCurrent()
        assertEquals(listOf("b"), broker.pending.value.map { it.id })
        broker.respond("b", true)
        assertEquals(AgentApprovalBroker.Result.Approved, later.await())
    }

    @Test
    fun `updates replace the displayed data of pending prompts`() = runTest {
        val broker = broker()
        val result = async { broker.request { Prompt("a", it) } }
        runCurrent()
        broker.update { it.copy(label = "renamed") }
        assertEquals("renamed", broker.pending.value.single().label)
        broker.respond("a", true)
        assertEquals(AgentApprovalBroker.Result.Approved, result.await())
    }
}
