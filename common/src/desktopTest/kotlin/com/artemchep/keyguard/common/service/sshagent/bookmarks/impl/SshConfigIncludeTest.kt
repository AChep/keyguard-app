package com.artemchep.keyguard.common.service.sshagent.bookmarks.impl

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshConfigIncludeTest {
    private val home: Path = Path.of("/home/me").toAbsolutePath()
    private val file: Path = home.resolve(".ssh").resolve("keyguard").resolve("config")

    private fun includes(text: String) = sshConfigIncludes(
        text = text,
        file = file,
        userHome = home,
    )

    @Test
    fun `finds the block of the setup`() {
        val text = """
            # >>> Keyguard ssh-bookmarks >>>
            Include "~/.ssh/keyguard/config"
            # <<< Keyguard ssh-bookmarks <<<
            Host *
              IdentityAgent "/tmp/agent.sock"
        """.trimIndent()
        assertTrue(includes(text))
    }

    @Test
    fun `finds an absolute path and a path relative to the ssh directory`() {
        assertTrue(includes("Include $file\n"))
        assertTrue(includes("Include keyguard/config\n"))
    }

    @Test
    fun `finds an equals sign and any case of the keyword`() {
        assertTrue(includes("  INCLUDE=~/.ssh/keyguard/config\n"))
        assertTrue(includes("include = ~/.ssh/keyguard/config\n"))
    }

    @Test
    fun `finds a quoted path with spaces`() {
        val home = Path.of("/home/my name").toAbsolutePath()
        val text = "Include \"~/.ssh/keyguard/config\"\n"
        assertTrue(
            sshConfigIncludes(
                text = text,
                file = home.resolve(".ssh").resolve("keyguard").resolve("config"),
                userHome = home,
            ),
        )
        assertTrue(includes("Include '~/.ssh/keyguard/config'\n"))
        assertTrue(includes("Include ~/.ssh/other/../keyguard/config\n"))
    }

    @Test
    fun `finds one of several arguments`() {
        assertTrue(includes("Include ~/.ssh/config.d/* ~/.ssh/keyguard/config\n"))
    }

    @Test
    fun `finds a pattern`() {
        assertTrue(includes("Include ~/.ssh/*/config\n"))
        assertTrue(includes("Include ~/.ssh/keyguar?/conf*\n"))
    }

    @Test
    fun `ignores comments and invalid lines`() {
        assertFalse(includes(""))
        assertFalse(includes("# Include ~/.ssh/keyguard/config\n"))
        assertFalse(includes("Include ~/.ssh/other # ~/.ssh/keyguard/config\n"))
        // OpenSSH rejects an unterminated quote.
        assertFalse(includes("Include \"~/.ssh/keyguard/config\n"))
    }

    @Test
    fun `ignores other files`() {
        assertFalse(includes("Include ~/.ssh/keyguard/config.bak\n"))
        assertFalse(includes("Include ~/.ssh/config.d/*\n"))
        assertFalse(includes("Include ~/*/config\n"))
        // A pattern matches a leading dot only literally.
        assertFalse(includes("Include ~/*/keyguard/config\n"))
        assertFalse(includes("IdentityFile ~/.ssh/keyguard/config\n"))
        assertFalse(includes("Includes ~/.ssh/keyguard/config\n"))
    }

    @Test
    fun `ignores an include that applies to some hosts`() {
        val text = """
            Host work
              User me
            Include "~/.ssh/keyguard/config"
        """.trimIndent()
        assertFalse(includes(text))
        assertFalse(includes("Match all\nInclude ~/.ssh/keyguard/config\n"))
        assertFalse(includes("  host=* \nInclude ~/.ssh/keyguard/config\n"))
    }

    @Test
    fun `follows nested includes`() {
        val home = createTempDirectory("ssh-config-include")
        try {
            val ssh = home.resolve(".ssh").createDirectories()
            val file = ssh.resolve("keyguard").resolve("config")
            fun includes(text: String) = sshConfigIncludes(text, file, home)

            ssh.resolve("config.d").createDirectories()
            ssh.resolve("config.d").resolve("00-keyguard")
                .writeText("Include ~/.ssh/keyguard/config\n")
            assertTrue(includes("Include ~/.ssh/config.d/*\n"))
            assertTrue(includes("Include config.d/00-keyguard\n"))

            ssh.resolve("config.d").resolve("00-keyguard")
                .writeText("Host work\n  Include ~/.ssh/keyguard/config\n")
            assertFalse(includes("Include ~/.ssh/config.d/*\n"))

            // A loop ends.
            ssh.resolve("config").writeText("Include ~/.ssh/config\n")
            assertFalse(includes("Include ~/.ssh/config\n"))
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
