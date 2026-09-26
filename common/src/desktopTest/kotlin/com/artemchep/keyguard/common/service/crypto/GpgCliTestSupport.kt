package com.artemchep.keyguard.common.service.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Minimal helper for driving a real `gpg` binary from tests. Interop tests use this to
 * prove that Keyguard-produced key material and messages are consumable by an actual gpg
 * client. Tests should skip themselves (via [isGpgAvailable]) when gpg is not on PATH so
 * the suite still passes in environments without a gpg toolchain.
 */
object GpgCliTestSupport {
    private const val AGENT_SHUTDOWN_TIMEOUT_SECONDS = 30L

    data class GpgResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    fun isGpgAvailable(): Boolean =
        runCatching {
            runGpg(null, "--version").exitCode == 0
        }.getOrDefault(false)

    fun runGpg(
        home: Path?,
        vararg args: String,
    ): GpgResult {
        val normalizedHome = home?.toAbsolutePath()?.normalize()
        val command = buildList {
            add("gpg")
            if (normalizedHome != null) {
                add("--homedir")
                add(normalizedHome.toString())
            }
            addAll(args)
        }
        // Drain both outputs directly to private files. Waiting before reading
        // pipe streams can deadlock when either child output fills its pipe.
        val captureDirectory = Files.createTempDirectory("keyguard-gpg-cli-output-")
        val stdout = captureDirectory.resolve("stdout")
        val stderr = captureDirectory.resolve("stderr")
        var process: Process? = null
        try {
            val running = ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply {
                    if (normalizedHome != null) {
                        environment()["GNUPGHOME"] = normalizedHome.toString()
                    }
                }
                .start()
            process = running
            running.outputStream.close()
            if (!running.waitFor(60, TimeUnit.SECONDS)) {
                throw AssertionError("gpg timed out: ${args.joinToString(" ")}")
            }
            return GpgResult(
                exitCode = running.exitValue(),
                stdout = Files.readAllBytes(stdout).decodeToString(),
                stderr = Files.readAllBytes(stderr).decodeToString(),
            )
        } finally {
            try {
                process?.takeIf { it.isAlive }?.let {
                    it.destroyForcibly()
                    it.waitFor(5, TimeUnit.SECONDS)
                }
            } finally {
                Files.deleteIfExists(stdout)
                Files.deleteIfExists(stderr)
                Files.deleteIfExists(captureDirectory)
            }
        }
    }

    fun createHome(prefix: String): Path {
        val shortTempRoot = Path.of("/tmp")
        val home = if (
            shortTempRoot.isAbsolute &&
            Files.isDirectory(shortTempRoot) &&
            Files.isWritable(shortTempRoot)
        ) {
            Files.createTempDirectory(shortTempRoot, prefix)
        } else {
            Files.createTempDirectory(prefix)
        }
        return home.toAbsolutePath().normalize()
    }

    fun killAgent(home: Path) {
        val normalizedHome = home.toAbsolutePath().normalize()
        val process = ProcessBuilder(
            "gpgconf",
            "--homedir",
            normalizedHome.toString(),
            "--kill",
            "gpg-agent",
        )
            .apply {
                environment()["GNUPGHOME"] = normalizedHome.toString()
            }
            .start()
        if (!process.waitFor(AGENT_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("gpgconf timed out while stopping gpg-agent")
        }
        check(process.exitValue() == 0) {
            process.errorStream.readBytes().decodeToString()
        }
    }
}
