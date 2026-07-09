package com.artemchep.keyguard.sshe2e

import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
    private val socketPath: Path,
) {
    fun run(
        vararg args: String,
        stdin: ByteArray? = null,
        timeoutSeconds: Long = 30,
    ): ProcessResult {
        val command = args.toList()
        val builder = ProcessBuilder(command)
        builder.environment()["SSH_AUTH_SOCK"] = socketPath.toAbsolutePath().toString()
        val process = builder.start()
        if (stdin != null) {
            process.outputStream.use { it.write(stdin) }
        } else {
            process.outputStream.close()
        }
        val stdoutBytes = process.inputStream.readBytes()
        val stderrBytes = process.errorStream.readBytes()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw RuntimeException(
                "Timed out after ${timeoutSeconds}s running: ${command.joinToString(" ")}",
            )
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdoutBytes.decodeToString(),
            stderr = stderrBytes.decodeToString(),
        )
    }
}
