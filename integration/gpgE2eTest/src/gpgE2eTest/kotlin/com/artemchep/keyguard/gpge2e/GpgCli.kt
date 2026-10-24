package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.integration.process.ProcessResult
import com.artemchep.keyguard.integration.process.runProcess
import java.nio.file.Path

/**
 * Thin wrapper around the real `gpg` / `gpgconf` binaries, bound to a particular
 * GNUPGHOME. Always uses loopback pinentry and an empty passphrase so the test keys
 * are usable without any interactive prompt.
 */
class GpgCli(
    private val gnupgHome: Path,
    private val toolchain: GpgToolchain = GpgToolchain.current,
    private val environmentOverrides: Map<String, String?> = emptyMap(),
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
        return exec(command, gnupgHome, stdin, timeoutSeconds, toolchain, environmentOverrides)
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
        return exec(command, gnupgHome, null, timeoutSeconds, toolchain, environmentOverrides)
    }

    companion object {
        fun exec(
            command: List<String>,
            gnupgHome: Path,
            stdin: ByteArray?,
            timeoutSeconds: Long,
            toolchain: GpgToolchain = GpgToolchain.current,
            environmentOverrides: Map<String, String?> = emptyMap(),
        ): ProcessResult = runProcess(command, stdin, timeoutSeconds) {
            toolchain.applyToEnvironment(environment())
            environmentOverrides.forEach { (name, value) ->
                if (value == null) environment().remove(name) else environment()[name] = value
            }
            environment()["GNUPGHOME"] = gnupgHome.toAbsolutePath().toString()
        }
    }
}
