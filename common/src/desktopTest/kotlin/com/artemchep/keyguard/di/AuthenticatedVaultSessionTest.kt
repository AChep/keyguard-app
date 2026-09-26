package com.artemchep.keyguard.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.Koin
import org.koin.dsl.module
import org.koin.dsl.onClose

class AuthenticatedVaultSessionTest {
    @Test
    fun `authenticated scope is returned only after its database opens`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val database = createUploadTestDatabase(driver)
            val opening = CompletableDeferred<Unit>()
            val opened = CompletableDeferred<Unit>()
            val manager = TestVaultDatabaseManager {
                opening.complete(Unit)
                opened.await()
                database
            }
            val application = Koin().apply {
                loadModules(listOf(module { scope<VaultSessionScope> { scoped<VaultDatabaseManager> { manager } } }))
            }
            try {
                val unlock = async {
                    KoinVaultSessionFactory(application).createAuthenticated(masterKey)
                }
                opening.await()
                assertFalse(unlock.isCompleted)

                opened.complete(Unit)
                val session = unlock.await()
                assertTrue(session.active.value)
                assertSame(manager, session.resolve { get<VaultDatabaseManager>() })
                session.close()
                assertFalse(session.active.value)
            } finally {
                application.close()
            }
        }
    }

    @Test
    fun `cancellation retires an unpublished scope even when database opening ignores cancellation`() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val database = createUploadTestDatabase(driver)
            val opening = CompletableDeferred<Unit>()
            val opened = CompletableDeferred<Unit>()
            var closed = 0
            val manager = TestVaultDatabaseManager {
                opening.complete(Unit)
                // Exercise native/database initialization which may finish after cancellation.
                withContext(NonCancellable) { opened.await() }
                database
            }
            val application = Koin().apply {
                loadModules(listOf(
                    module {
                        scope<VaultSessionScope> {
                            scoped<VaultDatabaseManager> { manager } onClose { closed++ }
                        }
                    },
                ))
            }
            try {
                val unlock = async {
                    KoinVaultSessionFactory(application).createAuthenticated(masterKey)
                }
                opening.await()
                unlock.cancel()
                opened.complete(Unit)
                unlock.join()

                assertTrue(unlock.isCancelled)
                assertEquals(1, closed)
            } finally {
                application.close()
            }
        }
    }

    private class TestVaultDatabaseManager(private val open: IO<Database>) : VaultDatabaseManager {
        override fun get(): IO<Database> = open
        override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> =
            error("Unlock must not mutate the database")

        override fun changePassword(newMasterKey: MasterKey): IO<Unit> =
            error("Unlock must not change the database password")
    }

    private val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
}
