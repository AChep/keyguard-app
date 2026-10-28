package com.artemchep.keyguard.feature.agent.help

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.agent.findAgentBinary
import com.artemchep.keyguard.common.service.gpgagent.GpgconfRunner
import com.artemchep.keyguard.common.service.gpgagent.ManagedGpgHome
import com.artemchep.keyguard.common.service.sshagent.SshAgentManager
import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.FLATPAK_INFO_PATH
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.flatpakInfoValue
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Composable
internal actual fun rememberAgentSetupRunner(agent: SetupAgent): AgentSetupRunner? {
    val directory = koinInject<DataDirectory>()
    val sshAgentManager = koinInject<SshAgentManager>()
    return remember(agent, directory, sshAgentManager) {
        val binary = findAgentBinary("keyguard")
        val flatpak = (CurrentPlatform as? Platform.Desktop.Linux)?.isFlatpak == true
        when {
            binary == null -> null
            !flatpak -> AgentSetupRunner.App(DesktopAgentSetupClient(agent, binary, directory, sshAgentManager))
            // Flatpak can't reach host files, so the user runs the bundled CLI from a host terminal.
            else -> AgentSetupRunner.HostCli {
                withContext(Dispatchers.IO) {
                    val loader = requireNotNull(flatpakLoader()) { "Unsupported architecture" }.toRealPath()
                    val command = flatpakHostCommand(binary, loader, File(FLATPAK_INFO_PATH).readText())
                    AgentSetupHostCli(
                        command = requireNotNull(command) { "Flatpak app or runtime path is unavailable" },
                        endpoint = agentSetupEndpoint(agent, directory, sshAgentManager),
                    )
                }
            }
        }
    }
}

/** The dynamic loader that Linux builds of the CLI name. */
private fun flatpakLoader(): Path? = when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> Path.of("/lib64/ld-linux-x86-64.so.2")
    "aarch64" -> Path.of("/lib/ld-linux-aarch64.so.1")
    else -> null
}

/**
 * Runs [binary] with the runtime's loader and libraries, so hosts that can't
 * run it directly, such as NixOS or musl distributions, run it too. Unlike
 * LD_LIBRARY_PATH, the loader option doesn't reach the shells the CLI starts.
 */
internal fun flatpakHostCommand(binary: Path, loader: Path, info: String): ImmutableList<String>? {
    val hostBinary = flatpakHostPath(binary, info)
    val hostLoader = flatpakHostPath(loader, info)
    if (hostBinary == null || hostLoader == null) return null
    return persistentListOf(
        hostLoader.toString(),
        "--library-path",
        hostLoader.parent.toString(),
        hostBinary.toString(),
    )
}

private val FLATPAK_MOUNTS = listOf(
    Path.of("/app") to "app-path",
    Path.of("/usr") to "runtime-path",
)

/**
 * Flatpak mounts the app at /app and its runtime at /usr; `app-path` and
 * `runtime-path` in /.flatpak-info are those directories on the host.
 */
internal fun flatpakHostPath(path: Path, info: String): Path? {
    val (mount, key) = FLATPAK_MOUNTS.firstOrNull { (mount, _) -> path.startsWith(mount) } ?: return null
    val host = flatpakInfoValue(info, "Instance", key)?.let(Path::of)?.takeIf { it.isAbsolute }
    return host?.resolve(mount.relativize(path))
}

/** The SSH socket or the GnuPG home that the agent serves. */
private fun agentSetupEndpoint(
    agent: SetupAgent,
    directory: DataDirectory,
    sshAgentManager: SshAgentManager,
): String = when (agent) {
    SetupAgent.SSH -> {
        val executable = requireNotNull(sshAgentManager.defaultBinaryPath) {
            "SSH agent executable is unavailable"
        }
        val override = sshAgentManager.defaultAgentSocketPath
        val command = listOf(executable.toString(), "--print-socket") +
            (override?.let { listOf("--ssh-socket", it.toString()) } ?: emptyList())
        runSetupCommand(command).trim()
    }
    SetupAgent.GPG -> requireNotNull(ManagedGpgHome.ofPlatformOrNull(CurrentPlatform, directory)) {
        "GnuPG home is unavailable"
    }.path.toString()
}

