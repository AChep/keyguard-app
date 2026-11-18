package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.crypto.NativeKeyPairGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorHistoryRepositoryImplTest {
    @Test
    fun `cleanup deletes expired rows beyond the visible limit and keeps the cutoff`() = runTest {
        val database = createUploadTestDatabase()
        val dao = database.generatorHistoryQueries
        val base64 = Base64ServiceImpl()
        val repository = GeneratorHistoryRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(database),
            base64Service = base64,
            keyPairGenerator = NativeKeyPairGenerator(base64),
            json = Json,
            dispatcher = UnconfinedTestDispatcher(),
        )

        dao.transaction {
            repeat(1002) { index ->
                dao.insert(
                    value_ = "value-$index",
                    createdAt = Instant.fromEpochMilliseconds(index.toLong()),
                    isPassword = true,
                    isUsername = false,
                    isEmailRelay = null,
                    isSshKey = null,
                    isGpgKey = null,
                )
            }
        }
        repository.removeOlderThan(Instant.fromEpochMilliseconds(1000)).bind()

        val remaining = dao.get(Long.MAX_VALUE).executeAsList()
        assertEquals(listOf("value-1001", "value-1000"), remaining.map { it.value_ })
    }
}
