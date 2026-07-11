package com.artemchep.keyguard.common.service.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class AgentManagerTest {
    @Test
    fun `publishes expected IPC peer process`() = runTest {
        val expectedPeerProcess = CompletableDeferred<Process>()
        val process = TestProcess()

        publishExpectedPeerProcess(
            expectedPeerProcess = expectedPeerProcess,
            process = process,
        )

        assertSame(process, expectedPeerProcess.await())
    }

    @Test
    fun `rethrows IPC server failure that wins process publication race`() = runTest {
        val serverFailure = IllegalStateException("IPC server failed after readiness")
        val expectedPeerProcess = CompletableDeferred<Process>()
        expectedPeerProcess.completeExceptionally(serverFailure)

        val thrown = assertFailsWith<IllegalStateException> {
            publishExpectedPeerProcess(
                expectedPeerProcess = expectedPeerProcess,
                process = TestProcess(),
            )
        }

        assertEquals(serverFailure.message, thrown.message)
    }

    private class TestProcess : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stdout = ByteArrayInputStream(ByteArray(0))
        private val stderr = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }
}
