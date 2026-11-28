package com.artemchep.keyguard.desktop.ui

import com.artemchep.keyguard.common.service.cli.CliPlatformCapability
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CliAgentIntegrationTest {
    @Test
    fun `MSIX uses the package family alias across updates and side by side installs`() {
        val localAppData = Path.of("Local App Data").toAbsolutePath()
        val store = "14896ArtemChepurnyi.KeyguardApp_ytzb51mpgqjgj"
        val sideload = "ArtemChepurnyi.Keyguard_abcd123456789"
        for (family in listOf(store, sideload)) {
            val expected = localAppData.resolve("Microsoft/WindowsApps/$family/keyguard.exe")
            for (version in listOf("3.10.0.0", "3.11.0.0")) {
                val bundled = Path.of("WindowsApps/${family}_$version/app/resources/keyguard.exe")
                assertEquals(expected, externalCliPath(bundled, family, localAppData.toString(), "unused"))
            }
        }
    }

    @Test
    fun `MSIX finds the per-user alias when LocalAppData is absent`() {
        val family = "ArtemChepurnyi.Keyguard_abcd123456789"
        val home = Path.of("User Home").toAbsolutePath()
        val expected = home.resolve("AppData/Local/Microsoft/WindowsApps/$family/keyguard.exe")
        val bundled = Path.of("WindowsApps/Keyguard/app/resources/keyguard.exe")
        assertEquals(expected, externalCliPath(bundled, family, null, home.toString()))
    }

    @Test
    fun `unpackaged installs keep the bundled CLI path`() {
        val bundled = Path.of("app/resources/keyguard")
        assertEquals(bundled.toAbsolutePath(), externalCliPath(bundled, null, null, "unused"))
    }

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
