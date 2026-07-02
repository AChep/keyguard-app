package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.BitwardenSyncDiagnostics
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalEntitySnapshot
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerEntitySnapshot
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntitySyncPlan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitwardenSyncDiagnosticsTest {
    @Test
    fun `diagnostics emits debug messages when enabled`() = runTest {
        val logRepository = TestLogRepository()
        val diagnostics =
            BitwardenSyncDiagnostics(
                logRepository = logRepository,
                enabled = true,
            )

        diagnostics.revisionPrecheck(
            accountId = "account-1",
            serverRevisionDate = "2024-01-01T00:00:00Z",
        )

        assertEquals(LogLevel.DEBUG, logRepository.entries.single().level)
        assertEquals("SyncDiagnostics.bitwarden", logRepository.entries.single().tag)
        assertTrue(logRepository.entries.single().message.contains("revision_precheck"))
        assertTrue(logRepository.entries.single().message.contains("account_id=account-1"))
    }

    @Test
    fun `diagnostics does not emit messages when disabled`() = runTest {
        val logRepository = TestLogRepository()
        val diagnostics =
            BitwardenSyncDiagnostics(
                logRepository = logRepository,
                enabled = false,
            )

        diagnostics.revisionPrecheck(
            accountId = "account-1",
            serverRevisionDate = "2024-01-01T00:00:00Z",
        )

        assertEquals(emptyList(), logRepository.entries)
    }

    @Test
    fun `entity plan diagnostics formats action counts centrally`() = runTest {
        val logRepository = TestLogRepository()
        val diagnostics =
            BitwardenSyncDiagnostics(
                logRepository = logRepository,
                enabled = true,
            )
        val local = syncedLocal(localId = "local-1", remoteId = "remote-1")
        val server = TestServer(id = "remote-1", revisionDate = T1)

        diagnostics.entityPlanBuilt(
            entityName = "ciphers",
            plan =
                EntitySyncPlan(
                    actions =
                        listOf(
                            SyncAction.UpdateLocally(
                                localId = "local-1",
                                serverId = "remote-1",
                            ),
                            SyncAction.PushToServer(
                                localId = "local-2",
                                serverId = null,
                            ),
                        ),
                    localSnapshot =
                        LocalEntitySnapshot(
                            entitiesByLocalId = mapOf(local.id to local),
                            metadata = listOf(TestSyncStrategy.toLocalItemMeta(local)),
                        ),
                    serverSnapshot =
                        ServerEntitySnapshot(
                            entitiesById = mapOf(server.id to server),
                            metadata = listOf(TestSyncStrategy.toServerItemMeta(server)),
                        ),
                ),
        )

        val message = logRepository.entries.single().message
        assertTrue(message.contains("entity_plan_built entity=ciphers"))
        assertTrue(message.contains("update_locally=1"))
        assertTrue(message.contains("push_to_server=1"))
    }

    @Test
    fun `upload diagnostics include raw ids without file paths or names`() = runTest {
        val logRepository = TestLogRepository()
        val diagnostics =
            BitwardenSyncDiagnostics(
                logRepository = logRepository,
                enabled = true,
            )

        diagnostics.cipherAttachmentUploadStarted(
            cipherLocalId = "cipher-local-1",
            cipherRemoteId = "cipher-remote-1",
            attachmentLocalId = "attachment-local-1",
            attachmentRemoteId = "attachment-remote-1",
            encryptedSize = 42L,
        )
        diagnostics.sendFileUploadStarted(
            sendLocalId = "send-local-1",
            sendRemoteId = "send-remote-1",
            fileRemoteId = "file-remote-1",
            encryptedSize = 84L,
            isCreate = true,
        )

        val messages = logRepository.entries.map { it.message }
        assertTrue(messages.any { it.contains("cipher_local_id=cipher-local-1") })
        assertTrue(messages.any { it.contains("attachment_remote_id=attachment-remote-1") })
        assertTrue(messages.any { it.contains("send_remote_id=send-remote-1") })
        assertTrue(messages.none { it.contains("/tmp/") })
        assertTrue(messages.none { it.contains("fileName") })
    }
}

internal class TestLogRepository : LogRepository {
    val entries = mutableListOf<TestLogEntry>()

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        entries += TestLogEntry(
            tag = tag,
            message = message,
            level = level,
        )
    }
}

internal data class TestLogEntry(
    val tag: String,
    val message: String,
    val level: LogLevel,
)
