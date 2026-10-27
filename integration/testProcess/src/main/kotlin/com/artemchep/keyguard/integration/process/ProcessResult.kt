package com.artemchep.keyguard.integration.process

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    fun describe(): String = buildString {
        appendLine("exitCode=$exitCode")
        if (stdout.isNotBlank()) {
            appendLine("stdout:")
            appendLine(stdout.trimEnd())
        }
        if (stderr.isNotBlank()) {
            appendLine("stderr:")
            appendLine(stderr.trimEnd())
        }
    }
}

/**
 * Runs [command] to completion and captures its output. [configure] sets up the
 * environment, working directory or stream redirection of the process.
 *
 * The whole [stdin] is written before the output is drained, so the timeout does
 * not cover a child that stops reading its input.
 *
 * @throws TimeoutException if the process does not exit in [timeoutSeconds]; the
 * process is then forcibly destroyed.
 */
fun runProcess(
    command: List<String>,
    stdin: ByteArray? = null,
    timeoutSeconds: Long = 30,
    configure: ProcessBuilder.() -> Unit = {},
): ProcessResult {
    val process = ProcessBuilder(command)
        .apply(configure)
        .start()
    if (stdin != null) {
        process.outputStream.use { it.write(stdin) }
    } else {
        process.outputStream.close()
    }
    // Drain the streams on background threads, otherwise a process
    // that outputs nothing but never exits would block the reads and
    // the timeout below would never get a chance to fire.
    val stdoutFuture = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
    val stderrFuture = CompletableFuture.supplyAsync { process.errorStream.readBytes() }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw TimeoutException(
            "Timed out after ${timeoutSeconds}s running: ${command.joinToString(" ")}",
        )
    }
    return ProcessResult(
        exitCode = process.exitValue(),
        stdout = stdoutFuture.get(timeoutSeconds, TimeUnit.SECONDS).decodeToString(),
        stderr = stderrFuture.get(timeoutSeconds, TimeUnit.SECONDS).decodeToString(),
    )
}
