package com.artemchep.keyguard.apple.vault

import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.service.download.DownloadProgress
import com.artemchep.keyguard.common.service.export.ExportManager
import com.artemchep.keyguard.common.service.export.model.ExportRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExportExecutionTest {
    private val request = ExportRequest(DFilter.All, "password", attachments = true)

    @Test
    fun forwardsRequestPreventsDuplicatesAndReportsProgressUntilSaved() = runTest {
        val manager = FakeManager()
        var saved = 0
        val execution = ExportExecution(this, manager, { saved++ }, { throw it })
        execution.start(request)
        execution.start(request.copy(password = "duplicate"))
        assertTrue(execution.state.value.running)
        runCurrent()
        assertEquals(listOf(request), manager.requests)
        manager.progress.value = DownloadProgress.Loading(25, 100)
        runCurrent()
        assertEquals(25L, execution.state.value.downloaded)
        assertEquals(100L, execution.state.value.total)
        assertEquals(0, saved)
        manager.progress.value = DownloadProgress.Complete("file:///export.zip".right())
        runCurrent()
        assertEquals(1, saved)
        assertFalse(execution.state.value.running)
    }

    @Test
    fun failureAllowsRetryAndPickerDismissalDoesNotReportSuccess() = runTest {
        val manager = FakeManager()
        var failures = 0
        var saved = 0
        val execution = ExportExecution(this, manager, { saved++ }, { failures++ })
        execution.start(request)
        runCurrent()
        manager.progress.value = DownloadProgress.Complete(IllegalStateException("disk full").left())
        runCurrent()
        assertEquals(1, failures)
        assertFalse(execution.state.value.running)
        manager.progress.value = DownloadProgress.Loading()
        execution.start(request)
        runCurrent()
        manager.progress.value = DownloadProgress.Complete(null.right())
        runCurrent()
        assertEquals(2, manager.requests.size)
        assertEquals(0, saved)
        assertEquals(1, failures)
        assertFalse(execution.state.value.running)
    }

    @Test
    fun cancellationIsSilentAndScreenDisposalCancelsManager() = runTest {
        val manager = FakeManager()
        val screenScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val execution = ExportExecution(screenScope, manager, { error("saved") }, { throw it })
        execution.start(request)
        runCurrent()
        execution.cancel()
        runCurrent()
        assertFalse(execution.state.value.running)
        assertTrue(manager.cancelled.contains("export"))
        manager.cancelled.clear()
        manager.progress.value = DownloadProgress.Loading()
        execution.start(request)
        runCurrent()
        screenScope.cancel()
        runCurrent()
        assertTrue(manager.cancelled.contains("export"))
        assertFalse(execution.state.value.running)
    }

    @Test
    fun queueFailureResetsRunningState() = runTest {
        val manager = FakeManager().apply { failQueue = true }
        var failures = 0
        val execution = ExportExecution(this, manager, { error("saved") }, { failures++ })
        execution.start(request)
        runCurrent()
        assertEquals(1, failures)
        assertFalse(execution.state.value.running)
    }

    private class FakeManager : ExportManager {
        val requests = mutableListOf<ExportRequest>()
        val cancelled = mutableListOf<String>()
        val progress = MutableStateFlow<DownloadProgress>(DownloadProgress.Loading())
        var failQueue = false
        override suspend fun queue(request: ExportRequest): ExportManager.QueueResult {
            if (failQueue) error("Cannot queue")
            requests += request
            return ExportManager.QueueResult("export", progress.transformWhile {
                emit(it)
                it is DownloadProgress.Loading
            })
        }
        override fun cancel(exportId: String) {
            cancelled += exportId
            progress.value = DownloadProgress.Complete(CancellationException().left())
        }
        override fun getProgressFlowByExportId(exportId: String): Flow<Flow<DownloadProgress>?> =
            flowOf(progress)
    }
}
