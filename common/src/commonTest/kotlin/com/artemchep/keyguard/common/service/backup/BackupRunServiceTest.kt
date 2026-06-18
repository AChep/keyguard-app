package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.localization.TextHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.bindSingleton

class BackupRunServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `cancelling run clears in-memory current run`() = runTest {
        val repository = BackupConfigRepositoryImpl(
            store = JsonKeyValueStore(),
            json = json,
        )
        val service = service(
            repository = repository,
            scope = backgroundScope,
        )
        val progressReported = CompletableDeferred<Unit>()

        val job = backgroundScope.launch {
            service.runManual(
                progressReporter = BackupProgressReporter {
                    progressReported.complete(Unit)
                    awaitCancellation()
                },
            )
        }

        progressReported.await()
        assertNotNull(repository.getStatus().first().currentRun)

        job.cancelAndJoin()

        assertEquals(null, repository.getStatus().first().currentRun)
    }

    private fun service(
        repository: BackupConfigRepository,
        scope: CoroutineScope,
    ): BackupRunService {
        val session = session(repository)
        return BackupRunService(
            getVaultSession = TestGetVaultSession(session),
            sessionReadRepository = TestSessionReadRepository(session),
            vaultSessionLocker = VaultSessionLocker(
                getVaultLockAfterTimeout = TestGetVaultLockAfterTimeout,
                clearVaultSession = TestClearVaultSession,
                scope = scope,
            ),
            diagnostics = BackupDiagnostics.NoOp,
        )
    }

    private fun session(
        repository: BackupConfigRepository,
    ) = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.V0,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        di = DI {
            bindSingleton<BackupConfigRepository> {
                repository
            }
        },
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.fromEpochMilliseconds(1L),
    )

    private class TestGetVaultSession(
        private val session: MasterSession,
    ) : GetVaultSession {
        override val valueOrNull: MasterSession
            get() = session

        override fun invoke(): Flow<MasterSession> = flowOf(session)
    }

    private class TestSessionReadRepository(
        private val session: MasterSession?,
    ) : SessionReadRepository {
        override fun get(): Flow<MasterSession?> = flowOf(session)
    }

    private data object TestGetVaultLockAfterTimeout : GetVaultLockAfterTimeout {
        override fun invoke(): Flow<Duration> = emptyFlow()
    }

    private data object TestClearVaultSession : ClearVaultSession {
        override fun invoke(
            reason: LockReason,
            message: TextHolder,
        ): IO<Unit> = ioEffect {
            // no-op
        }
    }
}
