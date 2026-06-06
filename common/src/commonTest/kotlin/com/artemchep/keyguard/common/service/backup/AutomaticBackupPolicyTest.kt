package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AutomaticBackupPolicyTest {
    private val runnableConfig = BackupConfig(
        enabled = true,
        store = BackupStoreConfig.Local(
            path = "/tmp/keyguard-backups",
        ),
    )

    @Test
    fun `policy runs only for configured dirty authenticated state`() {
        assertFalse(
            state(
                config = BackupConfig(
                    enabled = false,
                    store = BackupStoreConfig.Local(
                        path = "/tmp/keyguard-backups",
                    ),
                ),
                changeGeneration = 1L,
                authenticated = true,
            ).shouldRun,
        )
        assertFalse(
            state(
                changeGeneration = 1L,
                authenticated = false,
            ).shouldRun,
        )
        assertFalse(
            state(
                changeGeneration = 1L,
                lastSuccessfulBackupChangeGeneration = 1L,
                authenticated = true,
            ).shouldRun,
        )
        assertTrue(
            state(
                changeGeneration = 1L,
                authenticated = true,
            ).shouldRun,
        )
    }

    @Test
    fun `policy accepts unlocked key sessions`() {
        assertFalse(
            AutomaticBackupPolicy.isAuthenticatedInMemory(
                MasterSession.Empty(),
            ),
        )
        assertTrue(
            AutomaticBackupPolicy.isAuthenticatedInMemory(
                session(origin = MasterSession.Key.Persisted),
            ),
        )
        assertTrue(
            AutomaticBackupPolicy.isAuthenticatedInMemory(
                session(origin = MasterSession.Key.Authenticated),
            ),
        )
    }

    @Test
    fun `schedule state flow does not resolve backup config while vault is locked`() = runTest {
        val session = MutableStateFlow<MasterSession?>(MasterSession.Empty())

        val state = automaticBackupScheduleStateFlow(
            sessionReadRepository = FakeSessionReadRepository(session),
            getBackupConfigRepository = {
                error("Backup config repository should not be resolved while locked.")
            },
        ).first()

        assertFalse(state.authenticated)
        assertFalse(state.shouldRun)
    }

    @Test
    fun `schedule state flow emits runnable state for persisted dirty vault`() = runTest {
        val session = MutableStateFlow<MasterSession?>(
            session(origin = MasterSession.Key.Persisted),
        )
        val repository = FakeBackupConfigRepository(
            config = MutableStateFlow(runnableConfig),
            status = MutableStateFlow(
                BackupStatus(
                    changeGeneration = 1L,
                    lastSuccessfulBackupChangeGeneration = 0L,
                ),
            ),
        )

        val state = automaticBackupScheduleStateFlow(
            sessionReadRepository = FakeSessionReadRepository(session),
            getBackupConfigRepository = {
                repository
            },
        ).first()

        assertTrue(state.authenticated)
        assertTrue(state.shouldRun)
        assertEquals(1L, state.changeGeneration)
        assertEquals(0L, state.lastSuccessfulBackupChangeGeneration)
    }

    @Test
    fun `schedule state flow emits runnable state for authenticated dirty vault`() = runTest {
        val session = MutableStateFlow<MasterSession?>(
            session(origin = MasterSession.Key.Authenticated),
        )
        val repository = FakeBackupConfigRepository(
            config = MutableStateFlow(runnableConfig),
            status = MutableStateFlow(
                BackupStatus(
                    changeGeneration = 1L,
                    lastSuccessfulBackupChangeGeneration = 0L,
                ),
            ),
        )

        val state = automaticBackupScheduleStateFlow(
            sessionReadRepository = FakeSessionReadRepository(session),
            getBackupConfigRepository = {
                repository
            },
        ).first()

        assertTrue(state.authenticated)
        assertTrue(state.shouldRun)
        assertEquals(1L, state.changeGeneration)
        assertEquals(0L, state.lastSuccessfulBackupChangeGeneration)
    }

    @Test
    fun `debounce waits five seconds before triggering`() = runTest {
        val states = MutableStateFlow(state())
        val runs = mutableListOf<AutomaticBackupScheduleState>()
        val job = states
            .debounce(AutomaticBackupPolicy.DEBOUNCE_DELAY_MS)
            .filter { it.shouldRun }
            .onEach { runs += it }
            .launchIn(backgroundScope)

        states.value = state(
            changeGeneration = 1L,
            authenticated = true,
        )

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(emptyList(), runs)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(1L), runs.map { it.changeGeneration })

        job.cancel()
    }

    @Test
    fun `debounce restarts when dirty generation changes`() = runTest {
        val states = MutableStateFlow(state())
        val runs = mutableListOf<AutomaticBackupScheduleState>()
        val job = states
            .debounce(AutomaticBackupPolicy.DEBOUNCE_DELAY_MS)
            .filter { it.shouldRun }
            .onEach { runs += it }
            .launchIn(backgroundScope)

        states.value = state(
            changeGeneration = 1L,
            authenticated = true,
        )
        advanceTimeBy(4_000L)
        states.value = state(
            changeGeneration = 2L,
            authenticated = true,
        )

        advanceTimeBy(4_999L)
        runCurrent()
        assertEquals(emptyList(), runs)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(2L), runs.map { it.changeGeneration })

        job.cancel()
    }

    private fun state(
        config: BackupConfig = runnableConfig,
        changeGeneration: Long = 0L,
        lastSuccessfulBackupChangeGeneration: Long = 0L,
        authenticated: Boolean = false,
    ) = AutomaticBackupScheduleState(
        config = config,
        changeGeneration = changeGeneration,
        lastSuccessfulBackupChangeGeneration = lastSuccessfulBackupChangeGeneration,
        authenticated = authenticated,
    )

    private fun session(
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
