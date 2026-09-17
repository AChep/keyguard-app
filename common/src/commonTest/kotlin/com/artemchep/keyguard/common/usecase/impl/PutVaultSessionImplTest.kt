package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.flatTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class PutVaultSessionImplTest {
    @Test
    fun `cancellation before publication closes the candidate session`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler, "main"))
        val koin = sessionKoin()
        try {
            val repository = SessionRepositoryImpl()
            val candidate = KoinVaultSessionFactory(koin).create(masterKey)
            val publication = async(
                StandardTestDispatcher(testScheduler, "caller"),
                start = CoroutineStart.UNDISPATCHED,
            ) {
                PutVaultSessionImpl(repository)(candidate.asMasterSession()).bind()
            }
            assertNull(repository.get().first())

            publication.cancel()
            runCurrent()
            publication.join()

            assertFalse(candidate.active.value)
            assertNull(candidate.resolve { this })
            assertNull(repository.get().first())
        } finally {
            koin.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `replacing the caller's vault keeps the published session alive despite return cancellation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler, "main"))
        val koin = sessionKoin()
        try {
            val factory = KoinVaultSessionFactory(koin)
            val repository = SessionRepositoryImpl()
            val previous = factory.create(masterKey)
            repository.put(previous.asMasterSession())
            val candidate = factory.create(masterKey)
            val previousScope = checkNotNull(previous.resolve { getSource<VaultScopeSource>() }).coroutineScope
            val publication = previousScope.async(
                StandardTestDispatcher(testScheduler, "caller"),
                start = CoroutineStart.UNDISPATCHED,
            ) {
                PutVaultSessionImpl(repository)(candidate.asMasterSession()).bind()
            }

            runCurrent()
            publication.join()

            assertTrue(publication.isCancelled)
            assertFalse(previous.active.value)
            assertTrue(candidate.active.value)
            assertSame(candidate, (repository.get().first() as MasterSession.Key).session)
            assertSame(masterKey, candidate.resolve { getSource<VaultScopeSource>()?.masterKey })
        } finally {
            koin.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `repository rejection closes the unpublished candidate`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler, "main"))
        val koin = sessionKoin()
        try {
            val candidate = KoinVaultSessionFactory(koin).create(masterKey)
            val repository = object : SessionReadWriteRepository {
                override fun get(): Flow<MasterSession?> = flowOf(null)
                override fun put(key: MasterSession): Unit = error("Publication rejected")
            }
            val failure = runCatching {
                PutVaultSessionImpl(repository)(candidate.asMasterSession()).bind()
            }.exceptionOrNull()

            assertIs<IllegalStateException>(failure)
            assertFalse(candidate.active.value)
            assertNull(candidate.resolve { this })
        } finally {
            koin.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `application-owned rotation records completion after retiring the previous vault`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler, "main"))
        val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler, "application"))
        val koin = sessionKoin()
        try {
            val factory = KoinVaultSessionFactory(koin)
            val repository = SessionRepositoryImpl()
            val previous = factory.create(masterKey)
            repository.put(previous.asMasterSession())
            val rotatedKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(2))
            val candidate = factory.create(rotatedKey)
            val completion = mutableListOf<String>()
            val rotation = applicationScope.async {
                PutVaultSessionImpl(repository)(candidate.asMasterSession(rotatedKey))
                    .flatTap {
                        ioEffect { completion += "password-use timestamp" }
                    }
                    .effectTap {
                        assertFalse(previous.active.value)
                        completion += "success and navigation"
                    }
                    .bind()
            }

            runCurrent()
            rotation.await()

            assertEquals(listOf("password-use timestamp", "success and navigation"), completion)
            assertTrue(candidate.active.value)
            assertSame(candidate, (repository.get().first() as MasterSession.Key).session)
        } finally {
            applicationScope.cancel()
            koin.close()
            Dispatchers.resetMain()
        }
    }

    private fun sessionKoin() = Koin().apply {
        loadModules(listOf(module { scope<VaultSessionScope> {} }))
    }

    private fun VaultSession.asMasterSession(key: MasterKey = masterKey) = MasterSession.Key(
        masterKey = key,
        session = this,
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.DISTANT_PAST,
    )

    private val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
}
