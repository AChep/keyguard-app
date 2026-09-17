package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.data.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.module
import org.koin.dsl.onClose

class VaultSessionScopeTest {
    @Test
    fun `vault dependencies are isolated and retirement is idempotent`() {
        var closed = 0
        val application = Koin().apply {
            loadModules(listOf(
                module {
                    single { SharedService() }
                    scope<VaultSessionScope> {
                        scoped {
                            VaultService(
                                masterKey = checkNotNull(getSource<VaultScopeSource>()).masterKey,
                                shared = get(),
                            )
                        } onClose { closed++ }
                    }
                },
            ), allowOverride = false)
        }
        try {
            val factory = KoinVaultSessionFactory(application)
            val firstKey = masterKey(1)
            val secondKey = masterKey(2)
            val first = factory.create(firstKey)
            val second = factory.create(secondKey)
            val firstService = checkNotNull(first.resolve { get<VaultService>() })
            val secondService = checkNotNull(second.resolve { get<VaultService>() })

            assertNotEquals(first.id, second.id)
            assertSame(firstKey, firstService.masterKey)
            assertSame(secondKey, secondService.masterKey)
            assertSame(firstService, first.resolve { get<VaultService>() })
            assertNotSame(firstService, secondService)
            assertSame(firstService.shared, secondService.shared)
            assertNull(application.getOrNull<VaultService>())

            first.close()
            first.close()

            assertEquals(1, closed)
            assertFalse(first.active.value)
            assertNull(first.resolve { get<VaultService>() })
            assertFailsWith<IllegalStateException> { first.scope }
            assertTrue(second.active.value)
            assertSame(secondService, second.resolve { get<VaultService>() })
            assertSame(firstService.shared, application.get<SharedService>())
            second.close()
            assertEquals(2, closed)
        } finally {
            application.close()
        }
    }

    @Test
    fun `replacing or locking a session retires the previous scope before publication`() = runTest {
        val application = Koin().apply {
            loadModules(listOf(module { scope<VaultSessionScope> {} }))
        }
        try {
            val factory = KoinVaultSessionFactory(application)
            val key = masterKey(1)
            val first = MasterSession.Key(
                key,
                factory.create(key),
                MasterSession.Key.Authenticated,
                Instant.DISTANT_PAST,
            )
            val second = first.copy(session = factory.create(key))
            val repository = SessionRepositoryImpl()

            repository.put(first)
            repository.put(first.copy(origin = MasterSession.Key.Persisted))
            assertTrue(first.session.active.value)

            repository.put(second)
            assertSame(second, repository.get().first())
            assertFalse(first.session.active.value)
            assertTrue(second.session.active.value)

            val locked = MasterSession.Empty()
            repository.put(locked)
            assertSame(locked, repository.get().first())
            assertFalse(second.session.active.value)
        } finally {
            application.close()
        }
    }

    @Test
    fun `a new vault generation changes UI state even when the master key is unchanged`() {
        val application = Koin().apply {
            loadModules(listOf(module { scope<VaultSessionScope> {} }))
        }
        try {
            val key = masterKey(1)
            val factory = KoinVaultSessionFactory(application)
            val changePassword = VaultState.Main.ChangePassword(
                key = key,
                withMasterPassword = VaultState.Main.ChangePassword.WithPassword { _, _ ->
                    error("State comparison must not change the password")
                },
                withMasterPasswordAndBiometric = null,
            )
            val first = VaultState.Main(key, changePassword, factory.create(key))
            val same = VaultState.Main(key, changePassword, first.session)
            val replacement = VaultState.Main(key, changePassword, factory.create(key))

            assertEquals(first, same)
            assertEquals(first.hashCode(), same.hashCode())
            assertNotEquals(first, replacement)
            assertEquals(2, setOf(first, same, replacement).size)
        } finally {
            application.close()
        }
    }

    @Test
    fun `closing the application invalidates its session handles`() {
        val application = Koin().apply {
            loadModules(listOf(module { scope<VaultSessionScope> {} }))
        }
        val session = KoinVaultSessionFactory(application).create(masterKey(1))
        assertTrue(session.active.value)

        application.close()

        assertFalse(session.active.value)
        assertNull(session.resolve { this })
        session.close()
    }

    @Test
    fun `restoring a persisted session creates its scope without opening the database`() {
        var databaseCreated = false
        val application = Koin().apply {
            loadModules(listOf(
                module {
                    scope<VaultSessionScope> {
                        scoped<VaultDatabaseManager> {
                            databaseCreated = true
                            error("Persisted restoration must not open the vault")
                        }
                    }
                },
            ))
        }
        try {
            val session = KoinVaultSessionFactory(application).create(masterKey(1))
            assertTrue(session.active.value)
            assertFalse(databaseCreated)
            session.close()
        } finally {
            application.close()
        }
    }

    @Test
    fun `failed authenticated unlock closes its unpublished scope`() = runTest {
        var closed = 0
        val failure = IllegalStateException("Cannot open vault")
        val application = Koin().apply {
            loadModules(listOf(
                module {
                    scope<VaultSessionScope> {
                        scoped<VaultDatabaseManager> {
                            object : VaultDatabaseManager {
                                override fun get(): IO<Database> = { throw failure }
                                override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> =
                                    error("Mutation must not run during unlock")

                                override fun changePassword(newMasterKey: MasterKey): IO<Unit> =
                                    error("Password change must not run during unlock")
                            }
                        } onClose { closed++ }
                    }
                },
            ))
        }
        try {
            val error = assertFailsWith<IllegalStateException> {
                KoinVaultSessionFactory(application).createAuthenticated(masterKey(1))
            }
            assertSame(failure, error)
            assertEquals(1, closed)
        } finally {
            application.close()
        }
    }

    private fun masterKey(value: Byte) = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(value))

    private class SharedService
    private class VaultService(val masterKey: MasterKey, val shared: SharedService)
}
