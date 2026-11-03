package com.artemchep.keyguard.integration.process

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturedProcessTest {
    @Test
    fun capturesOutputAndTerminatesARunningProcess() {
        val process = ProcessBuilder(TestChild.command("serve")).start()
        val captured = CapturedProcess(process, "test-serve")
        try {
            val deadline = System.nanoTime() + 10_000_000_000L
            while ("warming up" !in captured.diagnostics("child") && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(captured.isAlive)
        } finally {
            captured.close()
        }

        assertFalse(process.isAlive)
        val diagnostics = captured.diagnostics("child")
        assertTrue("child alive: false" in diagnostics, diagnostics)
        assertTrue("child stdout:\nready\n" in diagnostics.replace("\r\n", "\n"), diagnostics)
        assertTrue("child stderr:\nwarming up\n" in diagnostics.replace("\r\n", "\n"), diagnostics)
    }

    @Test
    fun closesAnExitedProcess() {
        val process = ProcessBuilder(TestChild.command("exit", "2")).start()
        val captured = CapturedProcess(process, "test-exit")
        process.waitFor()
        captured.close()

        val diagnostics = captured.diagnostics("child")
        assertTrue("child exit: 2" in diagnostics, diagnostics)
    }
}
