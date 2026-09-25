package com.artemchep.keyguard.feature.gpgagent.help

import org.junit.Assume.assumeTrue
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacosSandboxGpgAgentSetupCommandTest {
    @Test
    fun `setup is repeatable and quotes paths without touching default keyring`() = withFixture { fixture ->
        val first = fixture.run()
        assertEquals(0, first.exitCode, first.output)
        val config = fixture.gpgHome.resolve("gpg.conf")
        assertEquals("no-autostart\n", Files.readString(config))
        val firstConfig = Files.readAttributes(config, "unix:ino")["ino"]

        val repeated = fixture.run()
        assertEquals(0, repeated.exitCode, repeated.output)
        assertEquals(fixture.socket, Files.readSymbolicLink(fixture.endpoint))
        assertEquals("no-autostart\n", Files.readString(config))
        assertEquals(firstConfig, Files.readAttributes(config, "unix:ino")["ino"])
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture.gpgHome)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)))
        assertEquals("original keyring\n", Files.readString(fixture.defaultHome.resolve("pubring.kbx")))
        assertEquals(
            listOf("pubring.kbx"),
            Files.list(fixture.defaultHome).use { files -> files.map { it.fileName.toString() }.toList() },
        )
        assertEquals(
            listOf("--create-socketdir", "--list-dirs", "--create-socketdir", "--list-dirs"),
            Files.readAllLines(fixture.gpgconfLog).map { it.substringAfter('\t').substringBefore('\t') },
        )
        assertTrue(
            Files.readAllLines(fixture.gpgconfLog).all { it.substringBefore('\t') == fixture.gpgHome.toString() },
        )
    }

    @Test
    fun `setup uses relocated endpoint reported by external gpgconf`() = withFixture { fixture ->
        fixture.endpoint = fixture.root.resolve("relocated ' sockets/S.gpg-agent")
        val result = fixture.run()
        assertEquals(0, result.exitCode, result.output)
        assertEquals(fixture.socket, Files.readSymbolicLink(fixture.endpoint))
        assertFalse(Files.exists(fixture.gpgHome.resolve("S.gpg-agent"), NOFOLLOW_LINKS))
    }

    @Test
    fun `setup accepts gpgconf creation failure when its reported home fallback is usable`() = withFixture { fixture ->
        fixture.createSocketDirectoryFails = true
        val result = fixture.run()
        assertEquals(0, result.exitCode, result.output)
        assertEquals(fixture.socket, Files.readSymbolicLink(fixture.endpoint))
        assertEquals("no-autostart\n", Files.readString(fixture.gpgHome.resolve("gpg.conf")))
        assertEquals("original keyring\n", Files.readString(fixture.defaultHome.resolve("pubring.kbx")))
        assertEquals(
            listOf("--create-socketdir", "--list-dirs"),
            Files.readAllLines(fixture.gpgconfLog).map { it.substringAfter('\t').substringBefore('\t') },
        )
    }

    @Test
    fun `setup refuses an unavailable endpoint after gpgconf creation failure`() = withFixture { fixture ->
        fixture.createSocketDirectoryFails = true
        fixture.endpoint = fixture.root.resolve("missing-socket-directory/S.gpg-agent")
        val result = fixture.run()
        assertTrue(result.exitCode != 0, result.output)
        assertTrue(result.output.contains("Not a regular directory"), result.output)
        assertFalse(Files.exists(fixture.endpoint.parent, NOFOLLOW_LINKS))
        assertFalse(Files.exists(fixture.gpgHome.resolve("gpg.conf"), NOFOLLOW_LINKS))
    }

    @Test
    fun `setup preserves existing configuration and adds no duplicate no-autostart option`() = withFixture { fixture ->
        fixture.prepareGpgHome()
        val config = fixture.gpgHome.resolve("gpg.conf")
        Files.writeString(config, "armor\n  no-autostart # Keep the native agent\n")
        val result = fixture.run()
        assertEquals(0, result.exitCode, result.output)
        assertEquals("armor\n  no-autostart # Keep the native agent\n", Files.readString(config))
    }

    @Test
    fun `setup refuses unrelated endpoint file link or live socket without changing configuration`() {
        for (kind in listOf("file", "symlink", "socket")) withFixture { fixture ->
            fixture.prepareGpgHome()
            val config = fixture.gpgHome.resolve("gpg.conf")
            Files.writeString(config, "armor\n")
            val unrelated = fixture.root.resolve("unrelated")
            Files.writeString(unrelated, "preserve me\n")
            val listener = when (kind) {
                "file" -> {
                    Files.writeString(fixture.endpoint, "unrelated endpoint\n")
                    null
                }
                "symlink" -> {
                    Files.createSymbolicLink(fixture.endpoint, unrelated)
                    null
                }
                else -> ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                    bind(UnixDomainSocketAddress.of(fixture.endpoint))
                }
            }
            try {
                val originalIdentity = Files.readAttributes(fixture.endpoint, "unix:ino", NOFOLLOW_LINKS)["ino"]
                val result = fixture.run()
                assertTrue(result.exitCode != 0, "$kind: ${result.output}")
                assertTrue(result.output.contains("Refusing to replace"), result.output)
                assertEquals(
                    originalIdentity,
                    Files.readAttributes(fixture.endpoint, "unix:ino", NOFOLLOW_LINKS)["ino"],
                )
                assertEquals("armor\n", Files.readString(config))
                assertEquals("preserve me\n", Files.readString(unrelated))
            } finally {
                listener?.close()
            }
        }
    }

    @Test
    fun `setup refuses a symlink configuration and leaves its target unchanged`() = withFixture { fixture ->
        fixture.prepareGpgHome()
        val unrelated = fixture.root.resolve("other.conf")
        Files.writeString(unrelated, "preserve me\n")
        Files.createSymbolicLink(fixture.gpgHome.resolve("gpg.conf"), unrelated)
        val result = fixture.run()
        assertTrue(result.exitCode != 0, result.output)
        assertEquals("preserve me\n", Files.readString(unrelated))
        assertFalse(Files.exists(fixture.endpoint, NOFOLLOW_LINKS))
    }

    @Test
    fun `setup refuses unsafe managed home permissions without tightening them`() = withFixture { fixture ->
        fixture.prepareGpgHome()
        Files.setPosixFilePermissions(fixture.gpgHome, PosixFilePermissions.fromString("rwxr-xr-x"))
        val result = fixture.run()
        assertTrue(result.exitCode != 0, result.output)
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(fixture.gpgHome)))
        assertFalse(Files.exists(fixture.endpoint, NOFOLLOW_LINKS))
        assertFalse(Files.exists(fixture.gpgconfLog))
    }

    @Test
    fun `setup rejects relative and multiline socket paths before generating a script`() {
        for (socket in listOf("relative/socket", "/tmp/socket\ncommand", "/tmp/socket\rcommand", "/tmp/socket\u0000")) {
            assertFailsWith<IllegalArgumentException> { macosSandboxGpgAgentSetupCommand(socket) }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        assumeTrue(File.separatorChar == '/')
        Fixture().use(block)
    }

    private class Fixture : AutoCloseable {
        // Short paths leave room for quoted names within Darwin's 103-byte socket limit.
        val root: Path = Files.createTempDirectory(
            Path.of(if (File("/private/tmp").isDirectory) "/private/tmp" else "/tmp"),
            "kgpg-",
        )
        private val home = root.resolve("home ' \$(literal)")
        val defaultHome: Path = home.resolve(".gnupg")
        val gpgHome: Path = home.resolve(".keyguard/gnupg")
        val socket: Path = root.resolve("agent ' \$(literal)")
        var endpoint: Path = gpgHome.resolve("S.gpg-agent")
        var createSocketDirectoryFails = false
        val gpgconfLog: Path = root.resolve("gpgconf.log")
        private val bin = Files.createDirectory(root.resolve("bin"))
        private val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)

        init {
            Files.createDirectories(defaultHome)
            Files.writeString(defaultHome.resolve("pubring.kbx"), "original keyring\n")
            listener.bind(UnixDomainSocketAddress.of(socket))
            executable("gpgconf", """
                #!/bin/sh
                set -eu
                [ "${'$'}1" = --homedir ] && [ "${'$'}2" = "${'$'}GNUPGHOME" ] || exit 90
                [ "${'$'}GNUPGHOME" = "${'$'}EXPECTED_GPG_HOME" ] || exit 91
                printf '%s\t%s\t%s\n' "${'$'}2" "${'$'}3" "${'$'}{4-}" >> "${'$'}FAKE_GPGCONF_LOG"
                case "${'$'}3" in
                  --create-socketdir)
                    [ "${'$'}#" = 3 ] || exit 92
                    if [ "${'$'}FAKE_CREATE_FAILURE" = 1 ]; then
                      printf '%s\n' 'no /run/user dir, using homedir as fallback' >&2
                      exit 1
                    fi
                    mkdir -p -m 700 "${'$'}(dirname "${'$'}FAKE_ENDPOINT")"
                    ;;
                  --list-dirs) [ "${'$'}4" = agent-socket ] || exit 93; printf '%s\n' "${'$'}FAKE_ENDPOINT" ;;
                  *) exit 94 ;;
                esac
            """.trimIndent())
            if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
                // Exercise the macOS script on Linux CI with only BSD stat syntax translated.
                executable("stat", """
                    #!/bin/sh
                    [ "${'$'}1" = -f ] || exit 95
                    case "${'$'}2" in
                      %u) exec /usr/bin/stat -c %u "${'$'}3" ;;
                      %Lp) exec /usr/bin/stat -c %a "${'$'}3" ;;
                      *) exit 96 ;;
                    esac
                """.trimIndent())
            }
        }

        fun prepareGpgHome() {
            Files.createDirectories(gpgHome)
            Files.setPosixFilePermissions(gpgHome, PosixFilePermissions.fromString("rwx------"))
        }

        fun run(): Result {
            val process = ProcessBuilder("/bin/sh", "-c", macosSandboxGpgAgentSetupCommand(socket.toString()))
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$bin:/usr/bin:/bin"
                    environment()["HOME"] = home.toString()
                    environment()["GNUPGHOME"] = defaultHome.toString()
                    environment()["EXPECTED_GPG_HOME"] = gpgHome.toString()
                    environment()["FAKE_GPGCONF_LOG"] = gpgconfLog.toString()
                    environment()["FAKE_ENDPOINT"] = endpoint.toString()
                    environment()["FAKE_CREATE_FAILURE"] = if (createSocketDirectoryFails) "1" else "0"
                }
                .start()
            try {
                process.outputStream.close()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Timed out running native macOS GPG setup")
                return Result(process.exitValue(), process.inputStream.readBytes().decodeToString())
            } finally {
                process.destroyForcibly()
            }
        }

        private fun executable(name: String, content: String) {
            val path = Files.writeString(bin.resolve(name), content + "\n")
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }

        override fun close() {
            listener.close()
            Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private data class Result(val exitCode: Int, val output: String)
}
