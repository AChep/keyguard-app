package com.artemchep.keyguard.core.session

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.backup.BackupSchedulerWorker
import com.artemchep.keyguard.common.service.sshagent.bookmarks.RecordingSshBookmarksConfigStore
import com.artemchep.keyguard.common.service.sshagent.bookmarks.testSshBookmarksConfigSyncer
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.usecase.WatchtowerSyncer
import com.artemchep.keyguard.common.worker.WorkerRegistry
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopWorkerRegistryTest {
    @Test
    fun `desktop startup starts every worker`() = runTest {
        var watchtowerStarts = 0
        val watchtower = object : WatchtowerSyncer {
            override fun start(scope: CoroutineScope, flow: Flow<LeLifecycleState>) {
                watchtowerStarts++
            }
        }
        val backup = BackupSchedulerWorker(
            sessionReadRepository = object : SessionReadRepository {
                override fun get(): Flow<MasterSession?> = flowOf(null)
            },
            getBackupConfigRepository = { error("The test vault is locked") },
            runAutomatic = { error("The test vault is locked") },
        )
        // With the agent off, a started syncer removes the configuration.
        val bookmarksStore = RecordingSshBookmarksConfigStore()
        val bookmarks = testSshBookmarksConfigSyncer(
            store = bookmarksStore,
            sshAgent = flowOf(false),
        )
        val koin = Koin()
        koin.loadModules(
            listOf(
                PlatformApplicationModule().module,
                module {
                    single<WatchtowerSyncer> { watchtower }
                    single { backup }
                    single { bookmarks }
                },
            ),
        )
        try {
            val workers = koin.get<WorkerRegistry>().values
            assertEquals(listOf(backup, watchtower, bookmarks), workers)
            workers.forEach { it.start(backgroundScope, flowOf(LeLifecycleState.RESUMED)) }
            runCurrent()
            assertEquals(1, watchtowerStarts)
            assertEquals(1, bookmarksStore.clears)
        } finally {
            koin.close()
        }
    }
}
