package com.artemchep.keyguard.common.service.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

sealed interface AgentIpcEndpoint {
    val argument: String
    val displayName: String

    data class UnixSocket(
        val socketPath: Path,
        val directory: Path,
    ) : AgentIpcEndpoint {
        override val argument: String
            get() = socketPath.toAbsolutePath().toString()

        override val displayName: String
            get() = socketPath.toString()
    }

    data class WindowsPipe(
        val pipeName: String,
    ) : AgentIpcEndpoint {
        override val argument: String
            get() = pipeName

        override val displayName: String
            get() = pipeName
    }
}

internal data class AgentIpcSocketPath(
    val socketPath: Path,
    val directory: Path,
)

internal fun createAgentIpcEndpoint(prefix: String): AgentIpcEndpoint =
    if (isWindows()) {
        createWindowsAgentIpcEndpoint(prefix)
    } else {
        val path = createAgentIpcSocketPath(prefix)
        AgentIpcEndpoint.UnixSocket(
            socketPath = path.socketPath,
            directory = path.directory,
        )
    }

/**
 * Creates a short private directory for a Unix-domain IPC socket.
 *
 * macOS and Linux impose small sockaddr_un path limits. In particular, the
 * JVM temp directory on macOS commonly lives under /var/folders/... and can
 * consume most of that budget before the socket filename is appended.
 */
internal fun createAgentIpcSocketPath(prefix: String): AgentIpcSocketPath {
    val directory = createAgentIpcSocketDirectory(prefix)
    return AgentIpcSocketPath(
        socketPath = directory.resolve("ipc.sock"),
        directory = directory,
    )
}

internal fun cleanupAgentIpcEndpoint(endpoint: AgentIpcEndpoint) {
    when (endpoint) {
        is AgentIpcEndpoint.UnixSocket -> {
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(endpoint.directory)
        }

        is AgentIpcEndpoint.WindowsPipe -> {
            // Named pipes are kernel objects; closing all handles removes them.
        }
    }
}

private fun createWindowsAgentIpcEndpoint(prefix: String): AgentIpcEndpoint.WindowsPipe {
    val suffix = UUID.randomUUID()
        .toString()
        .replace("-", "")
    return AgentIpcEndpoint.WindowsPipe(
        pipeName = "\\\\.\\pipe\\$prefix-$suffix",
    )
}

// Unix-domain-socket only. On Windows [createAgentIpcEndpoint] returns a
// named pipe instead, so these helpers are never reached there.
private fun createAgentIpcSocketDirectory(prefix: String): Path {
    val root = agentIpcSocketTempRoot()
    return Files.createTempDirectory(
        root,
        "$prefix-",
        PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS),
    )
}

private fun agentIpcSocketTempRoot(): Path {
    val shortTempRoot = Path.of("/tmp")
    if (Files.isDirectory(shortTempRoot)) {
        return shortTempRoot
    }

    return Path.of(System.getProperty("java.io.tmpdir"))
}

internal fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private val OWNER_ONLY_DIRECTORY_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)
