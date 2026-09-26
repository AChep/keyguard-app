package com.artemchep.keyguard.buildplugins.testing

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import javax.tools.ToolProvider

class E2ePreflightTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `collects command output without blocking on a full pipe`() {
        val output = runner().requireRuns(
            command = fixtureCommand("verbose"),
            timeout = Duration.ofSeconds(10),
            requirement = "Fixture must run",
        )

        assertTrue(output.length > 100_000)
        assertTrue(output.endsWith("version: 5.1"))
    }

    @Test
    fun `reports command exit code output and setup hint`() {
        val failure = assertThrows(GradleException::class.java) {
            runner().requireRuns(
                command = fixtureCommand("fail"),
                timeout = Duration.ofSeconds(10),
                requirement = "Fixture must run",
                setupHint = "Install the pinned dependencies.",
            )
        }

        assertTrue(failure.message.orEmpty().contains("exited with 7"))
        assertTrue(failure.message.orEmpty().contains("fixture stderr"))
        assertTrue(failure.message.orEmpty().contains("Install the pinned dependencies."))
    }

    @Test
    fun `reports missing executable with setup hint`() {
        val failure = assertThrows(GradleException::class.java) {
            runner().requireRuns(
                command = listOf(File(temporaryFolder.root, "missing-tool").absolutePath),
                timeout = Duration.ofSeconds(1),
                requirement = "Fixture must run",
                setupHint = "Select the Python virtualenv.",
            )
        }

        assertTrue(failure.message.orEmpty().contains("could not be started"))
        assertTrue(failure.message.orEmpty().contains("Select the Python virtualenv."))
    }

    @Test
    fun `terminates a timed out command`() {
        val pidFile = File(temporaryFolder.root, "child.pid")
        val failure = assertThrows(GradleException::class.java) {
            runner().requireRuns(
                command = fixtureCommand("sleep", pidFile.absolutePath),
                timeout = Duration.ofSeconds(1),
                requirement = "Fixture must run",
            )
        }

        assertTrue(failure.message.orEmpty().contains("Timed out"))
        val child = ProcessHandle.of(pidFile.readText().trim().toLong())
        assertFalse(child.map(ProcessHandle::isAlive).orElse(false))
    }

    @Test
    fun `honors Windows executable extensions and rejects missing tools`() {
        val directory = temporaryFolder.newFolder("tools")
        File(directory, "ssh-add.exe").apply {
            writeText("fixture")
            setExecutable(true)
        }
        requireExecutableOnPath("ssh-add", directory.absolutePath, ".EXE;.CMD", windows = true)

        assertThrows(GradleException::class.java) {
            requireExecutableOnPath("ssh-keygen", directory.absolutePath, ".EXE;.CMD", windows = true)
        }
    }

    @Test
    fun `preserves explicit GPG executable directories`() {
        val directory = temporaryFolder.newFolder("gpg")
        assertEquals("gpg", gpgTool("gpg", null, windows = false))
        assertEquals(File(directory, "gpgconf").absolutePath, gpgTool("gpgconf", directory.path, windows = false))
        assertEquals(File(directory, "gpg.exe").absolutePath, gpgTool("gpg", directory.path, windows = true))
    }

    @Test
    fun `accepts only the supported WebDAV major version`() {
        requireWebDav5("hacdias webdav\nversion: 5.8.1")
        val failure = assertThrows(GradleException::class.java) {
            requireWebDav5("version: 6.0.0")
        }
        assertTrue(failure.message.orEmpty().contains("v5.x"))
        assertTrue(failure.message.orEmpty().contains("6.0.0"))
    }

    @Test
    fun `Python setup hint retains the selected executable and requirements`() {
        val requirements = File(temporaryFolder.root, "requirements.txt")
        val message = pythonSetupMessage("/venv/bin/python", requirements)
        assertTrue(message.contains("/venv/bin/python -m pip install -r ${requirements.absolutePath}"))
        assertTrue(message.contains("-PkdbxE2ePython="))
    }

    private fun runner() = E2eCommandRunner(temporaryFolder.newFolder())

    private fun fixtureCommand(vararg arguments: String): List<String> {
        val directory = temporaryFolder.newFolder("fixture")
        val source = File(directory, "PreflightFixture.java").apply {
            writeText(
                """
                public final class PreflightFixture {
                    public static void main(String[] args) throws Exception {
                        switch (args[0]) {
                            case "verbose":
                                System.out.print("x".repeat(200000));
                                System.err.println("version: 5.1");
                                break;
                            case "fail":
                                System.err.println("fixture stderr");
                                System.exit(7);
                                break;
                            case "sleep":
                                java.nio.file.Files.writeString(
                                    java.nio.file.Path.of(args[1]),
                                    Long.toString(ProcessHandle.current().pid())
                                );
                                Thread.sleep(30000);
                                break;
                        }
                    }
                }
                """.trimIndent(),
            )
        }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Preflight tests require a JDK." }
        assertEquals(0, compiler.run(null, null, null, "-d", directory.absolutePath, source.absolutePath))
        val javaName = if (File.separatorChar == '\\') "java.exe" else "java"
        val executable = File(System.getProperty("java.home"), "bin/$javaName")
        return listOf(executable.absolutePath, "-cp", directory.absolutePath, "PreflightFixture") + arguments
    }
}
