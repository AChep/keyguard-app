package com.artemchep.keyguard.integration.bitwarden

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.service.backup.BackupStatus
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.MarkBackupAsDirty
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import kotlinx.io.Source
import kotlin.time.Instant

// Small stand-ins for the services that the sync engine needs but does not test.

internal object NoopWatchdog : Watchdog {
    override fun <T> track(
        accountIdSet: Set<AccountId>,
        accountTask: AccountTask,
        io: IO<T>,
    ): IO<T> = io
}

internal object ConstantPasswordStrength : GetPasswordStrength {
    override fun invoke(password: String): IO<PasswordStrength> = ioEffect {
        PasswordStrength(
            crackTimeSeconds = 1_000_000L,
            version = 1L,
        )
    }
}

internal object NoopMarkBackupAsDirty : MarkBackupAsDirty {
    override fun invoke(): IO<BackupStatus> = ioEffect {
        BackupStatus(changeGeneration = 1L)
    }
}

internal object NoopLogRepository : LogRepository {
    override fun post(tag: String, message: String, level: LogLevel) = Unit

    override suspend fun add(tag: String, message: String, level: LogLevel) = Unit
}

/** Fails on any call; the suites that test attachments or Send files bring a real one. */
internal object FailingPendingUploadCoordinator : PendingUploadCoordinator {
    override suspend fun stage(
        target: PendingUploadTarget,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile = unexpectedCall("stage")

    override suspend fun stage(
        target: PendingUploadTarget,
        source: Source,
        fileKey: ByteArray,
    ): PendingUploadFile = unexpectedCall("stage")

    override suspend fun readPlaintext(
        pendingUpload: PendingUploadFile,
        fileKey: ByteArray,
    ): ByteArray = unexpectedCall("readPlaintext")

    override suspend fun delete(
        pendingUpload: PendingUploadFile,
    ): Unit = unexpectedCall("delete")

    override suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    ): Unit = unexpectedCall("markUploaded")

    override suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean = unexpectedCall("isUploaded")

    override suspend fun sweepOrphans(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ): Unit = unexpectedCall("sweepOrphans")

    override suspend fun <T> persist(
        createdPendingUploads: Collection<PendingUploadFile>,
        removedPendingUploads: Collection<PendingUploadFile>,
        block: suspend () -> T,
    ): T = unexpectedCall("persist")

    private fun unexpectedCall(
        operation: String,
    ): Nothing = error("Unexpected pending-upload coordinator call: $operation")
}
