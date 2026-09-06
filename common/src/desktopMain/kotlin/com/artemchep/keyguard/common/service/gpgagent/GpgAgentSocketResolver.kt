package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the standard GnuPG agent socket for Keyguard's managed GnuPG
 * home: picks the per-platform [ManagedGpgHome], prepares it, and asks
 * `gpgconf` where the agent socket lives.
 */
internal class GpgAgentSocketResolver(
    private val logRepository: LogRepository,
    private val dataDirectory: DataDirectory,
) {
    private val gpgconf = GpgconfRunner()

    suspend fun resolveOrNull(): Path? = withContext(Dispatchers.IO) {
        val platform = CurrentPlatform
        val home = ManagedGpgHome.ofPlatformOrNull(platform, dataDirectory)
            ?: return@withContext null

        runCatchingNonFatal {
            require(!isDefaultUserGpgHome(home.path, platform)) {
                "Refusing to serve the default user GnuPG home: ${home.path}"
            }

            home.prepare()

            val socket = gpgconf.resolveAgentSocket(home.path)
            require(
                !home.isWindows ||
                    !isWindowsNamedPipePath(socket.toString()),
            ) {
                "Native Windows GnuPG must report a libassuan marker-file socket, got: $socket"
            }
            prepareSocketDirectory(home, socket)
            logRepository.post(
                TAG,
                "Resolved GPG socket with gpgconf: $socket",
                LogLevel.INFO,
            )
            socket
        }.getOrElse { e ->
            logRepository.post(
                TAG,
                "Could not resolve the platform GPG socket with gpgconf: ${e.message}",
                LogLevel.ERROR,
            )
            failGpgSocketDiscovery(platform, e)
        }
    }

    private fun prepareSocketDirectory(
        home: ManagedGpgHome,
        socket: Path,
    ) {
        if (home.isWindows) {
            Files.createDirectories(requireNotNull(socket.parent))
            return
        }

        val homePath = home.path.toAbsolutePath().normalize()
        val socketPath = socket.toAbsolutePath().normalize()
        if (socketPath.startsWith(homePath)) return

        val result = gpgconf.run(home.path, "--create-socketdir")
        require(result.exitCode == 0) {
            formatGpgconfFailure(
                invocation = "gpgconf --create-socketdir",
                exitCode = result.exitCode,
                output = result.output,
            )
        }
    }

    companion object {
        private const val TAG = "GpgAgentManager"
    }
}

internal fun failGpgSocketDiscovery(
    platform: Platform,
    cause: Throwable,
): Nothing {
    val details = cause.message ?: cause::class.simpleName ?: "unknown error"
    val message = when (platform) {
        is Platform.Desktop.Linux -> if (platform.isFlatpak) {
            "Could not resolve the standard GnuPG agent socket for Keyguard's Flatpak GnuPG home. " +
                "Check that gpgconf is available and that the Flatpak can access the GnuPG " +
                "runtime socket directory (xdg-run/gnupg). Details: $details"
        } else {
            "Could not resolve the standard GnuPG agent socket for Keyguard's Linux GnuPG home. " +
                "Install GnuPG so gpgconf is available on PATH, and make sure " +
                "gpgconf --create-socketdir can prepare the per-user runtime socket directory. " +
                "Details: $details"
        }

        is Platform.Desktop.MacOS ->
            "Could not prepare the macOS GnuPG home or resolve its standard agent socket. " +
                "Install GnuPG or configure KEYGUARD_GPG_BIN_DIR. Details: $details"

        is Platform.Desktop.Windows ->
            "Could not resolve the native Windows GnuPG agent socket. " +
                "Install GnuPG or configure KEYGUARD_GPG_BIN_DIR. Details: $details"

        else ->
            "Could not resolve the standard GnuPG agent socket. Details: $details"
    }
    throw IllegalStateException(message, cause)
}
