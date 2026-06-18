package com.artemchep.keyguard.common.service.execute.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ExecuteCommandJvmTest {
    @Test
    fun commandWithLargeStdoutCompletes() = runBlocking {
        val marker = Files.createTempFile("keyguard-execute-command", ".done")
        marker.deleteIfExists()

        try {
            val command = createLargeStdoutCommand(marker.toString())
            val executeCommand = ExecuteCommandJvm()

            withTimeout(5.seconds) {
                executeCommand(command).bind()
            }
            withTimeout(30.seconds) {
                while (!marker.exists()) {
                    delay(50)
                }
            }
        } finally {
            marker.deleteIfExists()
        }
    }

    private fun createLargeStdoutCommand(marker: String): String =
        when (CurrentPlatform) {
            is Platform.Desktop.Windows -> {
                "(for /L %i in (1,1,200000) do @echo x) & type nul > ${cmdQuote(marker)}"
            }

            else -> {
                "yes x | head -c 200000; : > ${shellQuote(marker)}"
            }
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun cmdQuote(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}
