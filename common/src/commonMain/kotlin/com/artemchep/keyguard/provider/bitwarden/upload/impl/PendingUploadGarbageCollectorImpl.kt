package com.artemchep.keyguard.provider.bitwarden.upload.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.core.store.bitwarden.pendingAttachmentUploads
import com.artemchep.keyguard.core.store.bitwarden.pendingFileUploads
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadGarbageCollector
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@Suppress("TooGenericExceptionCaught")
class PendingUploadGarbageCollectorImpl(
    private val db: VaultDatabaseManager,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val logRepository: LogRepository,
    private val now: () -> Instant = Clock.System::now,
    private val gracePeriod: Duration = DEFAULT_GRACE_PERIOD,
) : PendingUploadGarbageCollector {
    init {
        require(!gracePeriod.isNegative()) {
            "Pending-upload cleanup grace period must not be negative."
        }
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        db = directDI.instance(),
        pendingUploadCoordinator = directDI.instance(),
        logRepository = directDI.instance(),
    )

    override fun invoke(
        accountId: String,
    ): IO<Unit> = {
        try {
            // Only the reference snapshot needs the database. The sweep runs
            // outside of it: the grace period, not a database lock, is what
            // protects a file between staging it and committing its metadata.
            val referencedPathsByNamespace = db
                .get()
                .effectMap(Dispatchers.IO) { database ->
                    mapOf(
                        PendingUploadTarget.CipherAttachment.NAMESPACE to database
                            .cipherQueries
                            .getByAccountId(accountId = accountId)
                            .executeAsList()
                            .referencedPaths { row -> row.data_.pendingAttachmentUploads() },
                        PendingUploadTarget.SendFile.NAMESPACE to database
                            .sendQueries
                            .getByAccountId(accountId = accountId)
                            .executeAsList()
                            .referencedPaths { row -> row.data_.pendingFileUploads() },
                    )
                }
                .bind()

            sweepEveryNamespace(
                accountId = accountId,
                olderThan = now() - gracePeriod,
                referencedPathsByNamespace = referencedPathsByNamespace,
            )
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            logRepository.add(
                tag = TAG,
                message = "Failed to sweep stale pending uploads for account '$accountId': $e",
                level = LogLevel.WARNING,
            )
        }
    }

    override fun purge(
        accountId: String,
    ): IO<Unit> = {
        // No references and no grace period: everything staged for this
        // account is garbage once the account itself is gone.
        sweepEveryNamespace(
            accountId = accountId,
            olderThan = Instant.DISTANT_FUTURE,
        )
    }

    private suspend fun sweepEveryNamespace(
        accountId: String,
        olderThan: Instant,
        referencedPathsByNamespace: Map<String, Set<String>> = emptyMap(),
    ) = PendingUploadTarget.NAMESPACES.forEach { namespace ->
        sweepOrphansBestEffort(
            accountId = accountId,
            namespace = namespace,
            referencedPaths = referencedPathsByNamespace[namespace].orEmpty(),
            olderThan = olderThan,
        )
    }

    private suspend fun sweepOrphansBestEffort(
        accountId: String,
        namespace: String,
        referencedPaths: Set<String>,
        olderThan: Instant,
    ) {
        try {
            pendingUploadCoordinator.sweepOrphans(
                accountId = accountId,
                namespace = namespace,
                referencedPaths = referencedPaths,
                olderThan = olderThan,
            )
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            logRepository.add(
                tag = TAG,
                message = "Failed to sweep stale pending uploads in namespace '$namespace': $e",
                level = LogLevel.WARNING,
            )
        }
    }

    companion object {
        private const val TAG = "PendingUploadCleanup"
        private val DEFAULT_GRACE_PERIOD = 24.hours
    }
}

private fun <T> List<T>.referencedPaths(
    uploads: (T) -> Set<PendingUploadFile>,
): Set<String> = flatMap(uploads)
    .mapTo(mutableSetOf()) { pendingUpload -> pendingUpload.path }
