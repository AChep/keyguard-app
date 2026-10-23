@file:OptIn(NativeRuntimeApi::class)

package com.artemchep.keyguard.util.agentipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * D18: one Kotlin/Native thread blocks in each pending native call. At the bound the
 * agents can reach, two lanes for each of 8 + 8 + 32 connections and one accept for
 * each of three listeners, garbage collection and `Dispatchers.IO` stay healthy, and
 * closing everything returns every call promptly.
 */
class AgentIpcNativeThreadsTest {
    @Test
    fun ninetyNineBlockedCallsKeepTheRuntimeHealthy() = runBlocking {
        val listeners = List(LISTENERS) { AgentIpcListener.bind(agentIpcTestLocation()) }
        val clients = mutableListOf<AgentIpcTestClient>()
        val connections = mutableListOf<AgentIpcConnection>()
        // Failures stay in the deferred results, which the test awaits.
        val scope = CoroutineScope(SupervisorJob())
        try {
            repeat(CONNECTIONS) { index ->
                val listener = listeners[index % LISTENERS]
                clients += connectAgentIpcTestClient(listener.endpoint)
                connections += withTimeout(5_000) { listener.accept() }
            }
            val calls = mutableListOf<Deferred<AgentIpcFailureKind?>>()
            connections.forEach { connection ->
                // The clients neither write nor read, so both lanes block.
                calls += scope.async { failureOf { connection.read(ByteArray(16)) } }
                calls += scope.async { failureOf { connection.write(ByteArray(BLOCKING_WRITE_SIZE)) } }
            }
            listeners.forEach { listener ->
                calls += scope.async { failureOf { listener.accept() } }
            }
            assertEquals(2 * CONNECTIONS + LISTENERS, calls.size)
            delay(500)
            assertTrue(calls.none { it.isCompleted }, "Every call blocks")

            val collecting = TimeSource.Monotonic.markNow()
            GC.collect()
            assertTrue(collecting.elapsedNow() < 5.seconds, "GC.collect took ${collecting.elapsedNow()}")
            withTimeout(1_000) { withContext(Dispatchers.IO) {} }

            connections.forEach(AgentIpcConnection::close)
            listeners.forEach(AgentIpcListener::close)
            val failures = withTimeout(1_000) { calls.awaitAll() }
            assertEquals(List(calls.size) { AgentIpcFailureKind.CLOSED }, failures)
        } finally {
            scope.cancel()
            connections.forEach(AgentIpcConnection::close)
            listeners.forEach(AgentIpcListener::close)
            clients.forEach(AgentIpcTestClient::close)
        }
    }

    /** The kind of the failure that ended [call], or null when it returned. */
    private suspend fun failureOf(call: suspend () -> Unit): AgentIpcFailureKind? = try {
        call()
        null
    } catch (error: AgentIpcException) {
        error.kind
    }
}

private const val LISTENERS = 3

private const val CONNECTIONS = 48

// Far more than a Unix-domain socket buffers.
private const val BLOCKING_WRITE_SIZE = 1024 * 1024
