package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.di.KoinVaultSessionFactory
import com.artemchep.keyguard.di.VaultScopeSource
import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.di.resolve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class GetVaultSessionImplTest {
    @Test
    fun `restoration publishes a persisted session without opening the database, reused on resubscription`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val databaseCreated = atomic(0)
        val databaseOpened = atomic(0)
        val koin = Koin().apply {
            loadModules(
                listOf(
                    module {
                        scope<VaultSessionScope> {
                            scoped<VaultDatabaseManager> {
                                databaseCreated.incrementAndGet()
                                object : VaultDatabaseManager {
                                    override fun get(): IO<Database> = {
                                        databaseOpened.incrementAndGet()
                                        error("Persisted restoration must not open the database")
                                    }

                                    override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> =
                                        error("Restoration must not mutate the database")

                                    override fun changePassword(newMasterKey: MasterKey): IO<Unit> =
                                        error("Restoration must not change the database password")
                                }
                            }
                        }
                    },
                ),
            )
        }
        try {
            val factory = RecordingFactory(KoinVaultSessionFactory(koin))
            val repository = SessionRepositoryImpl()
            val getSession = GetVaultSessionImpl(
                sessionFactory = factory,
                sessionReadWriteRepository = repository,
                keyReadWriteRepository = PersistedKeys(flowOf(persistedSession)),
            )

            val restored = assertIs<MasterSession.Key>(getSession().first())

            assertSame(masterKey, restored.masterKey)
            assertSame(MasterSession.Key.Persisted, restored.origin)
            assertSame(restored, repository.get().first())
            assertSame(restored, getSession.valueOrNull)
            assertSame(masterKey, restored.session.resolve { getSource<VaultScopeSource>()?.masterKey })
            assertTrue(restored.session.active.value)
            assertEquals(1, factory.createdCount.value)
            assertEquals(0, databaseCreated.value)
            assertEquals(0, databaseOpened.value)

            repeat(3) {
                assertSame(restored, getSession().first())
            }
            // A fresh subscriber also receives subsequent repository transitions.
            val locking = async { getSession().first { it is MasterSession.Empty } }
            val locked = MasterSession.Empty()
            repository.put(locked)

            assertSame(locked, locking.await())
            assertFalse(restored.session.active.value)
            assertEquals(1, factory.createdCount.value)
            assertEquals(0, databaseCreated.value)
            assertEquals(0, databaseOpened.value)
        } finally {
            koin.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelling restoration after creation closes its unpublished candidate`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val koin = Koin().apply {
            loadModules(listOf(module { scope<VaultSessionScope> {} }))
        }
        val candidateCreated = CompletableDeferred<VaultSession>()
        var restorationJob: Job? = null
        val repository = SessionRepositoryImpl()
        val factory = RecordingFactory(KoinVaultSessionFactory(koin)) { session ->
            // This is the flow's collection job, captured immediately before emit. Cancelling
            // here reproduces a stopped subscriber after creation and before publication.
            checkNotNull(restorationJob).cancel()
            candidateCreated.complete(session)
        }
        val persistedKeys = PersistedKeys(
            flow {
                restorationJob = currentCoroutineContext()[Job]
                emit(persistedSession)
                awaitCancellation()
            },
        )
        val getSession = GetVaultSessionImpl(factory, repository, persistedKeys)
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            getSession().collect()
        }
        try {
            val candidate = candidateCreated.await()
            candidate.active.first { active -> !active }

            assertEquals(1, factory.createdCount.value)
            assertNull(repository.get().first())
            assertNull(getSession.valueOrNull)
            assertNull(candidate.resolve { this })
        } finally {
            subscription.cancelAndJoin()
            koin.close()
            Dispatchers.resetMain()
        }
    }

    private class RecordingFactory(
        private val delegate: VaultSessionFactory,
        private val onCreated: (VaultSession) -> Unit = {},
    ) : VaultSessionFactory {
        val createdCount = atomic(0)

        override fun create(masterKey: MasterKey): VaultSession = delegate.create(masterKey).also {
            createdCount.incrementAndGet()
            onCreated(it)
        }

        override suspend fun createAuthenticated(masterKey: MasterKey): VaultSession =
            error("Persisted restoration must not authenticate a session")
    }

    private class PersistedKeys(
        private val sessions: Flow<PersistedSession?>,
    ) : KeyReadWriteRepository {
        override fun get(): Flow<PersistedSession?> = sessions
        override fun put(session: PersistedSession?): IO<Unit> =
            error("Restoration must not write the persisted key")
    }

    private val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
    private val persistedSession = PersistedSession(
        masterKey = masterKey,
        createdAt = Instant.DISTANT_PAST,
        persistedAt = Instant.DISTANT_PAST,
    )
}
