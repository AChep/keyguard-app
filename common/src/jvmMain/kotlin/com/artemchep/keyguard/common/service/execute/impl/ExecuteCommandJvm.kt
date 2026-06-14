package com.artemchep.keyguard.common.service.execute.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import java.io.File
import org.kodein.di.DirectDI

class ExecuteCommandJvm(
) : ExecuteCommand {
    private val executor: ExecuteCommand? = when (CurrentPlatform) {
        is Platform.Desktop.Windows -> ExecuteCommandCmd()

        is Platform.Desktop.MacOS,
        is Platform.Desktop.Linux,
        -> ExecuteCommandBash()

        is Platform.Mobile.Android -> ExecuteCommandSh()

        // Not supported.
        else -> null
    }

    override val interpreter: String? get() = executor?.interpreter

    constructor(
        directDI: DirectDI,
    ) : this(
    )

    override fun invoke(command: String): IO<Unit> = ioEffect {
        requireNotNull(executor) {
            "Unsupported platform."
        }
            .invoke(command)
            .bind()
    }
}

//
// Actual implementation
//

private class ExecuteCommandCmd : ExecuteCommand {
    override val interpreter: String get() = "cmd"

    override fun invoke(command: String): IO<Unit> = ioEffect {
        val arr = arrayOf(
            "cmd",
            "/c",
            command,
        )
        executeCommand(arr)
    }
}

private class ExecuteCommandBash : ExecuteCommand {
    override val interpreter: String get() = "bash"

    override fun invoke(command: String): IO<Unit> = ioEffect {
        val arr = arrayOf(
            "bash",
            "-c",
            command,
        )
        executeCommand(arr)
    }
}

private class ExecuteCommandSh : ExecuteCommand {
    override val interpreter: String get() = "sh"

    override fun invoke(command: String): IO<Unit> = ioEffect {
        val arr = arrayOf(
            "sh",
            "-c",
            command,
        )
        executeCommand(arr)
    }
}

private fun executeCommand(array: Array<String>) {
    val nullDevice = getNullDevice()
    ProcessBuilder(*array)
        .redirectInput(ProcessBuilder.Redirect.from(nullDevice))
        .redirectOutput(ProcessBuilder.Redirect.to(nullDevice))
        .redirectError(ProcessBuilder.Redirect.to(nullDevice))
        .start()
}

private fun getNullDevice(): File = when (CurrentPlatform) {
    is Platform.Desktop.Windows -> File("NUL")
    else -> File("/dev/null")
}
