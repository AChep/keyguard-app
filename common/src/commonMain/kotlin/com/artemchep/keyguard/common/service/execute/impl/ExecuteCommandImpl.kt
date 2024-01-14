package com.artemchep.keyguard.common.service.execute.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.execute.ExecuteCommand
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.coroutineScope
import org.kodein.di.DirectDI

class ExecuteCommandImpl(
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

/**
 * Executes the given command, handling the exit
 * code and throwing an exception if the program
 * had failed.
 */
private suspend fun executeCommand(array: Array<String>) {
    val process = run {
        Runtime.getRuntime().exec(array)
    }
    coroutineScope {
        val errorText = runCatching {
            process.errorStream.reader().use {
                it.readText()
            }.trim()
        }.getOrNull()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException(errorText)
        }
    }
}
