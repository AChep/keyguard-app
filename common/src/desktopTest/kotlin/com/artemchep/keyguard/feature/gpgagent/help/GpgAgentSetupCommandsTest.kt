package com.artemchep.keyguard.feature.gpgagent.help

import com.artemchep.keyguard.common.service.gpgagent.linuxManagedGpgHome
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpgAgentSetupCommandsTest {
    @Test
    fun `linux setup exports the same lexical home as the application`() {
        assumeTrue(File.separatorChar == '/')
        val dataHomes = listOf(
            null,
            "",
            "  ",
            "relative/data",
            "~/data",
            "/data",
            "/data/",
            "/data///",
            "/data//space dir/ключі///",
            "/data/./linked/../directory/",
            "/data/with 'quotes' and \"quotes\"/\$value/",
            "/",
            "//",
        )
        for (userHome in listOf("/home/alice", "/home//alice///")) {
            for (dataHome in dataHomes) {
                val expected = linuxManagedGpgHome(Path.of(userHome), dataHome).path.toString()
                val actual = configuredHome(userHome, dataHome)
                assertEquals(expected, actual, "HOME=$userHome, XDG_DATA_HOME=$dataHome")
            }
        }
    }

    private fun configuredHome(userHome: String, dataHome: String?): String {
        val command = GPG_AGENT_SETUP_LINUX_HOME_COMMAND + "\nprintf '%s' \"\$GNUPGHOME\""
        val builder = ProcessBuilder("/bin/sh", "-eu", "-c", command)
            .redirectErrorStream(true)
        builder.environment()["HOME"] = userHome
        if (dataHome == null) {
            builder.environment().remove("XDG_DATA_HOME")
        } else {
            builder.environment()["XDG_DATA_HOME"] = dataHome
        }
        val process = builder.start()
        try {
            process.outputStream.close()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Timed out running the Linux setup command")
            val output = process.inputStream.readBytes().decodeToString()
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            process.destroyForcibly()
        }
    }
}
