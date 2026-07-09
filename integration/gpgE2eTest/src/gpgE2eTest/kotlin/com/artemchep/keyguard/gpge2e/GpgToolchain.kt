package com.artemchep.keyguard.gpge2e

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Resolves the GnuPG binaries used by the E2E test.
 *
 * On Windows the test must use a native GnuPG build. Git-for-Windows/MSYS GPG
 * accepts Unix-style paths and can reinterpret a Java native path like
 * C:\Users\... as a relative POSIX path, which makes GNUPGHOME point into the
 * Gradle project directory.
 */
data class GpgToolchain(
    val gpg: Path,
    val gpgconf: Path,
) {
    val binDir: Path
        get() = gpg.parent

    fun describe(): String =
        "gpg=${gpg.toAbsolutePath()}, gpgconf=${gpgconf.toAbsolutePath()}"

    fun applyToEnvironment(environment: MutableMap<String, String>) {
        val pathKey = environment.keys
            .firstOrNull { it.equals("PATH", ignoreCase = true) }
            ?: "PATH"
        val currentPath = environment[pathKey].orEmpty()
        environment[pathKey] = listOf(
            binDir.toAbsolutePath().toString(),
            currentPath,
        )
            .filter { it.isNotBlank() }
            .joinToString(File.pathSeparator)
    }

    fun verifyCompatibleForE2e() {
        val gpgVersion = requireSuccessful(
            label = "gpg --version",
            command = listOf(gpg.toString(), "--version"),
        )
        val gpgconfVersion = requireSuccessful(
            label = "gpgconf --version",
            command = listOf(gpgconf.toString(), "--version"),
        )

        val home = Files.createTempDirectory("kg-gpge2e-gpg-preflight-")
        restrictOwnerOnly(home)
        try {
            val homeArg = home.toAbsolutePath().toString()
            requireSuccessful(
                label = "gpgconf --list-dirs",
                command = listOf(
                    gpgconf.toString(),
                    "--homedir",
                    homeArg,
                    "--list-dirs",
                ),
                gnupgHome = home,
            )
            val agentSocket = requireSuccessful(
                label = "gpgconf --list-dirs agent-socket",
                command = listOf(
                    gpgconf.toString(),
                    "--homedir",
                    homeArg,
                    "--list-dirs",
                    "agent-socket",
                ),
                gnupgHome = home,
            )
                .stdout
                .parseGpgconfListDirValue()

            requireSuccessful(
                label = "gpg --list-keys",
                command = listOf(
                    gpg.toString(),
                    "--homedir",
                    homeArg,
                    "--batch",
                    "--list-keys",
                ),
                gnupgHome = home,
            )

            if (isWindowsHost()) {
                require(agentSocket.startsWith("\\\\.\\pipe\\", ignoreCase = true)) {
                    "GPG E2E on Windows requires a native GnuPG build whose " +
                        "gpgconf agent-socket is a Windows named pipe. " +
                        "Selected ${describe()} reported '$agentSocket'. " +
                        "Check KEYGUARD_GPG_BIN_DIR or -Dkeyguard.gpg.binDir."
                }
            }

            println(
                "GPG E2E using ${describe()}\n" +
                    gpgVersion.stdout.lineSequence().firstOrNull().orEmpty() + "\n" +
                    gpgconfVersion.stdout.lineSequence().firstOrNull().orEmpty(),
            )
        } finally {
            deleteRecursively(home)
        }
    }

    private fun requireSuccessful(
        label: String,
        command: List<String>,
        gnupgHome: Path? = null,
    ): ProcessResult {
        val result = runProcess(command, gnupgHome)
        require(result.isSuccess) {
            "$label failed for ${describe()} with exit ${result.exitCode}:\n${result.stdout}"
        }
        return result
    }

    private fun runProcess(
        command: List<String>,
        gnupgHome: Path?,
        timeoutSeconds: Long = 15,
    ): ProcessResult {
        val builder = ProcessBuilder(command)
            .redirectErrorStream(true)
        applyToEnvironment(builder.environment())
        if (gnupgHome != null) {
            builder.environment()["GNUPGHOME"] = gnupgHome.toAbsolutePath().toString()
        }

        val process = builder.start()
        process.outputStream.close()

        val output = CompletableFuture.supplyAsync {
            process.inputStream.readBytes().decodeToString()
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out after ${timeoutSeconds}s running: ${command.joinToString(" ")}")
        }

        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = output.get(timeoutSeconds, TimeUnit.SECONDS),
            stderr = "",
        )
    }

    private fun restrictOwnerOnly(dir: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                dir,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return

        val stream = Files.walk(path)
        try {
            stream
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        } finally {
            stream.close()
        }
    }

    companion object {
        val current: GpgToolchain by lazy { resolve() }

        fun resolve(): GpgToolchain {
            configuredBinDir()?.let { dir ->
                val toolchain = findInBinDir(dir)
                    ?: error(
                        "Configured GnuPG bin dir '$dir' must contain " +
                            "${executableName("gpg")} and ${executableName("gpgconf")}.",
                    )
                requireWindowsCompatible(toolchain)
                return toolchain
            }

            val candidates = candidateBinDirs()
                .mapNotNull(::findInBinDir)
                .distinctBy { it.gpg.toAbsolutePath().normalize().toString().lowercase() }
            val compatible = candidates.firstOrNull { !it.isKnownIncompatibleOnWindows() }
            if (compatible != null) return compatible

            if (candidates.isNotEmpty()) {
                error(
                    "Only incompatible GnuPG toolchains were found: " +
                        candidates.joinToString { it.describe() } + ". " +
                        "Set KEYGUARD_GPG_BIN_DIR or -Dkeyguard.gpg.binDir " +
                        "to a native GnuPG bin directory.",
                )
            }

            error(
                "Could not find ${executableName("gpg")} and ${executableName("gpgconf")} " +
                    "on PATH. Set KEYGUARD_GPG_BIN_DIR or -Dkeyguard.gpg.binDir.",
            )
        }

        private fun configuredBinDir(): Path? =
            System.getProperty("keyguard.gpg.binDir")
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?: System.getenv("KEYGUARD_GPG_BIN_DIR")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it) }

        private fun candidateBinDirs(): List<Path> = buildList {
            if (isWindowsHost()) {
                listOf(
                    System.getenv("ProgramFiles(x86)"),
                    System.getenv("ProgramFiles"),
                )
                    .filterNotNull()
                    .forEach { add(Path.of(it).resolve("GnuPG").resolve("bin")) }
            }

            System.getenv("PATH")
                .orEmpty()
                .split(File.pathSeparatorChar)
                .asSequence()
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() }
                .map { Path.of(it) }
                .forEach { add(it) }
        }
            .distinctBy { it.toAbsolutePath().normalize().toString().lowercase() }

        private fun findInBinDir(dir: Path): GpgToolchain? {
            val gpg = dir.resolve(executableName("gpg"))
            val gpgconf = dir.resolve(executableName("gpgconf"))
            if (!Files.isRegularFile(gpg) || !Files.isRegularFile(gpgconf)) {
                return null
            }
            return GpgToolchain(
                gpg = gpg.toAbsolutePath().normalize(),
                gpgconf = gpgconf.toAbsolutePath().normalize(),
            )
        }

        private fun requireWindowsCompatible(toolchain: GpgToolchain) {
            require(!toolchain.isKnownIncompatibleOnWindows()) {
                "Configured GnuPG toolchain is not supported for Windows E2E tests: " +
                    toolchain.describe()
            }
        }

        private fun GpgToolchain.isKnownIncompatibleOnWindows(): Boolean {
            if (!isWindowsHost()) return false

            val gpgPath = gpg.toAbsolutePath()
                .normalize()
                .toString()
                .replace('/', '\\')
                .lowercase()
            return listOf(
                "\\git\\usr\\bin\\",
                "\\msys64\\usr\\bin\\",
                "\\cygwin\\bin\\",
                "\\cygwin64\\bin\\",
            ).any { it in gpgPath }
        }

        private fun executableName(name: String): String =
            if (isWindowsHost()) "$name.exe" else name
    }
}

private fun String.parseGpgconfListDirValue(): String =
    lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.let { line ->
            if (line.startsWith("agent-socket:")) {
                line.substringAfter("agent-socket:")
            } else {
                line
            }
        }
        ?: error("gpgconf did not report an agent-socket:\n$this")

internal fun isWindowsHost(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
