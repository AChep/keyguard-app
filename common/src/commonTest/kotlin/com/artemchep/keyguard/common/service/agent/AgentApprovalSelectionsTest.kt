package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.agent.AgentApprovalSelections.Answer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AgentApprovalSelectionsTest {
    private data class Prompt(
        val id: String,
        val expiresAt: Instant,
    )

    /** A real broker, and the selections of its prompts. */
    private class Prompts(
        private val scope: TestScope,
    ) {
        val broker = AgentApprovalBroker<Prompt>(
            idOf = { it.id },
            clock = object : Clock {
                override fun now(): Instant = Instant.fromEpochMilliseconds(scope.currentTime)
            },
        )

        val selections = AgentApprovalSelections(isPending = { id -> broker.pending.value.any { it.id == id } })

        /** Shows the prompt [id] with one choice per flag of [requiresIdentity]. */
        fun ask(id: String, vararg requiresIdentity: Boolean): Deferred<Answer> = scope.async {
            selections.track(id, requiresIdentity.toList()) {
                broker.request { Prompt(id, it) }
            }
        }
    }

    @Test
    fun `the first choice is selected`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", false, true)
        runCurrent()
        assertTrue(prompts.broker.respond("a", true))
        assertEquals(Answer.Approved(choice = 0, identityConfirmed = false), answer.await())
    }

    @Test
    fun `approving a choice that requires the identity without confirming it denies the prompt`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", true)
        runCurrent()
        prompts.broker.respond("a", true)
        assertEquals(Answer.Denied, answer.await())
    }

    @Test
    fun `a confirmed identity approves the selected choice`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", false, true)
        runCurrent()
        assertTrue(prompts.selections.select("a", 1))
        assertTrue(prompts.selections.confirmIdentity("a") { true })
        prompts.broker.respond("a", true)
        assertEquals(Answer.Approved(choice = 1, identityConfirmed = true), answer.await())
    }

    @Test
    fun `a failed identity check confirms nothing`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", true)
        runCurrent()
        assertFalse(prompts.selections.confirmIdentity("a") { false })
        prompts.broker.respond("a", true)
        assertEquals(Answer.Denied, answer.await())
    }

    @Test
    fun `selecting another choice drops the confirmed identity`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", true, true)
        runCurrent()
        assertTrue(prompts.selections.confirmIdentity("a") { true })
        assertTrue(prompts.selections.select("a", 1))
        prompts.broker.respond("a", true)
        assertEquals(Answer.Denied, answer.await())
    }

    @Test
    fun `selecting the same choice keeps the confirmed identity`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", true, true)
        runCurrent()
        assertTrue(prompts.selections.confirmIdentity("a") { true })
        assertTrue(prompts.selections.select("a", 0))
        prompts.broker.respond("a", true)
        assertEquals(Answer.Approved(choice = 0, identityConfirmed = true), answer.await())
    }

    @Test
    fun `a selection changed during the identity check fails the confirmation`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", true, true)
        runCurrent()
        val verified = CompletableDeferred<Boolean>()
        val confirmed = async { prompts.selections.confirmIdentity("a") { verified.await() } }
        runCurrent()
        assertTrue(prompts.selections.select("a", 1))
        verified.complete(true)
        assertFalse(confirmed.await())

        prompts.broker.respond("a", true)
        assertEquals(Answer.Denied, answer.await())
    }

    @Test
    fun `a prompt ended during the identity check fails the confirmation`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", true)
        runCurrent()
        val verified = CompletableDeferred<Boolean>()
        val confirmed = async { prompts.selections.confirmIdentity("a") { verified.await() } }
        runCurrent()
        verified.complete(true)
        // Ends the prompt before the identity check resumes.
        assertTrue(prompts.broker.respond("a", true))
        assertFalse(confirmed.await())
        assertEquals(Answer.Denied, answer.await())
    }

    @Test
    fun `a choice that requires no identity needs no identity check`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", false, true)
        runCurrent()
        var verifyCalls = 0
        val confirmed = prompts.selections.confirmIdentity("a") {
            verifyCalls++
            true
        }
        assertTrue(confirmed)
        assertEquals(0, verifyCalls)
        prompts.broker.respond("a", true)
        assertEquals(Answer.Approved(choice = 0, identityConfirmed = false), answer.await())
    }

    @Test
    fun `a choice out of range is not selected`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", false, true)
        runCurrent()
        assertFalse(prompts.selections.select("a", 2))
        assertFalse(prompts.selections.select("a", -1))
        prompts.broker.respond("a", true)
        assertEquals(Answer.Approved(choice = 0, identityConfirmed = false), answer.await())
    }

    @Test
    fun `an ended prompt accepts no input`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", true, true)
        runCurrent()
        prompts.broker.respond("a", false)
        // Answered, but its request has not returned yet.
        assertFalse(prompts.selections.select("a", 1))
        assertFalse(prompts.selections.confirmIdentity("a") { true })

        assertEquals(Answer.Denied, answer.await())
        assertFalse(prompts.selections.select("a", 1))
        assertFalse(prompts.selections.confirmIdentity("a") { true })
        assertFalse(prompts.selections.select("unknown", 0))
        assertFalse(prompts.selections.confirmIdentity("unknown") { true })
    }

    @Test
    fun `a cancelled request leaves no state`() = runTest {
        val prompts = Prompts(this)
        prompts.ask("a", true, true).also { runCurrent() }.cancelAndJoin()

        // A pending prompt with the same id that is not tracked.
        val untracked = async { prompts.broker.request { Prompt("a", it) } }
        runCurrent()
        assertFalse(prompts.selections.select("a", 1))
        assertFalse(prompts.selections.confirmIdentity("a") { true })
        prompts.broker.respond("a", true)
        assertEquals(AgentApprovalBroker.Result.Approved, untracked.await())
    }

    @Test
    fun `an expired prompt is expired`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", false)
        runCurrent()
        advanceTimeBy(AGENT_APPROVAL_PROMPT_TIMEOUT)
        runCurrent()
        assertEquals(Answer.Expired, answer.await())
    }

    @Test
    fun `a denied prompt is denied`() = runTest {
        val prompts = Prompts(this)
        val answer = prompts.ask("a", false)
        runCurrent()
        prompts.broker.respond("a", false)
        assertEquals(Answer.Denied, answer.await())
    }

    @Test
    fun `a prompt has at least one choice`() = runTest {
        val selections = AgentApprovalSelections(isPending = { true })
        assertFailsWith<IllegalArgumentException> {
            selections.track("a", emptyList()) { AgentApprovalBroker.Result.Approved }
        }
    }
}
