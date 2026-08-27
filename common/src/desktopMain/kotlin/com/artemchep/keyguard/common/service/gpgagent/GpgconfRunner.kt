package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Runs the `gpgconf` tool against a specific GnuPG home. On Windows and
 * macOS this also searches known installation directories, since GUI
 * launches do not necessarily include GnuPG on PATH.
 */
internal class GpgconfRunner {
    internal data class Result(
        val exitCode: Int,
        val output: String,
    )

    private val executable: Path by lazy(::resolveExecutable)

    fun resolveAgentSocket(home: Path): Path {
        val result = run(home, "--list-dirs", "agent-socket")
        require(result.exitCode == 0) {
            formatGpgconfFailure(
                invocation = "gpgconf --list-dirs agent-socket",
                exitCode = result.exitCode,
                output = result.output,
            )
        }
        return parseGpgconfAgentSocket(result.output)
    }

    fun run(
        home: Path,
        vararg args: String,
    ): Result {
        val command = buildList {
            add(executable.toString())
            add("--homedir")
            add(home.toAbsolutePath().toString())
            addAll(args)
        }
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(true)
        if (executable.isAbsolute) {
            prependPath(processBuilder.environment(), requireNotNull(executable.parent))
        }
        val process = processBuilder.start()
        process.outputStream.close()

        val output = CompletableFuture.supplyAsync {
            process.inputStream.readBytes().decodeToString()
        }
        if (!process.waitFor(GPGCONF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(GPGCONF_FORCE_TERMINATION_SECONDS, TimeUnit.SECONDS)
            val diagnostic = runCatchingNonFatal {
                readGpgconfOutput(output, GPGCONF_FORCE_TERMINATION_SECONDS)
            }.getOrDefault("")
            error(
                formatGpgconfTimeout(
                    invocation = command.joinToString(" "),
                    output = diagnostic,
                ),
            )
        }
        return Result(
            exitCode = process.exitValue(),
            output = readGpgconfOutput(output, GPGCONF_TIMEOUT_SECONDS),
        )
    }

    private fun resolveExecutable(): Path {
        val platform = CurrentPlatform
        if (platform !is Platform.Desktop.Windows && platform !is Platform.Desktop.MacOS) {
            return Path.of("gpgconf")
        }

        val configuredBinDir = System.getProperty("keyguard.gpg.binDir")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: gpgEnvPath("KEYGUARD_GPG_BIN_DIR")
        val pathDirectories = System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .map { if (platform is Platform.Desktop.Windows) it.trim().trim('"') else it }
            .filter { it.isNotBlank() }
            .map(Path::of)
            .toList()
        if (platform is Platform.Desktop.MacOS) {
            return resolveMacosGpgconf(configuredBinDir, pathDirectories)
        }

        val programFilesRoots = listOfNotNull(
            gpgEnvPath("ProgramFiles(x86)"),
            gpgEnvPath("ProgramFiles"),
        )
        val binDir = findNativeWindowsGnuPgBinDir(
            configuredBinDir = configuredBinDir,
            programFilesRoots = programFilesRoots,
            pathDirectories = pathDirectories,
        ) ?: error(
            "Could not find native gpg.exe and gpgconf.exe. " +
                "Set KEYGUARD_GPG_BIN_DIR or -Dkeyguard.gpg.binDir to the GnuPG bin directory.",
        )
        return binDir.resolve(WINDOWS_GPGCONF_EXECUTABLE)
    }

    private fun prependPath(
        environment: MutableMap<String, String>,
        directory: Path,
    ) {
        val pathKey = environment.keys
            .firstOrNull { it.equals("PATH", ignoreCase = true) }
            ?: "PATH"
        environment[pathKey] = listOf(
            directory.toAbsolutePath().toString(),
            environment[pathKey].orEmpty(),
        )
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)
    }

    companion object {
        private const val GPGCONF_FORCE_TERMINATION_SECONDS = 2L
        private const val GPGCONF_TIMEOUT_SECONDS = 15L
        private const val WINDOWS_GPGCONF_EXECUTABLE = "gpgconf.exe"
    }
}

internal fun readGpgconfOutput(
    output: CompletableFuture<String>,
    timeoutSeconds: Long,
): String = try {
    output.get(timeoutSeconds, TimeUnit.SECONDS)
} catch (e: ExecutionException) {
    // Future.get wraps worker failures; expose fatal causes to the outer guards.
    e.cause?.throwIfFatalOrCancellation()
    throw e
}

