package com.artemchep.keyguard.feature.agent.help

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSetupHostCliTest {
    private val binary = Path.of("/app/lib/app/resources/keyguard")
    private val loader = Path.of("/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2")
    private val info = """
        [Application]
        name=com.artemchep.keyguard

        [Instance]
        instance-id=1234
        app-path=/var/lib/flatpak/app/com.artemchep.keyguard/x86_64/stable/abc/files
        runtime-path=/var/lib/flatpak/runtime/org.freedesktop.Platform/x86_64/25.08/def/files
    """.trimIndent()

    /** Flatpak paths are POSIX paths, which Windows doesn't treat as absolute. */
    @BeforeTest
    fun requirePosixPaths() = assumeTrue(File.separatorChar == '/')

    @Test
    fun `bundled CLI runs on the host with the runtime loader`() {
        val lib = "/var/lib/flatpak/runtime/org.freedesktop.Platform/x86_64/25.08/def/files/lib/x86_64-linux-gnu"
        assertEquals(
            persistentListOf(
                "$lib/ld-linux-x86-64.so.2",
                "--library-path",
                lib,
                "/var/lib/flatpak/app/com.artemchep.keyguard/x86_64/stable/abc/files/lib/app/resources/keyguard",
            ),
            flatpakHostCommand(binary, loader, info),
        )
    }

    @Test
    fun `missing or relative mount paths and paths outside the mounts are not mapped`() {
        val appOnly = "[Instance]\napp-path=/home/alex/.local/share/flatpak/app/id/files\n"
        assertNull(flatpakHostPath(binary, "[Application]\napp-path=/var/lib/flatpak/app/id/files\n"))
        assertNull(flatpakHostPath(binary, "[Instance]\napp-path=files\n"))
        assertNull(flatpakHostPath(Path.of("/application/keyguard"), info))
        assertNull(flatpakHostPath(Path.of("/lib64/ld-linux-x86-64.so.2"), info))
        assertNull(flatpakHostCommand(binary, loader, appOnly))
    }
}
