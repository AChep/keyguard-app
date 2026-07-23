package com.artemchep.keyguard.common.service.crypto

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
        val process = ProcessBuilder(listOf("gpg", *args))
            .apply {
                if (home != null) {
                    environment()["GNUPGHOME"] = home.toString()
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
}
