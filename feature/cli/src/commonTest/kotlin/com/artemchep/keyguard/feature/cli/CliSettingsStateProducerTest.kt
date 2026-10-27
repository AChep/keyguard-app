package com.artemchep.keyguard.feature.cli

import com.artemchep.keyguard.common.service.cli.CliApprovalRequest
import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.service.cli.CliServiceState
import com.artemchep.keyguard.feature.cli.ui.CliSettingsState
import com.artemchep.keyguard.feature.cli.ui.cliSettingsStateProducer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CliSettingsStateProducerTest {
    private class FakeCliService : CliService {
        override val state = MutableStateFlow(
            CliServiceState(path = "/keyguard", capabilities = setOf(CliPlatformCapability.Unsandboxed)),
        )
        override val approvals = MutableStateFlow(emptyList<CliApprovalRequest>())
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var calls = 0

        override suspend fun setEnabled(enabled: Boolean) {
            calls++
            gate?.await()
            failure?.let { throw it }
            state.update { it.copy(enabled = enabled) }
        }

        override suspend fun confirmIdentity(id: String, verify: suspend () -> Boolean) = false

        override fun respondToApproval(id: String, approved: Boolean) = false
    }

    private fun TestScope.state(service: CliService): StateFlow<CliSettingsState?> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return cliSettingsStateProducer(service)
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    @Test
    fun `enabling the CLI updates the service`() = runTest {
        val service = FakeCliService()
        val state = state(service)
        state.value!!.onEnabledChange!!(true)
        assertTrue(service.state.value.enabled)
        assertTrue(state.value!!.enabled)
        assertFalse(state.value!!.busy)
        assertFalse(state.value!!.failed)
    }

    @Test
    fun `one operation runs at a time`() = runTest {
        val service = FakeCliService().apply { gate = CompletableDeferred() }
        val state = state(service)
        val onEnabledChange = state.value!!.onEnabledChange!!
        onEnabledChange(true)
        assertTrue(state.value!!.busy)
        assertNull(state.value!!.onEnabledChange)
        // A stale snapshot can still call the action.
        onEnabledChange(false)
        service.gate?.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, service.calls)
        assertTrue(service.state.value.enabled)
        assertFalse(state.value!!.busy)
    }

    @Test
    fun `a build that serves no command shows no path`() = runTest {
        val service = FakeCliService()
        val state = state(service)
        assertEquals("/keyguard", state.value!!.path)
        service.state.update { it.copy(capabilities = emptySet()) }
        assertNull(state.value!!.path)
    }

    @Test
    fun `a failure is reported`() = runTest {
        val service = FakeCliService().apply { failure = IllegalStateException() }
        val state = state(service)
        state.value!!.onEnabledChange!!(true)
        assertTrue(state.value!!.failed)
        assertFalse(state.value!!.busy)
    }
}
