package com.artemchep.keyguard.integration.process

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunProcessTest {
    @Test
    fun nonZeroExitIsReported() {
        val result = runProcess(TestChild.command("exit", "3"))
        assertEquals(3, result.exitCode)
        assertFalse(result.isSuccess)
        assertTrue(result.describe().startsWith("exitCode=3"), result.describe())
    }

    @Test
    fun capturesBothStreamsSeparately() {
        val result = runProcess(TestChild.command("streams"))
        assertTrue(result.isSuccess, result.describe())
        assertEquals("to stdout", result.stdout.trim())
        assertEquals("to stderr", result.stderr.trim())
    }

    @Test
    fun drainsOutputLargerThanThePipeBuffer() {
        val size = 1024 * 1024
        val result = runProcess(TestChild.command("large", size.toString()))
        assertTrue(result.isSuccess, "exitCode=${result.exitCode}")
        assertEquals(size, result.stdout.length)
        assertEquals(size, result.stderr.length)
    }

    @Test
    fun mergedStderrIsReadAsStdout() {
        val result = runProcess(TestChild.command("streams")) {
            redirectErrorStream(true)
        }
        assertTrue(result.isSuccess, result.describe())
        assertTrue("to stdout" in result.stdout, result.stdout)
        assertTrue("to stderr" in result.stdout, result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun writesStdinThenClosesIt() {
        val result = runProcess(TestChild.command("echo"), stdin = "hello".encodeToByteArray())
        assertTrue(result.isSuccess, result.describe())
        assertEquals("hello", result.stdout)
    }

    @Test
    fun closesStdinWhenThereIsNoInput() {
        val result = runProcess(TestChild.command("echo"))
        assertTrue(result.isSuccess, result.describe())
        assertEquals("", result.stdout)
    }

    @Test
    fun silentHangTimesOutAndDestroysTheProcess() {
        val pidFile = Files.createTempFile("keyguard-test-process-", ".pid")
        try {
            Files.delete(pidFile)
            val error = assertFailsWith<TimeoutException> {
                runProcess(TestChild.command("hang", pidFile.toString()), timeoutSeconds = 5)
            }
            assertTrue("Timed out after 5s running: " in error.message.orEmpty(), error.message)

            val pid = pidFile.readText().toLong()
            val child = ProcessHandle.of(pid)
            child.ifPresent { handle -> handle.onExit().get(5, TimeUnit.SECONDS) }
            assertFalse(child.map(ProcessHandle::isAlive).orElse(false))
        } finally {
            pidFile.deleteIfExists()
        }
    }
}