internal fun resolveMacosGpgconf(
    configuredBinDir: Path?,
    pathDirectories: List<Path>,
    isExecutableFile: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
): Path {
    val configurationHint =
        "Set KEYGUARD_GPG_BIN_DIR or -Dkeyguard.gpg.binDir to the GnuPG bin directory."
    configuredBinDir?.let { directory ->
        val executable = directory.resolve("gpgconf").toAbsolutePath().normalize()
        require(isExecutableFile(executable)) {
            "Configured GnuPG bin directory '$directory' must contain an executable gpgconf. $configurationHint"
        }
        return executable
    }

    // Like GPGME, search the usual macOS installations when the inherited
    // PATH does not contain GnuPG. Include MacPorts as well as Homebrew and GPG Suite.
    val fallbackDirectories = listOf(
        Path.of("/opt/homebrew/bin"),
        Path.of("/usr/local/bin"),
        Path.of("/usr/local/MacGPG2/bin"),
        Path.of("/opt/local/bin"),
    )
    return (pathDirectories + fallbackDirectories)
        .asSequence()
        .map { it.resolve("gpgconf").toAbsolutePath().normalize() }
        .distinct()
        .firstOrNull(isExecutableFile)
        ?: error("Could not find gpgconf on PATH or in standard macOS installation directories. $configurationHint")
}

internal fun parseGpgconfAgentSocket(output: String): Path {
    val lines = output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
    val labeledSocket = lines
        .firstOrNull { it.startsWith("agent-socket:") }
        ?.substringAfter("agent-socket:")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val socket = labeledSocket
        ?: lines.firstOrNull { line ->
            runCatchingNonFatal { Path.of(line).isAbsolute }.getOrDefault(false)
        }
        ?: error("gpgconf did not report an agent-socket. Output: ${formatGpgconfOutput(output)}")
    val path = Path.of(socket)
    require(path.isAbsolute) {
        "gpgconf reported a non-absolute agent socket: $socket"
    }
    return path
}

internal fun formatGpgconfFailure(
    invocation: String,
    exitCode: Int,
    output: String,
): String = "$invocation exited with code $exitCode. Output: ${formatGpgconfOutput(output)}"

private fun formatGpgconfTimeout(
    invocation: String,
    output: String,
): String = "Timed out running $invocation. Output: ${formatGpgconfOutput(output)}"

private fun formatGpgconfOutput(output: String): String =
    output.trim().ifEmpty { "<no output>" }

internal fun findNativeWindowsGnuPgBinDir(
    configuredBinDir: Path?,
    programFilesRoots: List<Path>,
    pathDirectories: List<Path>,
    isRegularFile: (Path) -> Boolean = { Files.isRegularFile(it) },
): Path? {
    val candidates = buildList {
        configuredBinDir?.let(::add)
        programFilesRoots.forEach { root ->
            add(root.resolve("GnuPG").resolve("bin"))
        }
        addAll(pathDirectories)
    }
        .map { it.toAbsolutePath().normalize() }
        .distinctBy { it.toString().lowercase() }

    return candidates.firstOrNull { binDir ->
        !binDir.isKnownIncompatibleWindowsGnuPgPath() &&
            isRegularFile(binDir.resolve("gpg.exe")) &&
            isRegularFile(binDir.resolve("gpgconf.exe"))
    }
}

/**
 * Whether this bin directory belongs to a GnuPG build that accepts
 * Unix-style paths (Git-for-Windows/MSYS/Cygwin) and therefore cannot
 * be trusted with native Windows GNUPGHOME paths.
 *
 * Public so the gpgE2eTest module shares the same compatibility policy.
 */
fun Path.isKnownIncompatibleWindowsGnuPgPath(): Boolean {
    val value = toAbsolutePath()
        .normalize()
        .toString()
        .replace('/', '\\')
        .lowercase()
    return listOf(
        "\\git\\usr\\bin",
        "\\msys64\\usr\\bin",
        "\\cygwin\\bin",
        "\\cygwin64\\bin",
    ).any { value.endsWith(it) || "$it\\" in value }
}

/**
 * Public so the gpgE2eTest module shares the same socket classification.
 */
fun isWindowsNamedPipePath(value: String): Boolean =
    value
        .replace('/', '\\')
        .startsWith("\\\\.\\pipe\\", ignoreCase = true)
