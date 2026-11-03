package com.artemchep.keyguard.feature.agent

import com.artemchep.keyguard.common.model.Loadable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRequestUiStateTest {
    private data class Request(
        val id: String,
        val label: String = id,
    )

    private fun TestScope.presenter() = AgentRequestPresenter<Request>(
        idOf = { it.id },
        scope = backgroundScope,
        transitionDelay = 500.milliseconds,
    )

    @Test
    fun `an answered request is followed by a pause and then the next request`() = runTest {
        val presenter = presenter()
        presenter.onPending(listOf(Request("a"), Request("b")))
        assertEquals(Loadable.Ok(Request("a")), presenter.state.value)

        presenter.onHandled("a")
        presenter.onPending(listOf(Request("b")))
        assertEquals(Loadable.Loading, presenter.state.value)
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(Loadable.Ok(Request("b")), presenter.state.value)

        presenter.onHandled("b")
        presenter.onPending(emptyList())
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertNull(presenter.state.value)
    }

    @Test
    fun `a request that leaves the queue on its own also pauses before the next one`() = runTest {
        val presenter = presenter()
        presenter.onPending(listOf(Request("a")))
        presenter.onPending(listOf(Request("b")))
        assertEquals(Loadable.Loading, presenter.state.value)
        advanceTimeBy(500.milliseconds)
        runCurrent()
        assertEquals(Loadable.Ok(Request("b")), presenter.state.value)
    }

    @Test
    fun `the shown request keeps its latest data and ignores answers to other requests`() = runTest {
        val presenter = presenter()
        presenter.onPending(listOf(Request("a"), Request("b")))
        presenter.onPending(listOf(Request("a", label = "renamed"), Request("b")))
        assertEquals(Loadable.Ok(Request("a", label = "renamed")), presenter.state.value)

        presenter.onHandled("b")
        assertEquals(Loadable.Ok(Request("a", label = "renamed")), presenter.state.value)
    }
}
