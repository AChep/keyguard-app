package com.artemchep.keyguard.common.service.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentIpcSocketPathTest {
    @Test
    fun `gpg ipc socket path stays below unix domain path limit`() {
        val path = createAgentIpcSocketPath("keyguard-gpg-ipc")
        try {
            assertEquals("ipc.sock", path.socketPath.fileName.toString())
            assertTrue(
                path.socketPath.toString().length < 90,
                "IPC socket path should leave room under Unix domain socket limits: ${path.socketPath}",
            )
            if (!isWindows()) {
                assertTrue(
                    path.directory.startsWith(Path.of("/tmp")),
                    "Unix IPC socket path should use short temp root: ${path.socketPath}",
                )
            }
        } finally {
            Files.deleteIfExists(path.socketPath)
            Files.deleteIfExists(path.directory)
        }
    }

    @Test
    fun `unix ipc socket directory is private`() {
        if (isWindows()) {
            return
        }

        val path = createAgentIpcSocketPath("keyguard-gpg-ipc")
        try {
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(path.directory),
            )
        } finally {
            Files.deleteIfExists(path.socketPath)
            Files.deleteIfExists(path.directory)
        }
    }
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
