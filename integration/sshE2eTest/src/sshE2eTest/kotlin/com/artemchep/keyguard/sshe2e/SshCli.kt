package com.artemchep.keyguard.sshe2e

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

class SshCli(
    private val socket: String,
) {
    fun run(
        vararg args: String,
        stdin: ByteArray? = null,
        timeoutSeconds: Long = 30,
    ): ProcessResult {
        val command = args.toList()
        val builder = ProcessBuilder(command)
        builder.environment()["SSH_AUTH_SOCK"] = socket
        val process = builder.start()
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
}
