package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.database.DatabaseDispatcher
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance

class PendingUsageHistoryQueueImpl(
    private val databaseManager: ExposedDatabaseManager,
    private val settingsRepository: SettingsReadRepository,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
    private val logRepository: LogRepository,
) : PendingUsageHistoryQueue {
    companion object {
        private const val TAG = "PendingUsageHistoryQueue"
        private const val MAX_PENDING = 256L
    }

    /**
     * Maps a [PendingUsageHistory.coalescenceKey] to the id of the queue
     * row that currently represents it. Only ever touched inside
     * [ExposedDatabaseManager.mutate], which serializes access.
     */
    private val coalescenceIds = mutableMapOf<String, String>()

    constructor(directDI: DirectDI) : this(
        databaseManager = directDI.instance(),
        settingsRepository = directDI.instance(),
        json = directDI.instance(),
        dispatcher = directDI.instance(tag = DatabaseDispatcher),
        logRepository = directDI.instance(),
    )

    override fun get(): IO<List<SealedPendingUsageHistory>> = ioEffect(dispatcher) {
        databaseManager.get()
            .bind()
            .pendingUsageHistoryQueries
            .get()
            .executeAsList()
            .map { row ->
                SealedPendingUsageHistory(
                    id = row.id,
                    timestampEpochMilliseconds = row.timestampEpochMilliseconds,
                    payload = row.payload,
                )
            }
    }

    override fun enqueue(
        item: PendingUsageHistory,
    ): IO<Unit> = ioEffect(dispatcher) {
        val publicKeySpki = settingsRepository.getExposedContentPublicKey()
            .first()
        if (publicKeySpki == null) {
            // Writing the event in plaintext would leak it at the exposed
            // protection tier, so until a key is provisioned we drop it.
            logRepository.post(
                tag = TAG,
                message = "No envelope public key is provisioned, " +
                        "dropping a pending usage history event.",
                level = LogLevel.WARNING,
            )
            return@ioEffect
        }
        val payload = kotlin.run {
            val model = PendingUsageHistoryPayload(
                protocol = item.protocol.name,
                sessionId = item.sessionId,
                caller = item.caller,
                requestType = item.requestType,
                responseType = item.responseType,
                cipherId = item.cipherId,
                fingerprint = item.fingerprint,
                keygrip = item.keygrip,
            )
            val plaintext = json.encodeToString(model).encodeToByteArray()
            try {
                PendingUsageHistoryEnvelope.seal(
                    publicKeySpki = publicKeySpki,
                    plaintext = plaintext,
                )
            } finally {
                plaintext.fill(0)
            }
        }
        databaseManager.mutate("PendingUsageHistoryQueue.enqueue") { db ->
            db.pendingUsageHistoryQueries.transaction {
                val id = resolveRowId(db, item)
                // Overwriting a coalesced row does not grow the queue,
                // so only fresh rows may push it over the cap.
                if (id == item.id) {
                    val overflow = db.pendingUsageHistoryQueries.count()
                        .executeAsOne() - MAX_PENDING + 1L
                    if (overflow > 0L) {
                        db.pendingUsageHistoryQueries.deleteOldest(overflow)
                        logRepository.post(
                            tag = TAG,
                            message = "Dropped $overflow pending usage history entries.",
                            level = LogLevel.WARNING,
                        )
                    }
                }
                db.pendingUsageHistoryQueries.insert(
                    id = id,
                    timestampEpochMilliseconds = item.timestampEpochMilliseconds,
                    payload = payload,
                )
            }
        }.bind()
    }

    /**
     * Returns the id to write the event under: the remembered row id
     * when the event coalesces onto a row that is still queued, the
     * event's own id otherwise. Reusing an id of a row that was already
     * flushed would collide with its [eventId] in the vault tables and
     * lose the event, hence the existence check.
     */
    private fun resolveRowId(
        db: DatabaseExposed,
        item: PendingUsageHistory,
    ): String {
        val key = item.coalescenceKey
            ?: return item.id
        val known = coalescenceIds[key]
            ?.takeIf { id ->
                db.pendingUsageHistoryQueries.countById(id)
                    .executeAsOne() > 0L
            }
        if (known == null) {
            coalescenceIds[key] = item.id
        }
        return known ?: item.id
    }

    override fun remove(id: String): IO<Unit> =
        databaseManager.mutate("PendingUsageHistoryQueue.remove") { db ->
            db.pendingUsageHistoryQueries.deleteById(id)
        }
}
