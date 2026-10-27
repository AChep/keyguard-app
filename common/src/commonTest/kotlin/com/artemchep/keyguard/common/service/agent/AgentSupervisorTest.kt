package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.AgentStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSupervisorTest {
    private class Agent

    private class Harness(scope: TestScope) {
        val enabled = MutableStateFlow(true)
        val statuses = mutableListOf<AgentStatus>()
        val failures = mutableListOf<AgentFailure>()
        var starts = 0
        var stops = 0
        var startFails = true
        var runFor = 0.seconds
        val policy = AgentRestartPolicy(
            initialDelay = 1.seconds,
            maxDelay = 4.seconds,
            stableRun = 1.minutes,
            maxConsecutiveFailures = 3,
        )

        init {
            scope.backgroundScope.launch {
                superviseAgent(
                    enabled = enabled,
                    available = true,
                    status = { statuses += it },
                    onFailure = { failures += it },
                    policy = policy,
                    timeSource = scope.testScheduler.timeSource,
                    start = {
                        starts += 1
                        if (startFails) error("no socket")
                        Agent()
                    },
                    join = {
                        delay(runFor)
                        "exit code 1"
                    },
                    stop = { stops += 1 },
                )
            }
        }
    }

    @Test
    fun `a failing start is retried with growing delays until the supervisor gives up`() = runTest {
        val harness = Harness(this)
        runCurrent()
        assertEquals(1, harness.starts)
        assertEquals(listOf(AgentStatus.Starting, AgentStatus.Failed), harness.statuses)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, harness.starts, "The first restart waits for the initial delay")
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(3, harness.starts, "The second restart waits twice as long")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(3, harness.starts, "After the last allowed failure the agent stays failed")

        assertEquals(listOf(1, 2, 3), harness.failures.map { it.attempt })
        assertEquals(listOf(true, true, false), harness.failures.map { it.willRetry })
        assertEquals(AgentStatus.Failed, harness.statuses.last())
        assertEquals(3, harness.stops, "Every run is stopped, even one that failed to start")
    }

    @Test
    fun `a stable run resets the failure count`() = runTest {
        val harness = Harness(this)
        harness.startFails = false
        harness.runFor = 2.minutes
        runCurrent()
        assertEquals(listOf(AgentStatus.Starting, AgentStatus.Ready), harness.statuses)

        advanceTimeBy(2.minutes)
        runCurrent()
        assertEquals(AgentStoppedException::class, harness.failures.single().error::class)
        assertEquals(1, harness.failures.single().attempt)

        advanceTimeBy(3.minutes)
        runCurrent()
        assertEquals(listOf(1, 1), harness.failures.map { it.attempt }, "A long run never counts as a streak")
        assertEquals(3, harness.starts)
    }

    @Test
    fun `disabling stops the agent and enabling it again starts afresh`() = runTest {
        val harness = Harness(this)
        harness.startFails = false
        harness.runFor = 1.minutes
        runCurrent()
        assertEquals(AgentStatus.Ready, harness.statuses.last())

        harness.enabled.value = false
        runCurrent()
        assertEquals(1, harness.stops)
        assertEquals(AgentStatus.Stopped, harness.statuses.last())
        assertTrue(harness.failures.isEmpty(), "A requested stop is not a failure")

        harness.enabled.value = true
        runCurrent()
        assertEquals(2, harness.starts)
        assertEquals(AgentStatus.Ready, harness.statuses.last())
    }

    @Test
    fun `a failed start releases resources and finishes its worker before retrying`() = runTest {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("startup failed")
        val failures = mutableListOf<AgentFailure>()
        val transportClosed = CompletableDeferred<Unit>()
        backgroundScope.launch {
            superviseAgent<Agent>(
                enabled = MutableStateFlow(true),
                available = true,
                status = {},
                onFailure = { failures += it },
                policy = AgentRestartPolicy(initialDelay = 1.seconds, maxConsecutiveFailures = 2),
                start = { scope ->
                    events += "start"
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                transportClosed.await()
                                events += "worker finished"
                            }
                        }
                    }
                    throw failure
                },
                join = { error("Startup never completed") },
                stop = { agent ->
                    assertNull(agent)
                    events += "stop"
                    transportClosed.complete(Unit)
                },
            )
        }
        runCurrent()
        assertEquals(listOf("start", "stop", "worker finished"), events)
        assertIs<IllegalStateException>(failures.single().error)
        assertEquals(failure.message, failures.single().error.message)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(List(2) { listOf("start", "stop", "worker finished") }.flatten(), events)
        assertEquals(listOf(true, false), failures.map { it.willRetry })
    }

    @Test
    fun `an exited agent waits for descendants to finish before reporting failure and retrying`() = runTest {
        val transportClosed = CompletableDeferred<Unit>()
        val cleanupAllowed = CompletableDeferred<Unit>()
        val failures = mutableListOf<AgentFailure>()
        var starts = 0
        var finishedWorkers = 0
        backgroundScope.launch {
            superviseAgent(
                enabled = MutableStateFlow(true),
                available = true,
                status = {},
                onFailure = { failures += it },
                policy = AgentRestartPolicy(initialDelay = 1.seconds, maxConsecutiveFailures = 2),
                start = { scope ->
                    starts += 1
                    // AgentManager owns a supervisor job beneath the run's scope.
                    val serverScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
                    serverScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                transportClosed.await()
                                cleanupAllowed.await()
                                finishedWorkers += 1
                            }
                        }
                    }
                    Agent()
                },
                join = { "exit code 1" },
                stop = { transportClosed.complete(Unit) },
            )
        }
        runCurrent()
        assertTrue(transportClosed.isCompleted)
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(1, starts)
        assertEquals(0, finishedWorkers)
        assertTrue(failures.isEmpty())

        cleanupAllowed.complete(Unit)
        runCurrent()
        assertEquals(1, finishedWorkers)
        assertEquals(AgentStoppedException::class, failures.single().error::class)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(2, finishedWorkers)
    }

    @Test
    fun `reenabling and parent cancellation wait for worker cleanup without reporting failure`() = runTest {
        val enabled = MutableStateFlow(true)
        val transportClosed = CompletableDeferred<Unit>()
        val cleanupAllowed = CompletableDeferred<Unit>()
        val failures = mutableListOf<AgentFailure>()
        var starts = 0
        var stops = 0
        var finishedWorkers = 0
        val supervisor = backgroundScope.launch {
            superviseAgent(
                enabled = enabled,
                available = true,
                status = {},
                onFailure = { failures += it },
                start = { scope ->
                    starts += 1
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                transportClosed.await()
                                cleanupAllowed.await()
                                finishedWorkers += 1
                            }
                        }
                    }
                    Agent()
                },
                join = { awaitCancellation() },
                stop = {
                    stops += 1
                    transportClosed.complete(Unit)
                },
            )
        }
        runCurrent()
        enabled.value = false
        runCurrent()
        assertEquals(1, stops)
        enabled.value = true
        runCurrent()
        assertEquals(1, starts, "The next run must wait for the cancelled run's children")
        assertEquals(0, finishedWorkers)

        cleanupAllowed.complete(Unit)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, finishedWorkers)
        supervisor.cancel()
        runCurrent()
        assertTrue(supervisor.isCompleted)
        assertEquals(2, stops)
        assertEquals(2, finishedWorkers)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a failed child is reported and retried without cancelling the supervisor`() = runTest {
        val failure = IllegalStateException("worker failed")
        val failures = mutableListOf<AgentFailure>()
        var starts = 0
        var stops = 0
        val supervisor = backgroundScope.launch {
            superviseAgent(
                enabled = MutableStateFlow(true),
                available = true,
                status = {},
                onFailure = { failures += it },
                policy = AgentRestartPolicy(initialDelay = 1.seconds, maxConsecutiveFailures = 2),
                start = { scope ->
                    starts += 1
                    scope.launch { throw failure }
                    Agent()
                },
                join = { awaitCancellation() },
                stop = { stops += 1 },
            )
        }
        runCurrent()
        assertIs<IllegalStateException>(failures.single().error)
        assertEquals(failure.message, failures.single().error.message)
        assertTrue(supervisor.isActive)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, starts)
        assertEquals(2, stops)
        assertEquals(listOf(true, false), failures.map { it.willRetry })
        assertTrue(failures.all { it.error is IllegalStateException && it.error.message == failure.message })
        assertTrue(supervisor.isActive)
    }

    @Test
    fun `a failed stop still cancels and awaits the worker`() = runTest {
        val failure = IllegalStateException("stop failed")
        val failures = mutableListOf<AgentFailure>()
        var workerFinished = false
        backgroundScope.launch {
            superviseAgent(
                enabled = MutableStateFlow(true),
                available = true,
                status = {},
                onFailure = { failures += it },
                policy = AgentRestartPolicy(maxConsecutiveFailures = 1),
                start = { scope ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                delay(1.seconds)
                                workerFinished = true
                            }
                        }
                    }
                    Agent()
                },
                join = { "exit code 1" },
                stop = { throw failure },
            )
        }
        runCurrent()
        assertFalse(workerFinished)
        assertTrue(failures.isEmpty())
        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(workerFinished)
        assertIs<IllegalStateException>(failures.single().error)
        assertEquals(failure.message, failures.single().error.message)
    }

    @Test
    fun `an unavailable agent is unsupported and never started`() = runTest {
        val statuses = mutableListOf<AgentStatus>()
        var starts = 0
        backgroundScope.launch {
            superviseAgent<Agent>(
                enabled = MutableStateFlow(true),
                available = false,
                status = { statuses += it },
                start = { starts += 1; Agent() },
                join = { null },
                stop = {},
            )
        }
        runCurrent()
        assertEquals<List<AgentStatus>>(listOf(AgentStatus.Unsupported), statuses)
        assertEquals(0, starts)
    }
}
