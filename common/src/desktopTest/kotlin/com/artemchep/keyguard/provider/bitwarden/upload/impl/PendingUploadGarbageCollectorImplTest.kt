package com.artemchep.keyguard.provider.bitwarden.upload.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ACCOUNT_ID
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestLogRepository
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.insertLocalCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.testSend
import com.artemchep.keyguard.provider.bitwarden.sync.v2.testSendFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import com.artemchep.keyguard.provider.bitwarden.upload.SweepCall
import com.artemchep.keyguard.provider.bitwarden.upload.SweepRecordingEncryptedFilePendingUploadService
import com.artemchep.keyguard.provider.bitwarden.upload.pendingUploadFile
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@Suppress("FunctionNaming")
class PendingUploadGarbageCollectorImplTest {
    @Test
    fun `collector sweeps both namespaces using only references from the requested account`() =
        runTest {
            val attachmentPendingUpload = pendingUploadFile("/pending/account-1/attachment.bin")
            val sendPendingUpload = pendingUploadFile("/pending/account-1/send.bin")
            val database = createCollectorSweepDatabase(
                attachmentPendingUpload = attachmentPendingUpload,
                sendPendingUpload = sendPendingUpload,
            )
            val encryptedService = SweepRecordingEncryptedFilePendingUploadService()
            val now = Instant.parse("2026-07-27T07:00:00Z")
            val collector = PendingUploadGarbageCollectorImpl(
                db = UploadTestVaultDatabaseManager(database),
                pendingUploadCoordinator = PendingUploadCoordinatorImpl(encryptedService),
                logRepository = UploadTestLogRepository,
                now = { now },
                gracePeriod = 24.hours,
            )

            collector(ACCOUNT_ID).bind()

            assertEquals(
                listOf(
                    SweepCall(
                        accountId = ACCOUNT_ID,
                        namespace = PendingUploadTarget.CipherAttachment.NAMESPACE,
                        referencedPaths = setOf(attachmentPendingUpload.path),
                        olderThan = Instant.parse("2026-07-26T07:00:00Z"),
                    ),
                    SweepCall(
                        accountId = ACCOUNT_ID,
                        namespace = PendingUploadTarget.SendFile.NAMESPACE,
                        referencedPaths = setOf(sendPendingUpload.path),
                        olderThan = Instant.parse("2026-07-26T07:00:00Z"),
                    ),
                ),
                encryptedService.sweepCalls,
            )
        }

    @Test
    fun `purge sweeps both namespaces immediately without references`() = runTest {
        val database = createUploadTestDatabase()
        val encryptedService = SweepRecordingEncryptedFilePendingUploadService()
        val collector = PendingUploadGarbageCollectorImpl(
            db = UploadTestVaultDatabaseManager(database),
            pendingUploadCoordinator = PendingUploadCoordinatorImpl(encryptedService),
            logRepository = UploadTestLogRepository,
        )

        collector.purge(ACCOUNT_ID).bind()

        assertEquals(
            listOf(
                SweepCall(
                    accountId = ACCOUNT_ID,
                    namespace = PendingUploadTarget.CipherAttachment.NAMESPACE,
                    referencedPaths = emptySet(),
                    olderThan = Instant.DISTANT_FUTURE,
                ),
                SweepCall(
                    accountId = ACCOUNT_ID,
                    namespace = PendingUploadTarget.SendFile.NAMESPACE,
                    referencedPaths = emptySet(),
                    olderThan = Instant.DISTANT_FUTURE,
                ),
            ),
            encryptedService.sweepCalls,
        )
    }

    @Test
    fun `purge continues with the next namespace when one sweep fails`() = runTest {
        val database = createUploadTestDatabase()
        val encryptedService = SweepRecordingEncryptedFilePendingUploadService(
            sweepFailures = mapOf(
                PendingUploadTarget.CipherAttachment.NAMESPACE to IllegalStateException(
                    "sweep failed",
                ),
            ),
        )
        val collector = PendingUploadGarbageCollectorImpl(
            db = UploadTestVaultDatabaseManager(database),
            pendingUploadCoordinator = PendingUploadCoordinatorImpl(encryptedService),
            logRepository = UploadTestLogRepository,
        )

        collector.purge(ACCOUNT_ID).bind()

        assertEquals(
            listOf(
                PendingUploadTarget.CipherAttachment.NAMESPACE,
                PendingUploadTarget.SendFile.NAMESPACE,
            ),
            encryptedService.sweepCalls.map { call -> call.namespace },
        )
    }

    @Test
    fun `purge propagates cancellation and stops sweeping`() = runTest {
        val database = createUploadTestDatabase()
        val encryptedService = SweepRecordingEncryptedFilePendingUploadService(
            sweepFailures = mapOf(
                PendingUploadTarget.CipherAttachment.NAMESPACE to CancellationException(
                    "cancelled",
                ),
            ),
        )
        val collector = PendingUploadGarbageCollectorImpl(
            db = UploadTestVaultDatabaseManager(database),
            pendingUploadCoordinator = PendingUploadCoordinatorImpl(encryptedService),
            logRepository = UploadTestLogRepository,
        )

        assertFailsWith<CancellationException> {
            collector.purge(ACCOUNT_ID).bind()
        }

        assertEquals(
            listOf(PendingUploadTarget.CipherAttachment.NAMESPACE),
            encryptedService.sweepCalls.map { call -> call.namespace },
        )
    }
}

private fun createCollectorSweepDatabase(
    attachmentPendingUpload: PendingUploadFile,
    sendPendingUpload: PendingUploadFile,
) = createUploadTestDatabase().also { database ->
    insertLocalCipher(
        database,
        testBitwardenCipher(
            cipherId = "cipher-1",
            accountId = ACCOUNT_ID,
        ).copy(
            attachments = listOf(
                BitwardenCipher.Attachment.Local(
                    id = "attachment-1",
                    url = "file:///attachment",
                    fileName = "attachment.bin",
                    pendingUpload = attachmentPendingUpload,
                ),
            ),
        ),
    )
    insertLocalCipher(
        database,
        testBitwardenCipher(
            cipherId = "cipher-2",
            accountId = "account-2",
        ).copy(
            attachments = listOf(
                BitwardenCipher.Attachment.Local(
                    id = "attachment-2",
                    url = "file:///attachment-2",
                    fileName = "attachment-2.bin",
                    pendingUpload = pendingUploadFile(
                        "/pending/account-2/attachment.bin",
                    ),
                ),
            ),
        ),
    )
    val send = testSend(
        localId = "send-1",
        remoteId = null,
        localRevisionDate = Instant.parse("2026-07-25T07:00:00Z"),
        remoteRevisionDate = null,
        file = testSendFile(
            id = "send-file-1",
            pendingUpload = sendPendingUpload,
        ),
    )
    database.sendQueries.insert(
        accountId = send.accountId,
        sendId = send.sendId,
        data = send,
    )
}
