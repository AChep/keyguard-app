package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.common.model.DBarcodeUsageHistory
import com.artemchep.keyguard.common.usecase.impl.GetBarcodeUsageHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.PutBarcodeUsageHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeUsageHistoryRepositoryImplTest {
    @Test
    fun `put upserts history item by id`() = runTest {
        val repository = createRepository()
        repository.put(
            DBarcodeUsageHistory(
                id = "history-key",
                type = BarcodeImageFormat.QR_CODE.name,
                createdAt = Instant.fromEpochMilliseconds(1),
            ),
        )()
        repository.put(
            DBarcodeUsageHistory(
                id = "history-key",
                type = BarcodeImageFormat.CODE_128.name,
                createdAt = Instant.fromEpochMilliseconds(2),
            ),
        )()

        val item = repository.getById("history-key").first()

        assertNotNull(item)
        assertEquals(BarcodeImageFormat.CODE_128.name, item.type)
        assertEquals(Instant.fromEpochMilliseconds(2), item.createdAt)
    }

    @Test
    fun `get by id returns null for missing history item`() = runTest {
        val repository = createRepository()

        val item = repository.getById("missing").first()

        assertNull(item)
    }

    @Test
    fun `use cases write and read history item`() = runTest {
        val repository = createRepository()
        val putBarcodeUsageHistory = PutBarcodeUsageHistoryImpl(repository)
        val getBarcodeUsageHistory = GetBarcodeUsageHistoryImpl(repository)

        putBarcodeUsageHistory("history-key", BarcodeImageFormat.PDF_417.name)()
        val item = getBarcodeUsageHistory("history-key").first()

        assertNotNull(item)
        assertEquals(BarcodeImageFormat.PDF_417.name, item.type)
    }

    private fun createRepository(): BarcodeUsageHistoryRepositoryImpl {
        val database = createUploadTestDatabase()
        return BarcodeUsageHistoryRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(database),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }
}