private class DesktopAgentSetupClient(
    private val agent: SetupAgent,
    private val binary: Path,
    private val directory: DataDirectory,
    private val sshAgentManager: SshAgentManager,
) : AgentSetupClient {
    private val gpgconf by lazy { GpgconfRunner() }

    private val endpointMutex = Mutex()
    private var cachedEndpoint: String? = null

    /** The endpoint is fixed for the app's lifetime, so resolve it once. */
    private suspend fun endpoint(): String = endpointMutex.withLock {
        cachedEndpoint ?: withContext(Dispatchers.IO) {
            agentSetupEndpoint(agent, directory, sshAgentManager)
        }.also { cachedEndpoint = it }
    }

    override suspend fun preview(shells: Set<SetupShell>, host: String): AgentSetupPlan =
        plan(listOf("plan") + agentSetupOptions(agent, endpoint(), shells, host))

    override suspend fun undo(): AgentSetupPlan = plan(listOf("undo", agent.argument))

    override suspend fun apply(plan: AgentSetupPlan) {
        run(listOf("apply"), plan.json)
    }

    override suspend fun verify(): String {
        val executable = withContext(Dispatchers.IO) {
            if (agent == SetupAgent.SSH) "ssh-add" else gpgconf.agentClientExecutable.toString()
        }
        return run(listOf("verify") + agentSetupOptions(agent, endpoint()) + listOf("--executable", executable))
    }

    private suspend fun plan(arguments: List<String>): AgentSetupPlan =
        withContext(Dispatchers.IO) {
            parseAgentSetupPlan(runSetupCommand(agents(arguments)))
        }

    private suspend fun run(arguments: List<String>, input: String? = null): String =
        withContext(Dispatchers.IO) {
            runSetupCommand(agents(arguments), input)
        }

    /** The helper reports failures as JSON codes, which the app translates. */
    private fun agents(arguments: List<String>) =
        listOf(binary.toString(), "agents", "--json") + arguments
}

private val planJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class PlanDto(
    val edits: List<EditDto>,
    val conflicts: List<ConflictDto>,
    val undo: Boolean,
    val overrides: List<OverrideDto>,
)

@Serializable
private data class EditDto(
    val path: String,
    val before: String?,
    val after: String?,
)

@Serializable
private data class ConflictDto(
    val code: String,
    val path: String,
)

@Serializable
private data class ErrorDto(
    val code: String,
    val path: String? = null,
    val detail: String? = null,
)

@Serializable
private data class OverrideDto(
    val setting: String,
    val path: String,
    val line: Int,
)

internal fun parseAgentSetupPlan(raw: String): AgentSetupPlan {
    val element = planJson.parseToJsonElement(raw)
    val version = element.jsonObject["version"]?.jsonPrimitive?.intOrNull
    if (version != SETUP_PLAN_VERSION) {
        updateRequired("Unsupported setup plan version $version")
    }
    val plan = planJson.decodeFromJsonElement<PlanDto>(element)
    return AgentSetupPlan(
        json = raw,
        edits = plan.edits.map { AgentSetupEdit(it.path, it.before, it.after) }.toImmutableList(),
        conflicts = plan.conflicts.map { conflict ->
            AgentSetupConflict(
                kind = AgentSetupConflict.Kind.entries.firstOrNull { it.code == conflict.code }
                    ?: updateRequired("Unsupported setup conflict ${conflict.code}"),
                path = conflict.path,
            )
        }.toImmutableList(),
        undo = plan.undo,
        overrides = plan.overrides.map { override ->
            require(override.line > 0)
            AgentSetupOverride(
                setting = override.setting,
                path = override.path,
                line = override.line,
            )
        }.toImmutableList(),
    )
}

private fun updateRequired(detail: String): Nothing =
    throw AgentSetupException(AgentSetupError(AgentSetupError.Kind.UPDATE_REQUIRED, detail = detail))

/** Reads the helper's JSON error; any other output is the detail of a generic failure. */
internal fun parseAgentSetupError(raw: String): AgentSetupError {
    val text = raw.trim()
    val error = runCatchingNonFatal { planJson.decodeFromString<ErrorDto>(text.lineSequence().last()) }
        .getOrNull()
    val kind = AgentSetupError.Kind.entries.firstOrNull { it.code == error?.code }
    return if (error != null && kind != null) {
        AgentSetupError(kind = kind, path = error.path, detail = error.detail)
    } else {
        AgentSetupError(AgentSetupError.Kind.FAILED, detail = text.ifEmpty { null })
    }
}

private const val SETUP_PLAN_VERSION = 4
private const val MAX_SETUP_OUTPUT = 4 * 1024 * 1024
/**
 * Twice the helper's own worst case: planning and applying each run up to two
 * 15-second PowerShell probes. The app must not kill an apply that is writing.
 */
private const val SETUP_TIMEOUT_SECONDS = 60L

private fun runSetupCommand(command: List<String>, input: String? = null): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = CompletableFuture.supplyAsync {
        process.inputStream.readNBytes(MAX_SETUP_OUTPUT + 1)
    }
    try {
        process.outputStream.use { stream -> input?.let { stream.write(it.encodeToByteArray()) } }
        check(process.waitFor(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Agent setup timed out" }
        val bytes = output.get(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        check(bytes.size <= MAX_SETUP_OUTPUT) { "Agent setup output exceeds the size limit" }
        val text = bytes.decodeToString()
        if (process.exitValue() != 0) throw AgentSetupException(parseAgentSetupError(text))
        return text
    } finally {
        if (process.isAlive) process.destroyForcibly()
        output.cancel(true)
    }
}
