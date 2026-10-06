package com.artemchep.keyguard.test

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository

internal data class LogEntry(
    val tag: String,
    val message: String,
    val level: LogLevel,
)

/**
 * A [LogRepository] that keeps what it was told, for the paths that are supposed to
 * degrade into a log line rather than a crash or a report.
 *
 * Both [post] and [add] record synchronously, so a test can assert the entries right
 * after the call; asserting the level is how a test tells "handled" from "silently
 * swallowed". Use `LogRepositoryBridge(emptyList())` when nothing needs to be recorded.
 */
internal class RecordingLogRepository : LogRepository {
    val entries = mutableListOf<LogEntry>()

    val messages: List<String>
        get() = entries.map { it.message }

    override fun post(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        entries += LogEntry(tag = tag, message = message, level = level)
    }

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) = post(tag = tag, message = message, level = level)
}
