package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.testCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class WatchtowerPendingCipherQueriesTest {
    @Test
    fun `matching report is not pending and the next cipher write increments its counter`() {
        val db = createUploadTestDatabase()
        val original = cipher(id = "cipher-1")
        db.insertCipher(original, updatedAt = T0)
        db.report(
            cipher = original,
            cipherDataRevCounter = 0L,
            threat = true,
            reportedAt = T1,
        )

        assertTrue(
            db.watchtowerThreatQueries
                .getPendingCipherKeys(TYPE, VERSION, 500L)
                .executeAsList()
                .isEmpty(),
        )

        db.insertCipher(original.copy(name = "Updated"), updatedAt = T0)

        val pending = db.watchtowerThreatQueries
            .getPendingCipherKeys(TYPE, VERSION, 500L)
            .executeAsOne()
        assertEquals(original.cipherId, pending.cipherId)
        assertEquals(1L, pending.dataRevCounter)
    }

    @Test
    fun `pending key query returns only cipher id and counter`() {
        val db = createUploadTestDatabase()
        val original = cipher(id = "cipher-1")
        db.insertCipher(original, updatedAt = T0)
        db.report(
            cipher = original,
            threat = true,
            reportedAt = T1,
        )

        val ignored = original.copy(
            ignoredAlerts = mapOf(
                BitwardenCipher.IgnoreAlertType.UNSECURE_WEBSITE to BitwardenCipher.IgnoreAlertData(
                    createdAt = T2,
                ),
            ),
        )
        db.insertCipher(ignored, updatedAt = T2)

        val pending = db.watchtowerThreatQueries
            .getPendingCipherKeys(
                type = TYPE,
                version = VERSION,
                limit = 500L,
            )
            .executeAsList()

        assertEquals(1, pending.size)
        assertEquals(ignored.cipherId, pending.single().cipherId)
        assertEquals(1L, pending.single().dataRevCounter)
    }

    @Test
    fun `all pending signal uses the shared current vault snapshot`() {
        val db = createUploadTestDatabase()
        val first = cipher(id = "cipher-1")
        val second = cipher(id = "cipher-2")
        listOf(first, second).forEach { cipher ->
            db.insertCipher(cipher, updatedAt = T0)
            db.report(
                cipher = cipher,
                threat = true,
                reportedAt = T1,
            )
        }

        assertFalse(
            db.watchtowerThreatQueries
                .hasPendingCiphers(TYPE, VERSION)
                .executeAsOne(),
        )

        val updatedFirst = first.copy(name = "Updated")
        db.insertCipher(updatedFirst, updatedAt = T2)

        assertTrue(
            db.watchtowerThreatQueries
                .hasPendingCiphers(TYPE, VERSION)
                .executeAsOne(),
        )
        val snapshot = db.cipherQueries
            .getCipherSnapshots()
            .executeAsList()

        assertEquals(
            mapOf(
                updatedFirst.cipherId to updatedFirst.name,
                second.cipherId to second.name,
            ),
            snapshot.associate { row -> row.cipherId to row.data_.name },
        )
        assertEquals(
            mapOf(updatedFirst.cipherId to 1L, second.cipherId to 0L),
            snapshot.associate { row -> row.cipherId to row.dataRevCounter },
        )
    }

    @Test
    fun `stale result cannot consume a newer cipher revision`() {
        val db = createUploadTestDatabase()
        val original = cipher(id = "cipher-1")
        db.insertCipher(original, updatedAt = T0)
        db.report(
            cipher = original,
            cipherDataRevCounter = 0L,
            threat = true,
            reportedAt = T1,
        )

        val ignored = original.copy(
            ignoredAlerts = mapOf(
                BitwardenCipher.IgnoreAlertType.UNSECURE_WEBSITE to BitwardenCipher.IgnoreAlertData(
                    createdAt = T2,
                ),
            ),
        )
        db.insertCipher(ignored, updatedAt = T2)

        db.report(
            cipher = original,
            cipherDataRevCounter = 0L,
            threat = false,
            reportedAt = T3,
        )

        assertEquals(1, db.watchtowerThreatQueries.getThreats().executeAsList().size)
        val pending = db.watchtowerThreatQueries
            .getPendingCipherKeys(TYPE, VERSION, 500L)
            .executeAsOne()
        assertEquals(ignored.cipherId, pending.cipherId)
        assertEquals(1L, pending.dataRevCounter)

        db.report(
            cipher = ignored,
            cipherDataRevCounter = 1L,
            threat = false,
            reportedAt = T3,
        )

        assertTrue(db.watchtowerThreatQueries.getThreats().executeAsList().isEmpty())
        assertTrue(
            db.watchtowerThreatQueries
                .getPendingCipherKeys(TYPE, VERSION, 500L)
                .executeAsList()
                .isEmpty(),
        )
    }

    @Test
    fun `counter increments when the incoming timestamp is unchanged or older`() {
        val db = createUploadTestDatabase()
        val original = cipher(id = "cipher-1")
        db.insertCipher(original, updatedAt = T2)

        db.insertCipher(original.copy(name = "Same timestamp"), updatedAt = T2)
        var stored = db.cipherQueries
            .getByCipherId(original.cipherId)
            .executeAsOne()
        assertEquals("Same timestamp", stored.data_.name)
        assertEquals(T2, stored.updatedAt)
        assertEquals(1L, stored.dataRevCounter)

        db.insertCipher(original.copy(name = "Older timestamp"), updatedAt = T0)
        stored = db.cipherQueries
            .getByCipherId(original.cipherId)
            .executeAsOne()
        assertEquals("Older timestamp", stored.data_.name)
        assertEquals(T0, stored.updatedAt)
        assertEquals(2L, stored.dataRevCounter)
    }

    @Test
    fun `cipher upsert does not overwrite or increment a different account`() {
        val db = createUploadTestDatabase()
        val original = cipher(id = "cipher-1")
        db.insertCipher(original, updatedAt = T0)

        db.cipherQueries.insert(
            cipherId = original.cipherId,
            accountId = "different-account",
            folderId = original.folderId,
            data = original.copy(name = "Wrong account"),
            updatedAt = T2,
        )

        val stored = db.cipherQueries
            .getByCipherId(original.cipherId)
            .executeAsOne()
        assertEquals(original.accountId, stored.accountId)
        assertEquals(original.name, stored.data_.name)
        assertEquals(T0, stored.updatedAt)
        assertEquals(0L, stored.dataRevCounter)
    }

    @Test
    fun `guarded upsert does not create a threat for a missing cipher`() {
        val db = createUploadTestDatabase()
        val missing = cipher(id = "missing")

        db.report(
            cipher = missing,
            cipherDataRevCounter = 0L,
            threat = true,
            reportedAt = T1,
        )

        assertTrue(db.watchtowerThreatQueries.getThreats().executeAsList().isEmpty())
    }

    private fun cipher(id: String) = testCipher(
        localId = id,
        remoteId = "remote-$id",
        localRevisionDate = T0,
        remoteRevisionDate = T0,
        attachments = emptyList(),
    )

    private fun Database.insertCipher(
        cipher: BitwardenCipher,
        updatedAt: Instant,
    ) {
        cipherQueries.insert(
            cipherId = cipher.cipherId,
            accountId = cipher.accountId,
            folderId = cipher.folderId,
            data = cipher,
            updatedAt = updatedAt,
        )
    }

    private fun Database.report(
        cipher: BitwardenCipher,
        cipherDataRevCounter: Long = currentDataRevCounter(cipher.cipherId),
        threat: Boolean,
        reportedAt: Instant,
        version: String = VERSION,
    ) {
        watchtowerThreatQueries.upsert(
            value = null,
            threat = threat,
            cipherId = cipher.cipherId,
            type = TYPE,
            reportedAt = reportedAt,
            version = version,
            cipherDataRevCounter = cipherDataRevCounter,
        )
    }

    private fun Database.currentDataRevCounter(cipherId: String): Long = cipherQueries
        .getByCipherId(cipherId)
        .executeAsOne()
        .dataRevCounter

    private companion object {
        const val TYPE = 7L
        const val VERSION = "1"
        val T0 = Instant.fromEpochMilliseconds(1_000L)
        val T1 = Instant.fromEpochMilliseconds(2_000L)
        val T2 = Instant.fromEpochMilliseconds(3_000L)
        val T3 = Instant.fromEpochMilliseconds(4_000L)
    }
}
