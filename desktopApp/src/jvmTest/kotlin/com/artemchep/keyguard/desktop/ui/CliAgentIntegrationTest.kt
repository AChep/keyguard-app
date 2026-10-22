package com.artemchep.keyguard.desktop.ui

import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CliAgentIntegrationTest {
    @Test
    fun `a Flatpak CLI is sandboxed`() {
        assertEquals(emptySet(), cliCapabilities(flatpak = true))
        assertEquals(setOf(CliPlatformCapability.Unsandboxed), cliCapabilities(flatpak = false))
    }

    @Test
    fun `MCP connects to the configured agent socket`() {
        val socket = Path.of("/tmp/keyguard-501/cli.sock")

        assertEquals(
            listOf("mcp", "--socket", socket.toAbsolutePath().toString()),
            environmentMcpArguments("keyguard", flatpak = false, socketPath = socket),
        )
    }

    @Test
    fun `MCP keeps the platform default when the agent has no socket override`() {
        assertEquals(listOf("mcp"), environmentMcpArguments("keyguard", flatpak = false, socketPath = null))
    }

    @Test
    fun `Flatpak forwards the subcommand and socket argument after the application id`() {
        val command = "/app/bin/keyguard"
        val socket = Path.of("/run/user/1000/app/com.artemchep.keyguard/cli.sock")
        val prefix = listOf("run", "--command=$command", "com.artemchep.keyguard", "mcp")

        assertEquals(prefix, environmentMcpArguments(command, flatpak = true, socketPath = null))
        assertEquals(
            prefix + listOf("--socket", socket.toAbsolutePath().toString()),
            environmentMcpArguments(command, flatpak = true, socketPath = socket),
        )
    }
}
