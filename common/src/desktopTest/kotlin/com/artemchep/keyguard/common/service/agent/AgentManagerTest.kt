package com.artemchep.keyguard.common.service.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class AgentManagerTest {
    @Test
    fun `output drain accepts only the exact control line`() = runTest {
        val stdout = buildString {
            appendLine(" $AGENT_STARTUP_READY_RECORD")
            appendLine("$AGENT_STARTUP_READY_RECORD ")
            appendLine(AGENT_STARTUP_READY_RECORD)
        }
        val process = TestProcess(stdout = stdout.encodeToByteArray())
        val ready = CompletableDeferred<Unit>()
        val diagnostics = AgentProcessDiagnosticTail()

        val drains = drainAgentProcessOutput(
            scope = this,
            process = process,
            displayName = "SSH agent",
            readyRecord = AGENT_STARTUP_READY_RECORD,
            ready = ready,
            diagnostics = diagnostics,
            logStdout = {},
            logStderr = {},
            logReadFailure = {},
        )
        drains.joinAll()

        assertTrue(ready.isCompleted)
        // Only the two inexact lines land in the diagnostics; the exact
        // record is consumed as the readiness signal.
        val snapshot = diagnostics.snapshot()
        assertContains(snapshot, "[stdout]  $AGENT_STARTUP_READY_RECORD")
        assertEquals(2, snapshot.lines().size)
    }

    @Test
    fun `diagnostic tail remains bounded and retains newest output`() {
        val diagnostics = AgentProcessDiagnosticTail(maxChars = 24)

        diagnostics.append("stdout", "old-output")
        diagnostics.append("stderr", "new-output")
        val snapshot = diagnostics.snapshot()

        assertTrue(snapshot.length <= 24)
        assertContains(snapshot, "new-output")
        assertFalse(snapshot.contains("old-output"))
    }

    @Test
    fun `diagnostic tail truncates an oversized line to its newest output`() {
        val maxChars = 8_192
        val diagnostics = AgentProcessDiagnosticTail(maxChars = maxChars)
        val line = "x".repeat(1_000_000) + "newest output"

        diagnostics.append("stderr", line)

        assertEquals(maxChars, diagnostics.snapshot().length)
        assertEquals(line.takeLast(maxChars), diagnostics.snapshot())
    }

    @Test
    fun `diagnostic tail retains lines that fit exactly including their separator`() {
        val diagnostics = AgentProcessDiagnosticTail(maxChars = 21)

        diagnostics.append("stdout", "a")
        diagnostics.append("stderr", "b")
        assertEquals("[stdout] a\n[stderr] b", diagnostics.snapshot())

        diagnostics.append("stdout", "c")
        assertEquals("[stderr] b\n[stdout] c", diagnostics.snapshot())
    }

    @Test
    fun `diagnostic tail evicts a line when its separator exceeds the limit`() {
        val diagnostics = AgentProcessDiagnosticTail(maxChars = 20)

        diagnostics.append("stdout", "a")
        diagnostics.append("stderr", "b")

        assertEquals("[stderr] b", diagnostics.snapshot())
    }

    @Test
    fun `diagnostic tail supports a one-character limit`() {
        val diagnostics = AgentProcessDiagnosticTail(maxChars = 1)

        diagnostics.append("stdout", "ab")
        assertEquals("b", diagnostics.snapshot())

        diagnostics.append("stderr", "cd")
        assertEquals("d", diagnostics.snapshot())
    }

    @Test
    fun `closed diagnostic tail discards content and ignores new output`() {
        val diagnostics = AgentProcessDiagnosticTail()
        diagnostics.append("stderr", "startup output")
        assertFalse(diagnostics.isClosed)

        diagnostics.close()
        diagnostics.append("stderr", "steady-state output")

        assertTrue(diagnostics.isClosed)
        assertEquals("", diagnostics.snapshot())
    }

    @Test
    fun `output drain keeps forwarding after startup diagnostics close`() = runTest {
        val process = TestProcess(
            stdout = "$AGENT_STARTUP_READY_RECORD\nruntime output\n".encodeToByteArray(),
            stderr = "WARN runtime failure\n".encodeToByteArray(),
        )
        val ready = CompletableDeferred<Unit>()
        val diagnostics = AgentProcessDiagnosticTail().apply {
            append("stderr", "startup output")
            close()
        }
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        drainAgentProcessOutput(
            scope = this,
            process = process,
            displayName = "SSH agent",
            readyRecord = AGENT_STARTUP_READY_RECORD,
            ready = ready,
            diagnostics = diagnostics,
            logStdout = { stdout.add(it) },
            logStderr = { stderr.add(it) },
            logReadFailure = { error(it) },
        ).joinAll()

        assertTrue(ready.isCompleted)
        assertEquals(listOf("runtime output"), stdout)
        assertEquals(listOf("WARN runtime failure"), stderr)
        assertEquals("", diagnostics.snapshot())
    }

    @Test
    fun `startup readiness succeeds when exact record arrives`() = runTest {
        val ready = CompletableDeferred(Unit)
        val processExited = CompletableDeferred<Int>()

        awaitAgentStartupReadiness(
            ready = ready,
            processExited = processExited,
            timeoutMs = 1_000,
            displayName = "SSH agent",
            diagnostics = AgentProcessDiagnosticTail(),
        )
    }

    @Test
    fun `process exit wins startup race and includes diagnostics`() = runTest {
        val diagnostics = AgentProcessDiagnosticTail()
        // Simulates a drain coroutine that has not yet consumed the child's
        // final output; the failure must join it before taking the snapshot.
        val lateDrain = launch(start = CoroutineStart.LAZY) {
            diagnostics.append("stderr", "bind failed")
        }
        val thrown = assertFailsWith<IllegalStateException> {
            awaitAgentStartupReadiness(
                ready = CompletableDeferred(Unit),
                processExited = CompletableDeferred(17),
                timeoutMs = 1_000,
                displayName = "SSH agent",
                diagnostics = diagnostics,
                outputDrains = listOf(lateDrain),
            )
        }

        assertTrue(lateDrain.isCompleted)
        assertContains(thrown.message.orEmpty(), "exited unexpectedly with code 17")
        assertContains(thrown.message.orEmpty(), "[stderr] bind failed")
    }

    @Test
    fun `startup readiness timeout includes bounded diagnostics`() = runTest {
        val diagnostics = AgentProcessDiagnosticTail().apply {
            append("stderr", "still starting")
        }
        val thrown = assertFailsWith<IllegalStateException> {
            awaitAgentStartupReadiness(
                ready = CompletableDeferred(),
                processExited = CompletableDeferred(),
                timeoutMs = 1,
                displayName = "SSH agent",
                diagnostics = diagnostics,
            )
        }

        assertContains(thrown.message.orEmpty(), "did not become ready within 1ms")
        assertContains(thrown.message.orEmpty(), "[stderr] still starting")
    }

}

class AgentManagerPeerProcessTest {
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
}

private class TestProcess(
    stdout: ByteArray = ByteArray(0),
    stderr: ByteArray = ByteArray(0),
) : Process() {
    private val stdin = ByteArrayOutputStream()
    private val stdout = ByteArrayInputStream(stdout)
    private val stderr = ByteArrayInputStream(stderr)

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() = Unit
}
