package com.artemchep.keyguard.gpge2e

import java.nio.file.Path
import java.util.concurrent.TimeUnit

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
            add("gpg")
            add("--homedir")
            add(gnupgHome.toAbsolutePath().toString())
            addAll(args)
        }
        return exec(command, gnupgHome, stdin, timeoutSeconds)
    }

    fun gpgconf(
        vararg args: String,
        timeoutSeconds: Long = 30,
    ): ProcessResult {
        val command = buildList {
            add("gpgconf")
            add("--homedir")
            add(gnupgHome.toAbsolutePath().toString())
            addAll(args)
        }
        return exec(command, gnupgHome, null, timeoutSeconds)
    }

    companion object {
        fun exec(
            command: List<String>,
            gnupgHome: Path,
            stdin: ByteArray?,
            timeoutSeconds: Long,
        ): ProcessResult {
            val builder = ProcessBuilder(command)
            builder.environment()["GNUPGHOME"] = gnupgHome.toAbsolutePath().toString()
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
}
