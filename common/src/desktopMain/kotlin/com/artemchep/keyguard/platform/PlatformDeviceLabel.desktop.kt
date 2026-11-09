package com.artemchep.keyguard.platform

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal actual suspend fun platformDeviceLabel(context: LeContext): String? = withContext(Dispatchers.IO) {
    desktopDeviceLabel(CurrentPlatform)
}

internal suspend fun desktopDeviceLabel(
    platform: Platform,
    readFile: (String) -> String? = { path -> File(path).takeIf(File::isFile)?.readText() },
    environment: (String) -> String? = System::getenv,
    command: (List<String>) -> String? = ::readDeviceLabelCommand,
): String? = when (platform) {
    is Platform.Desktop.MacOS -> firstDeviceLabel(
        { command(listOf("/usr/sbin/scutil", "--get", "ComputerName")) },
        { command(listOf("/usr/sbin/scutil", "--get", "LocalHostName")) },
    )

    is Platform.Desktop.Windows -> firstDeviceLabel({ environment("COMPUTERNAME") })

    is Platform.Desktop.Linux -> firstDeviceLabel(
        { readFile("/etc/machine-info")?.let(::prettyHostname) },
        { readFile("/proc/sys/kernel/hostname") },
        { readFile("/etc/hostname") },
        { environment("HOSTNAME") },
    )

    else -> firstDeviceLabel({ environment("HOSTNAME") }, { environment("COMPUTERNAME") })
}

/** Reads shell-style assignments as data; never evaluates their contents. */
internal fun prettyHostname(machineInfo: String): String? = machineInfo.lineSequence()
    .map(String::trim)
    .filter { !it.startsWith('#') && it.substringBefore('=').trim() == "PRETTY_HOSTNAME" }
    .mapNotNull { line ->
        val value = line.substringAfter('=', "").trim()
        when {
            value.startsWith('"') && value.endsWith('"') && value.length >= 2 -> {
                unescapeMachineInfoValue(value.substring(1, value.lastIndex))
            }
            value.startsWith('\'') && value.endsWith('\'') && value.length >= 2 ->
                value.substring(1, value.lastIndex)
            value.none { it == '\'' || it == '"' || it == '\\' } -> value
            else -> null
        }
    }
    .lastOrNull()

private fun unescapeMachineInfoValue(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char == '\\' && value.getOrNull(index) in doubleQuoteEscapes) {
            append(value[index++])
        } else {
            append(char)
        }
    }
}

/** scutil emits a short UTF-8 name; stderr and failed commands must never become names. */
internal fun readDeviceLabelCommand(command: List<String>): String? = readDeviceLabelProcess(
    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(),
)

internal fun readDeviceLabelProcess(process: Process): String? = try {
    process.outputStream.close()
    if (!process.waitFor(DEVICE_LABEL_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
        null
    } else {
        val output = process.inputStream.readNBytes(MAX_DEVICE_LABEL_COMMAND_BYTES + 1)
        output.takeIf { it.size <= MAX_DEVICE_LABEL_COMMAND_BYTES }?.decodeToString(throwOnInvalidSequence = true)
    }
} finally {
    if (process.isAlive) process.destroyForcibly()
    process.inputStream.close()
    process.errorStream.close()
}

private val doubleQuoteEscapes = setOf('"', '\\', '$', '`')
private const val DEVICE_LABEL_COMMAND_TIMEOUT_SECONDS = 1L
private const val MAX_DEVICE_LABEL_COMMAND_BYTES = 4 * 1024
