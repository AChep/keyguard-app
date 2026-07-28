package com.artemchep.keyguard.common.service.gpgagent.impl

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.InstantToLongAdapter
import com.artemchep.keyguard.common.service.database.exposed.ExposedDatabaseManager
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRow
import com.artemchep.keyguard.dataexposed.DatabaseExposed
import com.artemchep.keyguard.dataexposed.UrlBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GpgAgentPublicKeyRepositoryImplTest {
    @Test
    fun `replaceAll restores public-only key rows from exposed database`() = runTest {
        val repository = createRepository()
        val publicOnly = GpgAgentPublicKeyRow(
            keygrip = "0123456789abcdef0123456789abcdef01234567",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            algorithm = "ED25519",
            canSign = false,
            canDecrypt = false,
            publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----",
            name = "Public only",
        )

        repository.replaceAll(listOf(publicOnly)).invoke()

        assertEquals(listOf(publicOnly.copy(keygrip = publicOnly.keygrip.uppercase())), repository.get().invoke())
        assertEquals(publicOnly.copy(keygrip = publicOnly.keygrip.uppercase()), repository.getByKeygrip(publicOnly.keygrip).invoke())

        repository.clearNames().invoke()

        val unnamed = repository.get().invoke().single()
        assertEquals(publicOnly.publicKeyArmored, unnamed.publicKeyArmored)
        assertEquals(false, unnamed.canSign)
        assertEquals(false, unnamed.canDecrypt)
        assertNull(unnamed.name)
    }

    private fun createRepository() = GpgAgentPublicKeyRepositoryImpl(
        exposedDatabaseManager = TestExposedDatabaseManager(),
        dispatcher = Dispatchers.Unconfined,
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
