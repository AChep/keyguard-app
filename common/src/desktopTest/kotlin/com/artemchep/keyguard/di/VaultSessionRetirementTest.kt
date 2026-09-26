package com.artemchep.keyguard.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.dsl.module

class VaultSessionRetirementTest {
    @Test
    fun `retirement waits for an active resolution and rejects later resolutions`() {
        VaultGraph().use { graph ->
            val session = graph.factory.create(masterKey)
            val enteredResolution = CountDownLatch(1)
            val releaseResolution = CountDownLatch(1)
            val startedRetirement = CountDownLatch(1)
            val completedRetirement = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2) { task ->
                Thread(task, "vault-retirement-test").apply { isDaemon = true }
            }
            try {
                val resolution = executor.submit<String?> {
                    session.resolve {
                        enteredResolution.countDown()
                        assertTrue(releaseResolution.await(5, TimeUnit.SECONDS))
                        "resolved"
                    }
                }
                assertTrue(enteredResolution.await(5, TimeUnit.SECONDS))
                val retirement = executor.submit {
                    startedRetirement.countDown()
                    session.retire()
                    completedRetirement.countDown()
                }
                assertTrue(startedRetirement.await(5, TimeUnit.SECONDS))

                assertFalse(completedRetirement.await(100, TimeUnit.MILLISECONDS))
                assertTrue(session.active.value)
                releaseResolution.countDown()

                assertEquals("resolved", resolution.get(5, TimeUnit.SECONDS))
                retirement.get(5, TimeUnit.SECONDS)
                assertFalse(session.active.value)
                assertNull(session.resolve { error("A retired handle must not enter resolution") })
                session.retire()
                session.close()
            } finally {
                releaseResolution.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `closing one vault cancels its work and preserves root and sibling work`() = runBlocking {
        VaultGraph().use { graph ->
            val first = graph.factory.create(masterKey)
            val second = graph.factory.create(masterKey)
            val firstSource = graph.source(first)
            val secondSource = graph.source(second)
            val firstWindow = checkNotNull(first.resolve { get<WindowCoroutineScope>() })
            val secondWindow = checkNotNull(second.resolve { get<WindowCoroutineScope>() })
            val rootJob = graph.rootScope.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            val sourceJob = firstSource.coroutineScope
                .launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            val windowJob = firstWindow.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            val siblingJob = secondWindow.launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }

            assertNotSame(graph.rootScope, firstWindow)
            assertNotSame(firstWindow, secondWindow)
            assertSame(firstWindow, first.resolve { get<WindowCoroutineScope>() })
            first.close()
            first.close()
            withTimeout(5_000) {
                sourceJob.join()
                windowJob.join()
            }

            assertTrue(sourceJob.isCancelled)
            assertTrue(windowJob.isCancelled)
            assertFalse(firstSource.coroutineScope.isActive)
            assertFalse(firstWindow.coroutineContext.job.isActive)
            assertTrue(rootJob.isActive)
            assertTrue(second.active.value)
            assertTrue(secondSource.coroutineScope.isActive)
            assertTrue(siblingJob.isActive)
            assertSame(graph.rootScope, graph.koin.get<WindowCoroutineScope>())
        }
    }

    @Test
    fun `a vault job can lock its own session without waiting for itself`() = runBlocking {
        VaultGraph().use { graph ->
            val session = graph.factory.create(masterKey)
            val window = checkNotNull(session.resolve { get<WindowCoroutineScope>() })
            val repository = SessionRepositoryImpl()
            repository.put(session.asMasterSession())
            val locked = MasterSession.Empty()
            val returnedFromLock = CompletableDeferred<Unit>()

            val lockingJob = window.launch {
                repository.put(locked)
                // Completion is deliberately non-suspending: locking cancels this job.
                returnedFromLock.complete(Unit)
            }
            withTimeout(5_000) {
                returnedFromLock.await()
                lockingJob.join()
            }

            assertTrue(lockingJob.isCancelled)
            assertFalse(session.active.value)
            assertSame(locked, repository.get().first())
            assertNull(session.resolve { this })
        }
    }

    @Test
    fun `application shutdown cancels all active vault work`() = runBlocking {
        VaultGraph().use { graph ->
            val first = graph.factory.create(masterKey)
            val second = graph.factory.create(masterKey)
            val firstSource = graph.source(first)
            val secondSource = graph.source(second)
            val firstJob = firstSource.coroutineScope
                .launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
            val secondJob = secondSource.coroutineScope
                .launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }

            graph.koin.close()
            withTimeout(5_000) {
                firstJob.join()
                secondJob.join()
            }

            assertTrue(firstJob.isCancelled)
            assertTrue(secondJob.isCancelled)
            assertFalse(first.active.value)
            assertFalse(second.active.value)
            assertNull(first.resolve { this })
            assertNull(second.resolve { this })
            first.close()
            second.close()
        }
    }

