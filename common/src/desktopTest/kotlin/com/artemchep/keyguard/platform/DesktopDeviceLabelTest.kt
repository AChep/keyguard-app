package com.artemchep.keyguard.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDeviceLabelTest {
    @Test
    fun `macOS prefers its friendly computer name then its Bonjour name`() = runTest {
        val calls = mutableListOf<List<String>>()
        val command: (List<String>) -> String? = {
            calls += it
            if (it.last() == "ComputerName") "Artem's MacBook Pro\n" else "artems-macbook"
        }
        assertEquals("Artem's MacBook Pro", desktopDeviceLabel(Platform.Desktop.MacOS.Jvm, command = command))
        assertEquals(listOf(listOf("/usr/sbin/scutil", "--get", "ComputerName")), calls)
        assertEquals(
            "artems-macbook",
            desktopDeviceLabel(Platform.Desktop.MacOS.Jvm, command = {
                if (it.last() == "ComputerName") throw SecurityException() else "artems-macbook"
            }),
        )
    }

    @Test
    fun `Windows uses the computer name environment variable`() = runTest {
        assertEquals(
            "WORK-LAPTOP",
            desktopDeviceLabel(
                Platform.Desktop.Windows,
                environment = { if (it == "COMPUTERNAME") "WORK-LAPTOP" else null },
            ),
        )
        assertNull(desktopDeviceLabel(Platform.Desktop.Windows, environment = { null }))
    }

    @Test
    fun `Linux prefers a pretty hostname to its kernel hostname`() = runTest {
        val files = mapOf(
            "/etc/machine-info" to "PRETTY_HOSTNAME=\"Work laptop\"\n",
            "/proc/sys/kernel/hostname" to "work-laptop\n",
        )
        assertEquals("Work laptop", desktopDeviceLabel(Platform.Desktop.Linux.native, readFile = files::get))
    }

    @Test
    fun `Flatpak falls back when host metadata is inaccessible`() = runTest {
        val platform = Platform.Desktop.Linux(isFlatpak = true)
        assertEquals(
            "framework",
            desktopDeviceLabel(platform, readFile = {
                if (it == "/proc/sys/kernel/hostname") "framework\n" else throw SecurityException()
            }),
        )
        assertEquals(
            "fallback-host",
            desktopDeviceLabel(platform, readFile = { null }, environment = { "fallback-host" }),
        )
        assertNull(desktopDeviceLabel(platform, readFile = { "localhost" }, environment = { null }))
    }

    @Test
    fun `Linux skips invalid metadata before consulting the hostname file`() = runTest {
        val files = mapOf(
            "/etc/machine-info" to "PRETTY_HOSTNAME=\"invalid\u202ename\"",
            "/proc/sys/kernel/hostname" to "localhost",
            "/etc/hostname" to "workstation\n",
        )
        assertEquals("workstation", desktopDeviceLabel(Platform.Desktop.Linux.native, readFile = files::get))
    }

    @Test
    fun `machine info supports quoting and escapes without expanding shell expressions`() {
        assertEquals("Work laptop", prettyHostname("# comment\nPRETTY_HOSTNAME='Work laptop'\n"))
        assertEquals("Artem's laptop", prettyHostname("PRETTY_HOSTNAME=\"Artem's laptop\""))
        assertEquals("Work \"laptop\"", prettyHostname("PRETTY_HOSTNAME=\"Work \\\"laptop\\\"\""))
        assertEquals("\$USER `hostname`", prettyHostname("PRETTY_HOSTNAME=\"\$USER `hostname`\""))
        assertEquals("last", prettyHostname("PRETTY_HOSTNAME=first\nPRETTY_HOSTNAME=last"))
        assertNull(prettyHostname("PRETTY_HOSTNAME=\"unterminated"))
        assertNull(prettyHostname("CHASSIS=laptop"))
    }

    @Test
    fun `a successful command returns stdout and closes its streams`() {
        val process = DeviceLabelProcess("Artem's MacBook Pro\n".encodeToByteArray())
        assertEquals("Artem's MacBook Pro\n", readDeviceLabelProcess(process))
        assertTrue(process.inputClosed)
        assertTrue(process.errorClosed)
        assertFalse(process.destroyed)
    }

    @Test
    fun `failed and oversized command output never becomes a name`() {
        assertNull(readDeviceLabelProcess(DeviceLabelProcess("error".encodeToByteArray(), exitCode = 1)))
        assertNull(readDeviceLabelProcess(DeviceLabelProcess(ByteArray(4097) { 'a'.code.toByte() })))
    }

    @Test
    fun `a timed out command is terminated and its streams are closed`() {
        val process = DeviceLabelProcess(byteArrayOf(), finished = false)
        assertNull(readDeviceLabelProcess(process))
        assertTrue(process.destroyed)
        assertTrue(process.inputClosed)
        assertTrue(process.errorClosed)
    }

    @Test
    fun `invalid command encoding falls through to the next source`() = runTest {
        assertEquals(
            "Mac",
            firstDeviceLabel(
                { readDeviceLabelProcess(DeviceLabelProcess(byteArrayOf(0xC3.toByte()))) },
                { "Mac" },
            ),
        )
    }
}

private class DeviceLabelProcess(
    output: ByteArray,
    private val exitCode: Int = 0,
    private val finished: Boolean = true,
) : Process() {
    var destroyed = false
    var inputClosed = false
    var errorClosed = false

    private val stdout = object : ByteArrayInputStream(output) {
        override fun close() {
            inputClosed = true
            super.close()
        }
    }

    private val stderr = object : ByteArrayInputStream("stderr must not become a name".encodeToByteArray()) {
        override fun close() {
            errorClosed = true
            super.close()
        }
    }

    override fun getInputStream() = stdout

    override fun getErrorStream() = stderr

    override fun getOutputStream() = ByteArrayOutputStream()

    override fun waitFor() = exitCode

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        assertEquals(1L, timeout)
        assertEquals(TimeUnit.SECONDS, unit)
        return finished
    }

    override fun exitValue() = exitCode

    override fun isAlive() = !finished && !destroyed

    override fun destroy() {
        destroyed = true
    }
}
