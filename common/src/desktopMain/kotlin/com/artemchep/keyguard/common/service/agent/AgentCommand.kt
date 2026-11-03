package com.artemchep.keyguard.common.service.agent

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val MAX_AGENT_COMMAND_OUTPUT = 4 * 1024 * 1024

internal class AgentCommandResult(
    val exitCode: Int,
    /** The standard output and the standard error, combined. */
    val output: String,
)

/**
 * Runs a command of an agent executable with the [input] on its standard
 * input. Throws if it runs longer than [timeoutSeconds], or if its output
 * exceeds the size limit.
 */
internal fun runAgentCommand(
    command: List<String>,
    timeoutSeconds: Long,
    input: String? = null,
): AgentCommandResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = CompletableFuture.supplyAsync {
        process.inputStream.readNBytes(MAX_AGENT_COMMAND_OUTPUT + 1)
    }
    try {
        process.outputStream.use { stream -> input?.let { stream.write(it.encodeToByteArray()) } }
        check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Agent command timed out" }
        val bytes = output.get(timeoutSeconds, TimeUnit.SECONDS)
        check(bytes.size <= MAX_AGENT_COMMAND_OUTPUT) { "Agent command output exceeds the size limit" }
        return AgentCommandResult(
            exitCode = process.exitValue(),
            output = bytes.decodeToString(),
        )
    } finally {
        if (process.isAlive) process.destroyForcibly()
        output.cancel(true)
    }
}
