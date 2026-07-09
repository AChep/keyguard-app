package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentManager
import com.artemchep.keyguard.common.service.agent.macosDevAgentSocketPath
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the lifecycle of the keyguard-gpg-agent Rust binary.
 *
 * The generic process/socket lifecycle lives in [AgentManager]; this
 * subclass wires up the GPG-specific IPC server and routes approval
 * prompts to the UI via [approvalRequests].
 */
class GpgAgentManager(
    logRepository: LogRepository,
    cryptoGenerator: CryptoGenerator,
    private val getVaultSession: GetVaultSession,
    private val getGpgAgentApprovalWindow: GetGpgAgentApprovalWindow,
    private val getGpgAgentFilter: GetGpgAgentFilter,
    private val gpgAgentPublicKeyRepository: GpgAgentPublicKeyRepository,
) : AgentManager(
    logRepository = logRepository,
    cryptoGenerator = cryptoGenerator,
    config = Config(
        tag = "GpgAgentManager",
        displayName = "GPG agent",
        binaryBaseName = "keyguard-gpg-agent",
        ipcSocketPrefix = "keyguard-gpg-ipc",
        agentSocketArg = "--gpg-socket",
        agentSocketLogLabel = "GPG socket",
        defaultAgentSocketPath = macosDevAgentSocketPath("gnupg/S.gpg-agent"),
    ),
) {
    val approvalRequests = EventFlow<GpgAgentApprovalRequest>()

    val requestsFlow = approvalRequests

    suspend fun resolveGpgAgentSocketPathOrNull(): Path? = withContext(Dispatchers.IO) {
        val platform = CurrentPlatform
        val home = managedGpgHomePath(platform)
            ?: return@withContext null

        runCatching {
            require(!isDefaultUserGpgHome(home)) {
                "Refusing to serve the default user GnuPG home: $home"
            }

            Files.createDirectories(home)
            restrictOwnerOnlyDirectory(home)
            ensureNoAutostart(home)

            val socket = resolveGpgconfAgentSocket(home)
            prepareGpgconfSocketDirectory(home, socket)
            logRepository.post(
                TAG,
                "Resolved GPG socket with gpgconf: $socket",
                LogLevel.INFO,
            )
            socket
        }.getOrElse { e ->
            val isFlatpak = platform is Platform.Desktop.Linux && platform.isFlatpak
            logRepository.post(
                TAG,
                if (isFlatpak) {
                    "Could not resolve Flatpak host-visible GPG socket with gpgconf: ${e.message}"
                } else {
                    "Could not resolve GPG socket with gpgconf; falling back to bundled default: ${e.message}"
                },
                LogLevel.WARNING,
            )
            if (isFlatpak) {
                throw IllegalStateException(
                    "Could not resolve the Flatpak host-visible GPG socket. " +
                        "Check that gpgconf is available and the Flatpak has xdg-run/gnupg access.",
                    e,
                )
            }
            null
        }
    }

    override fun createIpcServer(
        authToken: ByteArray,
        sessionId: String,
        scope: CoroutineScope,
    ): IpcServerRunner {
        val ipcServer = GpgAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = getVaultSession,
            getGpgAgentApprovalWindow = getGpgAgentApprovalWindow,
            getGpgAgentFilter = getGpgAgentFilter,
            gpgAgentPublicKeyRepository = gpgAgentPublicKeyRepository,
            authToken = authToken,
            scope = scope,
            sessionId = sessionId,
            onApprovalRequest = { operation, caller, keyName, keyFingerprint, keygrip ->
                val deferred = CompletableDeferred<Boolean>()
                val request = GpgAgentApprovalRequest(
                    operation = operation,
                    keyName = keyName,
                    keyFingerprint = keyFingerprint,
                    keygrip = keygrip,
                    caller = caller,
                    notificationTag = null,
                    expiresAt = Clock.System.now() + GpgAgentIpcServer.APPROVAL_TIMEOUT_MS.milliseconds,
                    deferred = deferred,
                )
                approvalRequests.emit(request)
                awaitWithExpiry(request, reason = "desktop_gpg_approval_timeout")
            },
        )
        return IpcServerRunner { endpoint, onReady ->
            ipcServer.start(endpoint, onReady = onReady)
        }
    }

    private fun managedGpgHomePath(platform: Platform): Path? = when (platform) {
        is Platform.Desktop.MacOS -> macosDevAgentSocketPath("gnupg")
            ?: Path.of(System.getProperty("user.home"))
                .resolve("Library")
                .resolve("Group Containers")
                .resolve("com.artemchep.keyguard")
                .resolve("gnupg")

        is Platform.Desktop.Linux -> linuxManagedGpgHomePath(platform)

        else -> null
    }

    private fun linuxManagedGpgHomePath(platform: Platform.Desktop.Linux): Path? {
        if (platform.isFlatpak) {
            return flatpakManagedGpgHomePath()
        }

        envPath("XDG_RUNTIME_DIR")?.let { runtimeDir ->
            return runtimeDir.resolve("keyguard-gpg-agent")
        }

        val uid = currentUnixUid()
            ?: return null
        return Path.of("/tmp", "keyguard-$uid", "gnupg")
    }

    private fun flatpakManagedGpgHomePath(): Path =
        envPath("XDG_DATA_HOME")
            ?.resolve("gnupg")
            ?: Path.of(System.getProperty("user.home"))
                .resolve(".var")
                .resolve("app")
                .resolve(flatpakId())
                .resolve("data")
                .resolve("gnupg")

    private fun resolveGpgconfAgentSocket(home: Path): Path {
        val result = runGpgconf(home, "--list-dirs", "agent-socket")
        require(result.exitCode == 0) {
            "gpgconf --list-dirs agent-socket exited ${result.exitCode}: ${result.stdout}"
        }

        val socket = result.stdout
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { line ->
                if (line.startsWith("agent-socket:")) {
                    line.substringAfter("agent-socket:")
                } else {
                    line
                }
            }
            ?: error("gpgconf did not report an agent-socket")
        val path = Path.of(socket)
        require(path.isAbsolute) {
            "gpgconf reported a non-absolute agent socket: $socket"
        }
        return path
    }

    private fun prepareGpgconfSocketDirectory(home: Path, socket: Path) {
        val homePath = home.toAbsolutePath().normalize()
        val socketPath = socket.toAbsolutePath().normalize()
        if (socketPath.startsWith(homePath)) return

        val result = runGpgconf(home, "--create-socketdir")
        require(result.exitCode == 0) {
            "gpgconf --create-socketdir exited ${result.exitCode}: ${result.stdout}"
        }
    }

    private fun runGpgconf(
        home: Path,
        vararg args: String,
    ): GpgconfResult {
        val command = buildList {
            add("gpgconf")
            add("--homedir")
            add(home.toAbsolutePath().toString())
            addAll(args)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()

        val output = CompletableFuture.supplyAsync {
            process.inputStream.readBytes().decodeToString()
        }
        if (!process.waitFor(GPGCONF_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Timed out running ${command.joinToString(" ")}")
        }
        return GpgconfResult(
            exitCode = process.exitValue(),
            stdout = output.get(GPGCONF_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    private fun ensureNoAutostart(home: Path) {
        val commonConf = home.resolve("common.conf")
        val existing = runCatching {
            Files.readString(commonConf)
        }.getOrDefault("")
        val hasNoAutostart = existing
            .lineSequence()
            .any { it.trim() == "no-autostart" }

        if (!hasNoAutostart) {
            val prefix = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
            Files.writeString(commonConf, existing + prefix + "no-autostart\n")
        }
        restrictOwnerOnlyFile(commonConf)
    }

    private fun restrictOwnerOnlyDirectory(dir: Path) {
        runCatching {
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIRECTORY_PERMISSIONS)
        }
    }

    private fun restrictOwnerOnlyFile(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(file, OWNER_ONLY_FILE_PERMISSIONS)
        }
    }

    private fun envPath(name: String): Path? =
        System.getenv(name)
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }

    private fun flatpakId(): String =
        System.getenv("FLATPAK_ID")
            ?.takeIf { it.isNotBlank() }
            ?: FLATPAK_APP_ID_FALLBACK

    private fun isDefaultUserGpgHome(home: Path): Boolean {
        val userHome = System.getProperty("user.home")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?: return false
        val defaultHome = userHome
            .resolve(".gnupg")
            .toAbsolutePath()
            .normalize()
        return home.toAbsolutePath().normalize() == defaultHome
    }

    private fun currentUnixUid(): Long? = runCatching {
        Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid") as Number
    }.getOrNull()?.toLong()

    private data class GpgconfResult(
        val exitCode: Int,
        val stdout: String,
    )

    companion object {
        private const val FLATPAK_APP_ID_FALLBACK = "com.artemchep.keyguard"
        private const val GPGCONF_TIMEOUT_SECONDS = 15L
        private const val TAG = "GpgAgentManager"

        private val OWNER_ONLY_DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )

        private val OWNER_ONLY_FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
