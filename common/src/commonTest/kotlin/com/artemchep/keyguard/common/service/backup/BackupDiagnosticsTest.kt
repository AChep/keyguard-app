package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BackupDiagnosticsTest {
    @Test
    fun `diagnostics emits debug messages when enabled`() = runTest {
        val logRepository = TestBackupLogRepository()
        val diagnostics = BackupDiagnostics(
            logRepository = logRepository,
            enabled = true,
        )

        diagnostics.backupRequestStarted(
            trigger = "manual",
            includeAttachments = true,
            retentionMaxSnapshots = 30,
        )

        val entry = logRepository.entries.single()
        assertEquals(LogLevel.DEBUG, entry.level)
        assertEquals("BackupDiagnostics", entry.tag)
        assertTrue(entry.message.contains("backup_request_started"))
        assertTrue(entry.message.contains("trigger=manual"))
        assertTrue(entry.message.contains("include_attachments=true"))
    }

    @Test
    fun `diagnostics does not emit messages when disabled`() = runTest {
        val logRepository = TestBackupLogRepository()
        val diagnostics = BackupDiagnostics(
            logRepository = logRepository,
            enabled = false,
        )

        diagnostics.backupSkipped(
            trigger = "manual",
            reason = "backup_not_configured",
        )

        assertEquals(emptyList(), logRepository.entries)
    }

    @Test
    fun `diagnostics redacts sensitive paths and urls from errors`() = runTest {
        val logRepository = TestBackupLogRepository()
        val diagnostics = BackupDiagnostics(
            logRepository = logRepository,
            enabled = true,
        )

        diagnostics.backupRequestFailed(
            trigger = "manual",
            error = IllegalStateException(
                "Failed to write /tmp/keyguard-backups/repo.zip from https://example.com/file " +
                        "while opening file.txt",
            ),
        )

        val message = logRepository.entries.single().message
        assertTrue(message.contains("backup_request_failed"))
        assertTrue(message.contains("<redacted-path>"))
        assertTrue(message.contains("<redacted-url>"))
        assertTrue(message.contains("<redacted-file>"))
        assertTrue(!message.contains("/tmp/keyguard-backups"))
        assertTrue(!message.contains("https://example.com/file"))
        assertTrue(!message.contains("file.txt"))
    }
}

internal class TestBackupLogRepository : LogRepository {
    val entries = mutableListOf<TestBackupLogEntry>()

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        entries += TestBackupLogEntry(
            tag = tag,
            message = message,
            level = level,
        )
    }
}

internal data class TestBackupLogEntry(
    val tag: String,
    val message: String,
    val level: LogLevel,
)

internal val TestBackupLogRepository.messages: List<String>
    get() = entries.map { it.message }
