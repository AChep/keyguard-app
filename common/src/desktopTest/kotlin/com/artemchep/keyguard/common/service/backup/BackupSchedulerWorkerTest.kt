package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSchedulerWorkerTest {
    @Test
    fun `start collects scheduler and runs automatic backup after debounce`() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.CREATED)
        val config = MutableStateFlow(
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.Local(
                    path = "/tmp/keyguard-backups",
                ),
            ),
        )
        val status = MutableStateFlow(
            BackupStatus(
                changeGeneration = 1L,
                lastSuccessfulBackupChangeGeneration = 0L,
            ),
        )
        val session = MutableStateFlow<MasterSession?>(authenticatedSession())
        var runs = 0
        val repository = FakeBackupConfigRepository(
            config = config,
            status = status,
        )
        val worker = BackupSchedulerWorker(
            sessionReadRepository = FakeSessionReadRepository(session),
            getBackupConfigRepository = {
                repository
            },
            runAutomatic = {
                runs++
            },
        )

        worker.start(backgroundScope, lifecycle)
        runCurrent()
        assertEquals(0, runs)

        lifecycle.value = LeLifecycleState.STARTED
        advanceTimeBy(AutomaticBackupPolicy.DEBOUNCE_DELAY_MS - 1L)
        runCurrent()
        assertEquals(0, runs)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun `start does not run automatic backup while vault is locked`() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.CREATED)
        val session = MutableStateFlow<MasterSession?>(MasterSession.Empty())
        var runs = 0
        val worker = BackupSchedulerWorker(
            sessionReadRepository = FakeSessionReadRepository(session),
            getBackupConfigRepository = {
                error("Backup config repository should not be resolved while locked.")
            },
            runAutomatic = {
                runs++
            },
        )

        worker.start(backgroundScope, lifecycle)
        lifecycle.value = LeLifecycleState.STARTED
        advanceTimeBy(AutomaticBackupPolicy.DEBOUNCE_DELAY_MS)
        runCurrent()

        assertEquals(0, runs)
    }

    @Test
    fun `start runs automatic backup for persisted dirty vault`() = runTest {
        val lifecycle = MutableStateFlow(LeLifecycleState.CREATED)
        val config = MutableStateFlow(
            BackupConfig(
                enabled = true,
                store = BackupStoreConfig.Local(
                    path = "/tmp/keyguard-backups",
                ),
            ),
        )
        val status = MutableStateFlow(
            BackupStatus(
                changeGeneration = 1L,
                lastSuccessfulBackupChangeGeneration = 0L,
            ),
        )
        val session = MutableStateFlow<MasterSession?>(
            keySession(origin = MasterSession.Key.Persisted),
        )
        var runs = 0
        val repository = FakeBackupConfigRepository(
            config = config,
            status = status,
        )
        val worker = BackupSchedulerWorker(
            sessionReadRepository = FakeSessionReadRepository(session),
            getBackupConfigRepository = {
                repository
            },
            runAutomatic = {
                runs++
            },
        )

        worker.start(backgroundScope, lifecycle)
        lifecycle.value = LeLifecycleState.STARTED
        advanceTimeBy(AutomaticBackupPolicy.DEBOUNCE_DELAY_MS)
        runCurrent()

        assertEquals(1, runs)
    }

    private fun authenticatedSession() = keySession(
        origin = MasterSession.Key.Authenticated,
    )

    private fun keySession(
        origin: MasterSession.Key.Origin,
    ) = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.V0,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        di = DI {},
        origin = origin,
        createdAt = Instant.fromEpochMilliseconds(1L),
    )

    private class FakeBackupConfigRepository(
        private val config: MutableStateFlow<BackupConfig>,
        private val status: MutableStateFlow<BackupStatus>,
    ) : BackupConfigRepository {
        override fun getConfig(): Flow<BackupConfig> = config

        override fun setConfig(
            config: BackupConfig,
        ): IO<Unit> = ioEffect {
            this.config.value = config
        }

        override fun getStatus(): Flow<BackupStatus> = status

        override fun setStatus(
            status: BackupStatus,
        ): IO<Unit> = ioEffect {
            this.status.value = status
        }

        override fun setStatusAfterSuccessfulRun(
            status: BackupStatus,
            runStartedChangeGeneration: Long,
        ): IO<Unit> = ioEffect {
            this.status.value = status.copy(
                lastSuccessfulBackupChangeGeneration = runStartedChangeGeneration,
            )
        }

        override fun setCurrentRun(
            progress: BackupRunProgress?,
        ): IO<Unit> = ioEffect {
            status.value = status.value.copy(
                currentRun = progress,
            )
        }

        override fun markDirty(
            now: Instant,
        ): IO<BackupStatus> = ioEffect {
            val updated = status.value.copy(
                changeGeneration = status.value.changeGeneration + 1L,
                lastChangedAt = now,
            )
            status.value = updated
            updated
        }
    }

    private class FakeSessionReadRepository(
        private val session: Flow<MasterSession?>,
    ) : SessionReadRepository {
        override fun get(): Flow<MasterSession?> = session
    }
}
