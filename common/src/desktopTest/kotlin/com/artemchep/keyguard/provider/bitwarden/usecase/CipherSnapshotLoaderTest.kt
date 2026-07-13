package com.artemchep.keyguard.provider.bitwarden.usecase

import app.cash.sqldelight.ColumnAdapter
import com.artemchep.keyguard.common.service.database.ObjectToStringAdapter
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestServer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.testCipher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CipherSnapshotLoaderTest {
    @Test
    fun `loads only changed payloads and evicts deleted snapshots`() = runTest {
        val countingAdapter = CountingCipherDataAdapter()
        val db = createUploadTestDatabase(cipherDataAdapter = countingAdapter)
        val storedCiphers = (1..3).map(::loginCipher)
        storedCiphers.forEach { cipher -> db.insert(cipher) }
        val loader = CipherSnapshotLoader(
            dbDispatcher = StandardTestDispatcher(testScheduler),
            getPasswordStrength = UploadTestPasswordStrength,
        )

        val initial = loader.load(
            db = db,
            previousSnapshotsByCipherId = emptyMap(),
        )

        assertEquals(3, countingAdapter.decodeCount)
        assertEquals(3, initial.stats.loadedPayloadCount)
        assertTrue(initial.stats.isFullLoad)

        val changed = storedCiphers.first().copy(
            name = "Updated cipher",
            revisionDate = T1,
        )
        db.insert(changed, updatedAt = T1)
        val updated = loader.load(
            db = db,
            previousSnapshotsByCipherId = initial.snapshotsByCipherId,
        )

        assertEquals(4, countingAdapter.decodeCount)
        assertEquals(1, updated.stats.changedCipherCount)
        assertEquals(1, updated.stats.loadedPayloadCount)
        assertFalse(updated.stats.isFullLoad)
        assertSame(
            initial.snapshotsByCipherId.getValue("cipher-2"),
            updated.snapshotsByCipherId.getValue("cipher-2"),
        )

        db.cipherQueries.deleteByCipherId("cipher-2")
        val afterDelete = loader.load(
            db = db,
            previousSnapshotsByCipherId = updated.snapshotsByCipherId,
        )

        assertEquals(4, countingAdapter.decodeCount)
        assertEquals(0, afterDelete.stats.changedCipherCount)
        assertEquals(0, afterDelete.stats.loadedPayloadCount)
        assertEquals(setOf("cipher-1", "cipher-3"), afterDelete.snapshotsByCipherId.keys)
    }

    @Test
    fun `uses a full payload scan for a large sync`() = runTest {
        val countingAdapter = CountingCipherDataAdapter()
        val db = createUploadTestDatabase(cipherDataAdapter = countingAdapter)
        val storedCiphers = (1..80).map(::loginCipher)
        storedCiphers.forEach { cipher -> db.insert(cipher) }
        val loader = CipherSnapshotLoader(
            dbDispatcher = StandardTestDispatcher(testScheduler),
            getPasswordStrength = UploadTestPasswordStrength,
        )
        val initial = loader.load(
            db = db,
            previousSnapshotsByCipherId = emptyMap(),
        )

        storedCiphers.take(64).forEach { cipher ->
            db.insert(
                cipher = cipher.copy(revisionDate = T1),
                updatedAt = T1,
            )
        }
        val updated = loader.load(
            db = db,
            previousSnapshotsByCipherId = initial.snapshotsByCipherId,
        )

        assertEquals(160, countingAdapter.decodeCount)
        assertEquals(64, updated.stats.changedCipherCount)
        assertEquals(80, updated.stats.loadedPayloadCount)
        assertTrue(updated.stats.isFullLoad)
    }

    private fun loginCipher(index: Int) = testCipher(
        localId = "cipher-$index",
        remoteId = "remote-cipher-$index",
        localRevisionDate = T0,
        remoteRevisionDate = T0,
        attachments = emptyList(),
    ).copy(
        type = BitwardenCipher.Type.Login,
        secureNote = null,
        login = BitwardenCipher.Login(
            password = "password-$index",
            uris = emptyList(),
        ),
    )

    private fun com.artemchep.keyguard.data.Database.insert(
        cipher: BitwardenCipher,
        updatedAt: Instant = T0,
    ) {
        cipherQueries.insert(
            cipherId = cipher.cipherId,
            accountId = cipher.accountId,
            folderId = cipher.folderId,
            data = cipher,
            updatedAt = updatedAt,
        )
    }

    private class CountingCipherDataAdapter : ColumnAdapter<BitwardenCipher, String> {
        private val delegate = ObjectToStringAdapter<BitwardenCipher>(UploadTestServer.json)

        var decodeCount: Int = 0
            private set

        override fun decode(databaseValue: String): BitwardenCipher {
            decodeCount += 1
            return delegate.decode(databaseValue)
        }

        override fun encode(value: BitwardenCipher): String = delegate.encode(value)
    }

    private companion object {
        val T0 = Instant.fromEpochMilliseconds(1_000L)
        val T1 = Instant.fromEpochMilliseconds(2_000L)
    }
}
