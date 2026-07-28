package com.artemchep.keyguard.gpge2e

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Thin wrapper around the real `gpg` / `gpgconf` binaries, bound to a particular
 * GNUPGHOME. Always uses loopback pinentry and an empty passphrase so the test keys
 * are usable without any interactive prompt.
 */
class GpgCli(
    private val gnupgHome: Path,
    private val toolchain: GpgToolchain = GpgToolchain.current,
) {
    fun run(
        vararg args: String,
        stdin: ByteArray? = null,
        timeoutSeconds: Long = 30,
    ): ProcessResult = runWithStdin(args.toList(), stdin, timeoutSeconds)

    fun runWithStdin(
        args: List<String>,
        stdin: ByteArray?,
        timeoutSeconds: Long = 30,
    ): ProcessResult {
        val command = buildList {
            add(toolchain.gpg.toString())
            add("--homedir")
            add(gnupgHome.toAbsolutePath().toString())
            addAll(args)
        }
        return exec(command, gnupgHome, stdin, timeoutSeconds, toolchain)
    }

    fun gpgconf(
        vararg args: String,
        timeoutSeconds: Long = 30,
    ): ProcessResult {
        val command = buildList {
            add(toolchain.gpgconf.toString())
            add("--homedir")
            add(gnupgHome.toAbsolutePath().toString())
            addAll(args)
        }
        return exec(command, gnupgHome, null, timeoutSeconds, toolchain)
    }

    companion object {
        fun exec(
            command: List<String>,
            gnupgHome: Path,
            stdin: ByteArray?,
            timeoutSeconds: Long,
            toolchain: GpgToolchain = GpgToolchain.current,
        ): ProcessResult {
            val builder = ProcessBuilder(command)
            toolchain.applyToEnvironment(builder.environment())
            builder.environment()["GNUPGHOME"] = gnupgHome.toAbsolutePath().toString()
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
}
