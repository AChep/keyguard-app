package com.artemchep.keyguard.android.credentialexchange

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
 * Shared by the credential-exchange transport tests: both the untrusted-request parse
 * and the account-mirror read answer a failure this way, and asserting the level is
 * how those tests tell "handled" from "silently swallowed".
 */
internal class RecordingLogRepository : LogRepository {
    val entries = mutableListOf<LogEntry>()

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
