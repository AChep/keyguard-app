package com.artemchep.keyguard.desktop.instance

import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.util.instance.InstanceConfig
import com.artemchep.keyguard.util.instance.InstanceCoordinator
import com.artemchep.keyguard.util.instance.InstanceResult
import com.artemchep.keyguard.util.instance.PrimaryInstance
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.nio.file.Files
import java.nio.file.Path

/** Process-owned activation receiver. Hiding or disposing a window does not release ownership. */
// Forward receiver failures to the UI and release ownership after any construction failure.
@Suppress("TooGenericExceptionCaught")
internal class DesktopInstance private constructor(
    private val primary: PrimaryInstance,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    val activations = requests.receiveAsFlow()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ stop() }, "Keyguard instance shutdown"))
        Thread({
            try {
                while (primary.awaitActivation()) {
                    requests.trySend(Unit)
                }
                requests.close()
            } catch (e: Exception) {
                requests.close(e)
            }
        }, "Keyguard instance activation").apply {
            isDaemon = true
            start()
        }
    }

    /** Wake receivers, retaining ownership until the JVM and its background workers exit. */
    fun stop() {
        try {
            primary.stop()
        } finally {
            requests.close()
        }
    }

    companion object {
        /** Null means another instance accepted activation. No vault services are started. */
        fun acquire(): DesktopInstance? {
            val identity = if (isRelease) "keyguard" else "keyguard-dev"
            val dataDirectory = Path.of(DataDirectory().dataBlocking()).toAbsolutePath()
            val configuration = InstanceConfig(
                coordinationDirectory = instanceCoordinationDirectory(dataDirectory).toString(),
                runtimeDirectory = instanceRuntimeDirectory().toString(),
                identity = identity,
            )
            val primary = when (val result = InstanceCoordinator.acquireOrActivate(configuration)) {
                is InstanceResult.Primary -> result.instance
                InstanceResult.Activated -> return null
            }
            return try {
                DesktopInstance(primary)
            } catch (e: Exception) {
                primary.close()
                throw e
            }
        }
    }
}

internal fun instanceCoordinationDirectory(dataDirectory: Path): Path {
    val directory = dataDirectory.toAbsolutePath().normalize()
    // Erase data removes the data, config, and cache trees before the JVM exits.
    // Keep the permanent lock outside those trees so its pathname stays intact.
    return directory.resolveSibling("${directory.fileName}.instance")
}

internal fun instanceRuntimeDirectory(
    osName: String = System.getProperty("os.name"),
    temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
    xdgRuntimeDirectory: String? = System.getenv("XDG_RUNTIME_DIR"),
    flatpakId: String? = System.getenv("FLATPAK_ID"),
): Path {
    if (osName.startsWith("Windows", ignoreCase = true)) {
        // A rooted path such as /tmp has no drive prefix on Windows, even if it exists.
        return temporaryDirectory.toAbsolutePath()
    }
    val isLinux = osName.startsWith("Linux", ignoreCase = true)
    val xdgRuntime = xdgRuntimeDirectory?.takeIf { isLinux && it.isNotBlank() }
    return if (xdgRuntime != null) {
        val root = Path.of(xdgRuntime).toAbsolutePath()
        val appId = flatpakId?.takeIf(String::isNotBlank)
        if (appId != null) root.resolve("app").resolve(appId) else root
    } else {
        // Keep Unix socket paths short. Rust creates an owner-only random directory below this root.
        val shortTemporaryRoot = Path.of("/tmp")
        if (Files.isDirectory(shortTemporaryRoot)) {
            shortTemporaryRoot
        } else {
            temporaryDirectory.toAbsolutePath()
        }
    }
}
