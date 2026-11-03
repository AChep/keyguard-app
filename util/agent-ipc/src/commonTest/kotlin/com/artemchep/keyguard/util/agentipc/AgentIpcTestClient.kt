package com.artemchep.keyguard.util.agentipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a call that must return promptly may take. */
internal val BOUND: Duration = 5.seconds

/**
 * A plain blocking client of this platform's endpoint, connected as a helper
 * connects: a Unix-domain socket, or a synchronous pipe handle on Windows. A pipe
 * client must not read and write at the same time.
 */
internal interface AgentIpcTestClient : AutoCloseable {
    /** Writes all of [bytes]. */
    fun write(bytes: ByteArray)

    /**
     * Reads at most [length] bytes into [buffer] at [offset]: the count, or -1 at the
     * end of the stream, which includes a reset or broken connection. Blocks.
     */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    override fun close()
}

/**
 * A location on this platform: a new run directory under `/tmp`, or a pipe. Each call
 * without [exactName] gets a random name.
 */
internal expect fun agentIpcTestLocation(exactName: String? = null): AgentIpcLocation

/** A location of the other platform, which this one rejects. */
internal expect fun agentIpcForeignLocation(): AgentIpcLocation

/** Connects to [endpoint], retrying while every pipe instance is busy. */
internal expect fun connectAgentIpcTestClient(endpoint: String): AgentIpcTestClient

/** One attempt to connect to [endpoint]; null when nothing listens there. */
internal expect fun tryConnectAgentIpcTestClient(endpoint: String): AgentIpcTestClient?

/** A fresh exact name. */
internal fun agentIpcTestName(): String = "kg-ipc-test-" + Random.nextLong().toULong().toString(16)

/**
 * Runs [block] in real time: delays and timeouts wait for real, so native calls on
 * other threads can complete meanwhile.
 */
internal fun agentIpcTest(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest(timeout = 120.seconds) {
        withContext(Dispatchers.Default) { block() }
    }

/**
 * Runs a blocking [call] on a thread of its own and fails after [timeout] instead of
 * hanging. A call that never returns keeps its thread.
 */
internal suspend fun <T> blocking(timeout: Duration = BOUND, call: () -> T): T {
    val result = CoroutineScope(Dispatchers.IO).async { call() }
    return withTimeout(timeout) { result.await() }
}

/** Reads exactly [count] bytes; fails at an early end of the stream. */
internal suspend fun AgentIpcTestClient.readExactly(count: Int): ByteArray = blocking {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        check(read > 0) { "Unexpected end of stream after $offset bytes" }
        offset += read
    }
    bytes
}

/** Reads until the end of the stream, which must come within [BOUND]. */
internal suspend fun AgentIpcTestClient.readToEnd(): ByteArray = blocking {
    var bytes = ByteArray(0)
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        bytes += chunk.copyOf(read)
    }
    bytes
}

/** Reads exactly [count] bytes from the connection; fails at an early end of the stream. */
internal suspend fun AgentIpcConnection.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        check(read > 0) { "Unexpected end of stream after $offset bytes" }
        offset += read
    }
    return bytes
}

/** [count] bytes that are not all alike. */
internal fun pattern(count: Int): ByteArray = ByteArray(count) { (it % 251).toByte() }
