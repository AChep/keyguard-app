package com.artemchep.keyguard.common.service.exposedaccount.impl

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.Argon2Mode
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccount
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountEntry
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountRegistration
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("FunctionNaming")
class ExposedAccountRepositoryImplTest {
    @Test
    fun `replaceAll publishes one complete registration snapshot`() = runTest {
        val repository = createRepository()
        val registrationsDeferred = async {
            repository.getRegistrations()
                .take(2)
                .toList()
        }
        runCurrent()

        repository.replaceAll(
            accounts = listOf(
                account(id = "b", name = "Bravo"),
                account(id = "a", name = "Alpha"),
            ),
            allAccountIds = linkedSetOf("a", "b"),
        ).invoke()

        assertEquals(
            listOf(
                emptyList(),
                listOf(
                    registration(id = "a", entryId = "entry-1", label = "Alpha"),
                    registration(id = "b", entryId = "entry-2", label = "Bravo"),
                ),
            ),
            registrationsDeferred.await(),
        )
    }

    @Test
    fun `replaceAll preserves an entry id when the account label changes`() = runTest {
        val repository = createRepository()
        repository.replaceAll(
            accounts = listOf(account(id = "a", name = "Before")),
            allAccountIds = setOf("a"),
        ).invoke()

        val before = repository.getRegistrations().first().single()
        repository.replaceAll(
            accounts = listOf(account(id = "a", name = "After")),
            allAccountIds = setOf("a"),
        ).invoke()

        assertEquals(
            before.copy(label = "After"),
            repository.getRegistrations().first().single(),
        )
    }

    @Test
    fun `hidden account is not registrable but its stale entry remains known`() = runTest {
        val repository = createRepository()
        repository.replaceAll(
            accounts = listOf(account(id = "a", name = "Alpha")),
            allAccountIds = setOf("a"),
        ).invoke()
        val entryId = repository.getRegistrations().first().single().entryId

        repository.replaceAll(
            accounts = emptyList(),
            allAccountIds = setOf("a"),
        ).invoke()

        assertEquals(emptyList(), repository.getRegistrations().first())
        assertEquals(
            ExposedAccountEntry(
                accountId = "a",
                account = null,
            ),
            repository.resolveEntry(entryId).invoke(),
        )
    }

    private fun createRepository() = ExposedAccountRepositoryImpl(
        exposedDatabaseManager = TestExposedDatabaseManager(),
        cryptoGenerator = SequentialUuidCryptoGenerator(),
        dispatcher = Dispatchers.Unconfined,
    )

    private fun account(
        id: String,
        name: String,
    ) = ExposedAccount(
        accountId = id,
        name = name,
        email = "$id@example.com",
        host = "$id.example.com",
    )

    private fun registration(
        id: String,
        entryId: String,
        label: String,
    ) = ExposedAccountRegistration(
        accountId = id,
        entryId = entryId,
        label = label,
    )

    private class TestExposedDatabaseManager : ExposedDatabaseManager {
        private val database = createExposedTestDatabase()

        override fun get(): IO<DatabaseExposed> = {
            database
        }

        override fun <T> mutate(
            tag: String,
            block: suspend (DatabaseExposed) -> T,
        ): IO<T> = {
            block(database)
        }

        override fun changePassword(
            newMasterKey: MasterKey,
        ): IO<Unit> = {}
    }

    @Suppress("TooManyFunctions")
    private class SequentialUuidCryptoGenerator : CryptoGenerator {
        private var nextUuid = 1

        override fun uuid(): String = "entry-${nextUuid++}"

        override fun hkdf(
            seed: ByteArray,
            salt: ByteArray?,
            info: ByteArray?,
            length: Int,
        ): ByteArray = unused()

        override fun pbkdf2(
            seed: ByteArray,
            salt: ByteArray,
            iterations: Int,
            length: Int,
        ): ByteArray = unused()

        override fun argon2(
            mode: Argon2Mode,
            seed: ByteArray,
            salt: ByteArray,
            iterations: Int,
            memoryKb: Int,
            parallelism: Int,
        ): ByteArray = unused()

        override fun seed(length: Int): ByteArray = unused()

        override fun hmac(
            key: ByteArray,
            data: ByteArray,
            algorithm: CryptoHashAlgorithm,
        ): ByteArray = unused()

        override fun hashSha1(data: ByteArray): ByteArray = unused()

        override fun hashSha256(data: ByteArray): ByteArray = unused()

        override fun hashMd5(data: ByteArray): ByteArray = unused()

        override fun random(): Int = unused()

        override fun random(range: IntRange): Int = unused()

        private fun <T> unused(): T = error("Not used in this test.")
    }

    private companion object {
        fun createExposedTestDatabase(): DatabaseExposed {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            DatabaseExposed.Schema.create(driver)
            return DatabaseExposed(
                driver = driver,
                urlBlockAdapter = UrlBlock.Adapter(InstantToLongAdapter),
            )
        }
    }
}
