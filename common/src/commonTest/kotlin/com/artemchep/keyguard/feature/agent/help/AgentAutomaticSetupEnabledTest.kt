package com.artemchep.keyguard.feature.agent.help

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentAutomaticSetupEnabledTest {
    @Test
    fun `each agent requires its switch before preview and apply while undo remains available`() = runTest {
        SetupAgent.entries.forEach { agent ->
            val client = Client()
            val initial = AgentSetupData(agent, selectedShells = persistentSetOf(SetupShell.BASH))
            val editor = AgentAutomaticSetupEditor(initial, client, this) {}
            assertFalse(editor.state.value.canContinue)
            editor.next()
            editor.refresh()
            advanceUntilIdle()
            assertEquals(0, client.previews)
            assertEquals(0, client.applies)
            editor.setEnabled(true)
            editor.next()
            advanceUntilIdle()
            assertEquals(1, client.previews)
            editor.setEnabled(false)
            editor.next()
            advanceUntilIdle()
            assertEquals(0, client.applies)
            assertNull(editor.state.value.plan)
            editor.setEnabled(true)
            editor.refresh()
            advanceUntilIdle()
            editor.next()
            editor.setEnabled(false)
            advanceUntilIdle()
            assertEquals(0, client.applies)
            editor.setEnabled(true)
            editor.refresh()
            advanceUntilIdle()
            editor.next()
            advanceUntilIdle()
            assertEquals(1, client.applies)
            val undo = AgentAutomaticSetupEditor(
                initial.copy(mode = AgentSetupMode.UNDO, step = AgentSetupStep.REVIEW), client, this,
            ) {}
            advanceUntilIdle()
            assertTrue(undo.state.value.canContinue)
            undo.next()
            advanceUntilIdle()
            assertEquals(2, client.applies)
        }
    }

    @Test
    fun `disabling during a pending review discards late results and hides Termux commands`() = runTest {
        val client = Client()
        val pending = CompletableDeferred<AgentSetupPlan>()
        client.previewBlock = { withContext(NonCancellable) { pending.await() } }
        val editor = AgentAutomaticSetupEditor(AgentSetupData(SetupAgent.SSH, enabled = true), client, this) {}
        editor.next()
        runCurrent()
        editor.setEnabled(false)
        pending.complete(plan)
        advanceUntilIdle()
        assertNull(editor.state.value.plan)
        assertFalse(editor.state.value.canContinue)
        val external = AgentAutomaticSetupEditor(
            AgentSetupData(
                SetupAgent.GPG, enabled = true, external = true, selectedShells = persistentSetOf(SetupShell.BASH),
            ),
            null, this,
        ) {}
        external.next()
        external.setEnabled(false)
        assertFalse(external.state.value.setupAllowed)
        external.next()
        assertEquals(AgentSetupStep.REVIEW, external.state.value.step)
    }

    @Test
    fun `an in-flight apply preserves a disabled switch and can still finish`() = runTest {
        val client = Client()
        val pending = CompletableDeferred<Unit>()
        client.applyBlock = { pending.await() }
        val editor = AgentAutomaticSetupEditor(AgentSetupData(SetupAgent.SSH, enabled = true), client, this) {}
        editor.next()
        advanceUntilIdle()
        editor.next()
        runCurrent()
        editor.setEnabled(false)
        pending.complete(Unit)
        advanceUntilIdle()
        assertFalse(editor.state.value.enabled)
        assertTrue(editor.state.value.applied)
        assertTrue(editor.state.value.canContinue)
    }

    private class Client : AgentSetupClient {
        var previews = 0
        var applies = 0
        var previewBlock: suspend () -> AgentSetupPlan = { plan }
        var applyBlock: suspend () -> Unit = {}
        override suspend fun preview(shells: Set<SetupShell>, host: String): AgentSetupPlan {
            previews += 1
            return previewBlock()
        }
        override suspend fun undo() = plan.copy(undo = true)
        override suspend fun apply(plan: AgentSetupPlan) { applies += 1; applyBlock() }
        override suspend fun verify() = "Connected"
    }

    private companion object {
        val plan = AgentSetupPlan("{}", persistentListOf(AgentSetupEdit("config", null, "value\n")),
            persistentListOf(), false)
    }
}
