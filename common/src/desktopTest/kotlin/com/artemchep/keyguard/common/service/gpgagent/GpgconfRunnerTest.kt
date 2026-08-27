package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class GpgconfRunnerTest {
    @Test
    fun `completed output is returned unchanged`() {
        val output = CompletableFuture.completedFuture("diagnostic output\n")

        assertEquals("diagnostic output\n", readGpgconfOutput(output, timeoutSeconds = 0))
    }

    @Test
    fun `ordinary output failures preserve their cause and permit fallback`() {
        val cause = IOException("pipe closed")
        val output = CompletableFuture.failedFuture<String>(cause)

        val result = runCatchingNonFatal { readGpgconfOutput(output, timeoutSeconds = 0) }

        assertSame(cause, assertIs<ExecutionException>(result.exceptionOrNull()).cause)
        assertEquals("", result.getOrDefault(""))
    }

    @Test
    fun `output timeout permits diagnostic fallback`() {
        val output = CompletableFuture<String>()

        val result = runCatchingNonFatal { readGpgconfOutput(output, timeoutSeconds = 0) }

        assertIs<TimeoutException>(result.exceptionOrNull())
        assertEquals("", result.getOrDefault(""))
    }

    @Test
    fun `cancelled output escapes diagnostic fallback`() {
        val output = CompletableFuture<String>().apply { cancel(false) }

        assertFailsWith<CancellationException> {
            runCatchingNonFatal { readGpgconfOutput(output, timeoutSeconds = 0) }.getOrDefault("")
        }
    }

    @Test
    fun `wrapped worker cancellation escapes diagnostic fallback`() {
        val cause = CancellationException("worker cancelled")
        val output = CompletableFuture.failedFuture<String>(CompletionException(cause))

        val thrown = assertFailsWith<CancellationException> {
            runCatchingNonFatal { readGpgconfOutput(output, timeoutSeconds = 0) }.getOrDefault("")
        }

        assertSame(cause, thrown)
    }

    @Test
    fun `wrapped worker errors escape diagnostic fallback`() {
        for (cause in listOf(OutOfMemoryError("heap"), StackOverflowError("stack"), LinkageError("native"))) {
            val output = CompletableFuture.failedFuture<String>(CompletionException(cause))

            val thrown = assertFailsWith<Error> {
                runCatchingNonFatal { readGpgconfOutput(output, timeoutSeconds = 0) }.getOrDefault("")
            }

            assertSame(cause, thrown)
        }
    }

    @Test
    fun `socket parser skips warnings with invalid path characters`() {
        val socket = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("S.gpg-agent")

        assertEquals(socket, parseGpgconfAgentSocket("invalid\u0000path\n$socket\n"))
    }
}
