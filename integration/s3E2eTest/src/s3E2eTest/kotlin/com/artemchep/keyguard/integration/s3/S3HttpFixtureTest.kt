package com.artemchep.keyguard.integration.s3

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class S3HttpFixtureTest {
    @Test
    fun `failed writes followed by read timeouts do not acknowledge disconnect`() {
        val writeFailure = IOException("Write failed")
        val readTimeout = SocketTimeoutException("Read timed out")
        val socket = object : Socket() {
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int): Unit = throw writeFailure
            }

            override fun getInputStream() = object : InputStream() {
                override fun read(): Int = throw readTimeout
            }
        }

        val error = assertFailsWith<SocketTimeoutException> { socket.writeUntilDisconnected() }

        assertSame(readTimeout, error)
        assertEquals(listOf(writeFailure), error.suppressedExceptions)
    }

    @Test
    fun `worker failures interrupt completion waits`() = runTest {
        withContext(Dispatchers.IO) {
            withTimeout(5.seconds) {
                val failure = IOException("Fixture handler failed")
                val server = S3HttpFixture { _, _ -> throw failure }
                try {
                    val endpoint = URI(server.endpoint)
                    Socket(endpoint.host, endpoint.port).use { socket ->
                        socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                        socket.getOutputStream().flush()

                        val error = assertFailsWith<AssertionError> {
                            server.awaitCompletion(CompletableDeferred())
                        }
                        // Coroutine stacktrace recovery can insert a copy of the assertion error.
                        assertSame(failure, generateSequence<Throwable>(error) { it.cause }.last())
                    }
                } finally {
                    val error = assertFailsWith<AssertionError> { server.close() }
                    assertSame(failure, error.cause)
                }
            }
        }
    }
}
