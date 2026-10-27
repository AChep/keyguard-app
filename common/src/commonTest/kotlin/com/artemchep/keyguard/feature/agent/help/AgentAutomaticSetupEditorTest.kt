package com.artemchep.keyguard.feature.agent.help

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentAutomaticSetupEditorTest {
    @Test
    fun `host and shell validation precedes review and back preserves choices`() = runTest {
        val fixture = Fixture(this)
        val editor = fixture.editor
        assertTrue(editor.state.value.canContinue)
        editor.setOneHost(true)
        listOf("", "*.example.com", "host\nHost *", "host name").forEach {
            editor.setHost(it)
            assertFalse(editor.state.value.canContinue)
        }
        editor.setHost("git.example.com")
        editor.toggleShell(SetupShell.ZSH)
        editor.next()
        advanceUntilIdle()
        assertEquals(setOf(SetupShell.ZSH) to "git.example.com", fixture.client.request)
        assertTrue(fixture.client.applied.isEmpty())
        editor.back()
        assertEquals(AgentSetupStep.SETTINGS, editor.state.value.step)
        assertEquals("git.example.com", editor.state.value.host)
        assertEquals(setOf(SetupShell.ZSH), editor.state.value.selectedShells)
        assertNull(editor.state.value.plan)
        editor.back()
        assertTrue(editor.state.value.confirmingDiscard)
        editor.confirmDiscard(false)
        assertEquals(0, fixture.closed)
        editor.back()
        editor.confirmDiscard(true)
        assertEquals(1, fixture.closed)
    }

    @Test
    fun `GPG requires a shell and Termux begins with Bash`() = runTest {
        val gpg = Fixture(this, defaults.copy(agent = SetupAgent.GPG))
        assertFalse(gpg.editor.state.value.canContinue)
        gpg.editor.next()
        assertNull(gpg.client.request)
        gpg.editor.toggleShell(SetupShell.BASH)
        assertTrue(gpg.editor.state.value.canContinue)
        val termux = Fixture(this, defaults.copy(external = true, selectedShells = persistentSetOf(SetupShell.BASH)))
        assertTrue(termux.editor.state.value.canContinue)
        termux.editor.next()
        assertEquals("keyguard-android-ssh-agent --setup-shells bash", termuxSetupCommand(termux.editor.state.value))
        termux.editor.next()
        assertEquals(AgentSetupStep.RESULT, termux.editor.state.value.step)
        assertFalse(termux.editor.state.value.applied)
        assertNull(termux.editor.state.value.verified)
        assertNull(termux.client.request)
    }

    @Test
    fun `late review cannot replace a newer selection and plan`() = runTest {
        val fixture = Fixture(this)
        val pending = CompletableDeferred<AgentSetupPlan>()
        fixture.client.previewBlock = { withContext(NonCancellable) { pending.await() } }
        fixture.editor.next()
        runCurrent()
        fixture.editor.back()
        fixture.editor.toggleShell(SetupShell.BASH)
        fixture.client.previewBlock = { plan }
        fixture.editor.next()
        runCurrent()
        pending.complete(plan.copy(conflicts = persistentListOf(conflict)))
        advanceUntilIdle()
        assertSame(plan, fixture.editor.state.value.plan)
        assertTrue(fixture.editor.state.value.canContinue)
    }

    @Test
    fun `conflicts and failed reviews block apply until refreshed`() = runTest {
        val fixture = Fixture(this)
        fixture.client.previewBlock = { error("Helper unavailable") }
        fixture.editor.next()
        advanceUntilIdle()
        assertFalse(fixture.editor.state.value.canContinue)
        assertEquals(
            AgentSetupError(AgentSetupError.Kind.FAILED, detail = "Helper unavailable"),
            fixture.editor.state.value.error,
        )
        assertEquals(AgentSetupReviewStatus.FAILED, fixture.editor.state.value.reviewStatus)
        val busy = AgentSetupError(AgentSetupError.Kind.BUSY, detail = "another agent setup operation is running")
        fixture.client.previewBlock = { throw AgentSetupException(busy) }
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertEquals(busy, fixture.editor.state.value.error)
        fixture.client.previewBlock = { plan.copy(conflicts = persistentListOf(conflict)) }
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertEquals(AgentSetupReviewStatus.CONFLICTS, fixture.editor.state.value.reviewStatus)
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertEquals(AgentSetupReviewStatus.CONFLICTS, fixture.editor.state.value.reviewStatus)
        fixture.editor.next()
        assertTrue(fixture.client.applied.isEmpty())
        fixture.client.previewBlock = { plan }
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertTrue(fixture.editor.state.value.canContinue)
        assertEquals(AgentSetupReviewStatus.READY, fixture.editor.state.value.reviewStatus)
        assertTrue(fixture.client.applied.isEmpty())
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertEquals(listOf(plan), fixture.client.applied)
    }

    @Test
    fun `apply locks navigation and applies the reviewed plan only once`() = runTest {
        val fixture = Fixture(this)
        val pending = CompletableDeferred<Unit>()
        fixture.client.applyBlock = { pending.await() }
        fixture.editor.next()
        advanceUntilIdle()
        fixture.editor.next()
        runCurrent()
        fixture.editor.next()
        fixture.editor.back()
        fixture.editor.refresh()
        fixture.editor.toggleShell(SetupShell.BASH)
        assertTrue(fixture.editor.state.value.mutating)
        assertEquals(0, fixture.closed)
        assertEquals(listOf(plan), fixture.client.applied)
        assertTrue(fixture.editor.state.value.selectedShells.isEmpty())
        pending.complete(Unit)
        advanceUntilIdle()
        assertEquals(AgentSetupStep.RESULT, fixture.editor.state.value.step)
        assertTrue(fixture.editor.state.value.applied)
        assertEquals(true, fixture.editor.state.value.verified)
        assertEquals(1, fixture.client.verifications)
    }

    @Test
    fun `failed apply requires a fresh review`() = runTest {
        val fixture = Fixture(this)
        fixture.client.applyBlock = { error("File changed since review") }
        fixture.editor.next()
        advanceUntilIdle()
        fixture.editor.next()
        advanceUntilIdle()
        assertNull(fixture.editor.state.value.plan)
        assertFalse(fixture.editor.state.value.applied)
        assertFalse(fixture.editor.state.value.canContinue)
        assertEquals(0, fixture.client.verifications)
        fixture.editor.next()
        assertEquals(1, fixture.client.applied.size)
        assertEquals(AgentSetupReviewStatus.FAILED, fixture.editor.state.value.reviewStatus)
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertTrue(fixture.editor.state.value.canContinue)
        assertEquals(1, fixture.client.applied.size)
        assertNull(fixture.editor.state.value.error)
    }

    @Test
    fun `missing review can be recovered but busy disabled and discard states cannot refresh`() = runTest {
        val fixture = Fixture(this, defaults.copy(step = AgentSetupStep.REVIEW))
        assertEquals(AgentSetupReviewStatus.REQUIRED, fixture.editor.state.value.reviewStatus)
        val pending = CompletableDeferred<AgentSetupPlan>()
        var reviews = 0
        fixture.client.previewBlock = { reviews += 1; pending.await() }
        val recovery = assertNotNull(fixture.presentation.onPrimaryAction)
        recovery()
        recovery()
        runCurrent()
        assertEquals(1, reviews)
        assertEquals(AgentSetupReviewStatus.REVIEWING, fixture.editor.state.value.reviewStatus)
        assertNull(fixture.presentation.onPrimaryAction)
        pending.complete(plan)
        advanceUntilIdle()
        assertTrue(fixture.client.applied.isEmpty())
        fixture.editor.setEnabled(false)
        assertEquals(AgentSetupReviewStatus.DISABLED, fixture.editor.state.value.reviewStatus)
        assertNull(fixture.presentation.onPrimaryAction)
        recovery()
        assertNull(fixture.editor.state.value.plan)
        fixture.editor.setEnabled(true)
        assertEquals(AgentSetupReviewStatus.REQUIRED, fixture.editor.state.value.reviewStatus)
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertEquals(2, reviews)
        assertTrue(fixture.client.applied.isEmpty())
        val discard = Fixture(this, defaults.copy(step = AgentSetupStep.REVIEW, confirmingDiscard = true))
        assertNull(discard.presentation.onPrimaryAction)
        discard.editor.refresh()
        assertNull(discard.client.request)
    }

    @Test
    fun `undo recovers conflicts with the agent disabled and waits for explicit removal`() = runTest {
        val fixture = Fixture(this, defaults.copy(
            enabled = false, mode = AgentSetupMode.UNDO, step = AgentSetupStep.REVIEW,
        ))
        fixture.client.previewBlock = {
            plan.copy(conflicts = persistentListOf(conflict.copy(kind = AgentSetupConflict.Kind.CHANGED_BLOCK)))
        }
        advanceUntilIdle()
        assertEquals(AgentSetupReviewStatus.CONFLICTS, fixture.editor.state.value.reviewStatus)
        fixture.client.previewBlock = { plan }
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertTrue(fixture.client.applied.isEmpty())
        assertNotNull(fixture.presentation.onPrimaryAction).invoke()
        advanceUntilIdle()
        assertEquals(listOf(plan.copy(undo = true)), fixture.client.applied)
        assertEquals(0, fixture.client.verifications)
    }

    @Test
    fun `verification failure preserves applied state and retry does not reapply`() = runTest {
        val fixture = Fixture(this)
        fixture.client.verifyBlock = { error("Agent is locked") }
        fixture.editor.next()
        advanceUntilIdle()
        fixture.editor.next()
        advanceUntilIdle()
        assertTrue(fixture.editor.state.value.applied)
        assertEquals(false, fixture.editor.state.value.verified)
        fixture.client.verifyBlock = { "Connected" }
        fixture.editor.verify()
        advanceUntilIdle()
        assertEquals(true, fixture.editor.state.value.verified)
        assertNull(fixture.editor.state.value.error)
        assertEquals(1, fixture.client.applied.size)
        assertEquals(2, fixture.client.verifications)
    }

    @Test
    fun `closing during verification ignores a late completion`() = runTest {
        val fixture = Fixture(this)
        val pending = CompletableDeferred<String>()
        fixture.client.verifyBlock = { withContext(NonCancellable) { pending.await() } }
        fixture.editor.next()
        advanceUntilIdle()
        fixture.editor.next()
        runCurrent()
        assertTrue(fixture.editor.state.value.applied)
        assertTrue(fixture.editor.state.value.canContinue)
        fixture.editor.next()
        assertEquals(1, fixture.closed)
        pending.complete("Connected")
        advanceUntilIdle()
        assertNull(fixture.editor.state.value.verified)
        fixture.editor.next()
        fixture.editor.back()
        assertEquals(1, fixture.closed)
    }

    @Test
    fun `unchanged configuration checks connection while undo skips verification`() = runTest {
        val fixture = Fixture(this)
        fixture.client.previewBlock = { plan.copy(edits = persistentListOf()) }
        fixture.editor.next()
        advanceUntilIdle()
        fixture.editor.next()
        advanceUntilIdle()
        assertEquals(listOf(plan.copy(edits = persistentListOf())), fixture.client.applied)
        assertEquals(1, fixture.client.verifications)
        assertFalse(fixture.editor.state.value.applied)
        val undo = Fixture(this, defaults.copy(mode = AgentSetupMode.UNDO, step = AgentSetupStep.REVIEW))
        advanceUntilIdle()
        assertEquals(1, undo.editor.state.value.stepNumber)
        assertTrue(undo.editor.state.value.plan!!.undo)
        undo.editor.next()
        advanceUntilIdle()
        assertEquals(2, undo.editor.state.value.stepNumber)
        assertEquals(1, undo.client.applied.size)
        assertEquals(0, undo.client.verifications)
        undo.editor.next()
        assertEquals(1, undo.closed)
    }

    @Test
    fun `stale unchanged configuration requires refresh before checking connection`() = runTest {
        val fixture = Fixture(this)
        fixture.client.previewBlock = { plan.copy(edits = persistentListOf()) }
        fixture.client.applyBlock = { error("File changed since review") }
        fixture.editor.next()
        advanceUntilIdle()
        fixture.editor.next()
        advanceUntilIdle()
        assertNull(fixture.editor.state.value.plan)
        assertEquals(AgentSetupReviewStatus.FAILED, fixture.editor.state.value.reviewStatus)
        assertEquals(0, fixture.client.verifications)
        assertFalse(fixture.editor.state.value.applied)
        fixture.client.applyBlock = {}
        fixture.editor.refresh()
        advanceUntilIdle()
        fixture.editor.next()
        advanceUntilIdle()
        assertEquals(1, fixture.client.verifications)
        assertFalse(fixture.editor.state.value.applied)
    }

    private class Fixture(scope: TestScope, initial: AgentSetupData = defaults) {
        val client = Client()
        var closed = 0
        val editor = AgentAutomaticSetupEditor(initial, client, scope) { closed += 1 }
        val presentation get() = editor.state.value.let { data ->
            AgentAutomaticSetupState(
                data = data,
                onContinue = (editor::next).takeIf { data.canContinue },
                onRefresh = (editor::refresh).takeIf { data.canRefresh },
            )
        }
    }

    private class Client : AgentSetupClient {
        var request: Pair<Set<SetupShell>, String>? = null
        var previewBlock: suspend () -> AgentSetupPlan = { plan }
        var applyBlock: suspend () -> Unit = {}
        var verifyBlock: suspend () -> String = { "Connected" }
        val applied = mutableListOf<AgentSetupPlan>()
        var verifications = 0
        override suspend fun preview(shells: Set<SetupShell>, host: String): AgentSetupPlan {
            request = shells to host
            return previewBlock()
        }
        override suspend fun undo() = previewBlock().copy(undo = true)
        override suspend fun apply(plan: AgentSetupPlan) {
            applied += plan
            applyBlock()
        }
        override suspend fun verify(): String {
            verifications += 1
            return verifyBlock()
        }
    }

    private companion object {
        val defaults = AgentSetupData(
            SetupAgent.SSH, enabled = true, shells = persistentListOf(SetupShell.BASH, SetupShell.ZSH),
        )
        val plan = AgentSetupPlan(
            json = "reviewed plan",
            edits = persistentListOf(AgentSetupEdit("/home/example/.ssh/config", null, "Host *")),
            conflicts = persistentListOf(),
            undo = false,
        )
        val conflict = AgentSetupConflict(AgentSetupConflict.Kind.ZDOTDIR, "/home/example/.zshenv")
    }
}
