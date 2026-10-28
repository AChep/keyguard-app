package com.artemchep.keyguard.feature.agent.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSetupCommandsTest {
    @Test
    fun `setup lists shells in a fixed order and limits SSH to one host`() {
        val cli = listOf("/rt/ld.so", "--library-path", "/rt", "/app/keyguard")
        assertEquals(
            "env /rt/ld.so --library-path /rt /app/keyguard agents setup --agent ssh --socket /run/ssh.sock " +
                "--shells bash,zsh --host git.example.com",
            hostCliSetupCommand(
                cli = cli,
                agent = SetupAgent.SSH,
                endpoint = "/run/ssh.sock",
                shells = setOf(SetupShell.ZSH, SetupShell.BASH),
                host = "git.example.com",
            ),
        )
        assertEquals(
            listOf("--agent", "gpg", "--gpg-home", "/home/gnupg"),
            agentSetupOptions(SetupAgent.GPG, "/home/gnupg", host = "git.example.com"),
        )
    }

    @Test
    fun `commands quote paths for the shell`() {
        val cli = listOf("/home/o'neil/keyguard")
        val executable = "env '/home/o'\\''neil/keyguard'"
        val home = "'/home/o'\\''neil/.var/app/id/data/gnupg'"
        assertEquals(
            "$executable agents setup --agent gpg --gpg-home $home --shells zsh",
            hostCliSetupCommand(cli, SetupAgent.GPG, "/home/o'neil/.var/app/id/data/gnupg", setOf(SetupShell.ZSH), ""),
        )
        assertEquals("$executable agents undo gpg --review", hostCliUndoCommand(cli, SetupAgent.GPG))
        assertEquals(
            "env GNUPGHOME=$home gpg-connect-agent --no-autostart 'KEYINFO --list' /bye",
            externalCheckCommand(SetupAgent.GPG, "/home/o'neil/.var/app/id/data/gnupg"),
        )
    }

    @Test
    fun `check names the endpoint only when given`() {
        assertEquals("ssh-add -L", externalCheckCommand(SetupAgent.SSH, null))
        assertEquals(
            "env SSH_AUTH_SOCK=/run/ssh.sock ssh-add -L",
            externalCheckCommand(SetupAgent.SSH, "/run/ssh.sock"),
        )
    }

    @Test
    fun `host names one SSH host`() {
        assertTrue(isAgentSetupHostValid(" work-server "))
        listOf("", "*.example.com", "host\nHost *", "host name").forEach { host ->
            assertFalse(isAgentSetupHostValid(host), host)
        }
    }
}
