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
        val process = ProcessBuilder(command)
            .apply {
                if (normalizedHome != null) {
                    environment()["GNUPGHOME"] = normalizedHome.toString()
                }
            }
            .start()
        val completed = process.waitFor(60, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw AssertionError("gpg timed out: ${args.joinToString(" ")}")
        }
        return GpgResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.readBytes().decodeToString(),
            stderr = process.errorStream.readBytes().decodeToString(),
        )
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
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("gpgconf timed out while stopping gpg-agent")
        }
        check(process.exitValue() == 0) {
            process.errorStream.readBytes().decodeToString()
        }
    }
}