    @Test
    fun `repeated unlock and key rotation replace vault dependencies and preserve root services`() = runBlocking {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            val database = createUploadTestDatabase(driver)
            VaultGraph().use { graph ->
                graph.koin.loadModules(
                    listOf(
                        module {
                            scope<VaultSessionScope> {
                                scoped<VaultDatabaseManager> {
                                    SessionDatabaseManager(
                                        masterKey = get(),
                                        rootMessageService = get(),
                                        database = database,
                                    )
                                }
                            }
                        },
                    ),
                )
                val repository = SessionRepositoryImpl()
                val rootMessageService = graph.koin.get<ShowMessage>()
                var previous = graph.factory.create(masterKey)
                var previousManager = checkNotNull(
                    previous.resolve { get<VaultDatabaseManager>() },
                ) as SessionDatabaseManager
                repository.put(previous.asMasterSession())
                assertEquals(0, previousManager.openCount)
                assertSame(masterKey, previousManager.masterKey)
                val sessionIds = mutableSetOf(previous.id)
                val rotatedKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(2))

                for (key in listOf(masterKey, rotatedKey, rotatedKey.copy(byteArray = byteArrayOf(2)))) {
                    val previousSource = graph.source(previous)
                    val current = graph.factory.createAuthenticated(key)
                    val currentManager = checkNotNull(
                        current.resolve { get<VaultDatabaseManager>() },
                    ) as SessionDatabaseManager
                    repository.put(current.asMasterSession(key))

                    assertTrue(sessionIds.add(current.id))
                    assertSame(key, currentManager.masterKey)
                    assertEquals(1, currentManager.openCount)
                    assertNotSame(previousManager, currentManager)
                    assertSame(currentManager, current.resolve { get<VaultDatabaseManager>() })
                    assertSame(rootMessageService, currentManager.rootMessageService)
                    assertSame(rootMessageService, graph.koin.get<ShowMessage>())
                    assertTrue(graph.rootScope.isActive)
                    assertTrue(current.active.value)
                    assertFalse(previous.active.value)
                    assertFalse(previousSource.coroutineScope.isActive)
                    assertNull(previous.resolve { get<VaultDatabaseManager>() })
                    assertSame(current, (repository.get().first() as MasterSession.Key).session)
                    previous = current
                    previousManager = currentManager
                }

                assertNull(graph.koin.getOrNull<VaultDatabaseManager>())
                repository.put(MasterSession.Empty())
                assertFalse(previous.active.value)
                assertTrue(graph.rootScope.isActive)
            }
        }
    }

    private class VaultGraph : AutoCloseable {
        val rootScope = object : WindowCoroutineScope {
            override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default
        }
        val koin = Koin().apply {
            loadModules(
                listOf(
                    VaultOperationsModule().module,
                    module {
                        single<WindowCoroutineScope> { rootScope }
                        single<ShowMessage> {
                            object : ShowMessage {
                                override fun copy(value: ToastMessage, target: String?) =
                                    error("Lifecycle tests must not report an unexpected coroutine failure")
                            }
                        }
                    },
                ),
            )
        }
        val factory = KoinVaultSessionFactory(koin)

        fun source(session: VaultSession): VaultScopeSource =
            checkNotNull(session.resolve { getSource<VaultScopeSource>() })

        override fun close() {
            koin.close()
            rootScope.cancel()
        }
    }

    private class SessionDatabaseManager(
        val masterKey: MasterKey,
        val rootMessageService: ShowMessage,
        private val database: Database,
    ) : VaultDatabaseManager {
        var openCount = 0
            private set

        override fun get(): IO<Database> = {
            openCount++
            database
        }

        override fun <T> mutate(tag: String, block: suspend (Database) -> T): IO<T> =
            error("Session replacement must not mutate the database")

        override fun changePassword(newMasterKey: MasterKey): IO<Unit> =
            error("The fixture supplies an already rotated key")
    }

    private fun VaultSession.asMasterSession(key: MasterKey = masterKey) = MasterSession.Key(
        masterKey = key,
        session = this,
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.DISTANT_PAST,
    )

    private val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))
}
