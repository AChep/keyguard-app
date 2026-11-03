package com.artemchep.keyguard.integration.process

import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A long-running child process, such as a test server, whose stdout and stderr are
 * captured line by line on daemon threads named `<name>-stdout` and `<name>-stderr`.
 */
class CapturedProcess(
    private val process: Process,
    name: String,
) : AutoCloseable {
    private val stdout = ProcessOutput(process.inputStream, "$name-stdout")
    private val stderr = ProcessOutput(process.errorStream, "$name-stderr")

    val isAlive: Boolean get() = process.isAlive

    fun diagnostics(label: String): String = buildString {
        appendLine("$label alive: ${process.isAlive}")
        appendLine("$label exit: ${runCatching { process.exitValue() }.getOrNull()}")
        appendLine("$label stdout:")
        appendLine(stdout.text())
        appendLine("$label stderr:")
        appendLine(stderr.text())
    }

    /** Asks the process to exit, forcibly destroys it if it does not, then joins the readers. */
    override fun close() {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        stdout.join()
        stderr.join()
    }

    private companion object {
        const val TERMINATION_TIMEOUT_SECONDS = 5L
    }
}

/** Returns a port that was free at the time of the call. */
fun freePort(): Int = ServerSocket(0).use { socket ->
    socket.reuseAddress = true
    socket.localPort
}

private class ProcessOutput(
    input: InputStream,
    name: String,
) {
    private val buffer = StringBuilder()
    private val thread = thread(
        start = true,
        isDaemon = true,
        name = name,
    ) {
        try {
            input.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(buffer) {
                        buffer.appendLine(line)
                    }
                }
            }
        } catch (_: IOException) {
            // Process shutdown can close streams before the reader drains them.
        }
    }

    fun text(): String = synchronized(buffer) {
        buffer.toString()
    }

    fun join() {
        thread.join(TimeUnit.SECONDS.toMillis(1))
    }
}
