package com.artemchep.keyguard.common

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.util.io.FileSystemFailure
import com.artemchep.keyguard.util.io.FileSystemFailureKind
import com.artemchep.keyguard.util.io.LocalPath
import com.artemchep.keyguard.util.io.artifact.SweepReport
import com.artemchep.keyguard.util.io.artifact.SweepStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TemporaryArtifactMaintenanceTest {
    @Test
    fun `app worker schedules maintenance once after its startup delay`() = runTest {
        var maintenanceCalls = 0
        val worker = AppWorkerIm(
            getVaultSession = EmptyGetVaultSession,
            updateVersionLog = NoOpUpdateVersionLog,
            temporaryArtifactMaintenance = TemporaryArtifactMaintenance {
                maintenanceCalls += 1
            },
        )
        val job = worker.launch(
            scope = backgroundScope,
            flow = flowOf(LeLifecycleState.STARTED),
        )

        runCurrent()
        advanceTimeBy(14_999L)
        runCurrent()
        assertEquals(0, maintenanceCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, maintenanceCalls)

        job.cancel()
    }

    @Test
    fun `a failing root provider does not prevent another root from being swept`() = runTest {
        val privatePath = "/private/secret/keyguard"
        val cachePath = LocalPath("/cache/keyguard")
        val swept = mutableListOf<LocalPath>()
        val logs = RecordingMaintenanceLogRepository()
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("private-temporary") {
                    throw IllegalStateException("Failed to create $privatePath")
                },
                TemporaryArtifactRoot("cache") {
                    cachePath
                },
            ),
            sweeper = { directory, _ ->
                swept += directory
                completeReport()
            },
            logs = logs,
        )

        maintenance()

        assertEquals(listOf(cachePath), swept)
        assertTrue(
            logs.messages.any { message ->
                "event=root_resolution_failed root=private-temporary" in message
            },
        )
        assertFalse(logs.messages.any { message -> privatePath in message })
        assertFalse(logs.messages.any { message -> cachePath.value in message })
    }

    @Test
    fun `duplicate resolved roots are swept once`() = runTest {
        val directory = LocalPath("/same/root")
        var sweepCount = 0
        val logs = RecordingMaintenanceLogRepository()
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("private-temporary") { directory },
                TemporaryArtifactRoot("cache") { directory },
            ),
            sweeper = { _, _ ->
                sweepCount += 1
                completeReport()
            },
            logs = logs,
        )

        maintenance()

        assertEquals(1, sweepCount)
        assertTrue(
            logs.messages.any { message ->
                message == "event=root_deduplicated root=cache duplicateOf=private-temporary"
            },
        )
    }

    @Test
    fun `busy incomplete and changed reports receive bounded injected retries`() = runTest {
        val reports = ArrayDeque(
            listOf(
                busyReport(),
                incompleteReport(),
                completeReport(
                    candidateNames = 2uL,
                    skippedBusy = 1uL,
                    skippedChanged = 1uL,
                ),
                completeReport(),
            ),
        )
        val requestedAges = mutableListOf<Duration>()
        val delays = mutableListOf<Duration>()
        val logs = RecordingMaintenanceLogRepository()
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("cache") { LocalPath("/cache") },
            ),
            sweeper = { _, olderThan ->
                requestedAges += olderThan
                reports.removeFirst()
            },
            logs = logs,
            retryDelay = delays::add,
            retryDelays = listOf(1.seconds, 2.seconds, 3.seconds),
        )

        maintenance()

        assertEquals(emptyList(), reports)
        assertContentEquals(List(4) { 24.hours }, requestedAges)
        assertContentEquals(listOf(1.seconds, 2.seconds, 3.seconds), delays)
        assertTrue(
            logs.messages.any { message ->
                "status=Busy" in message && "retry=true" in message
            },
        )
        assertTrue(
            logs.messages.any { message ->
                "status=Incomplete" in message && "retry=true" in message
            },
        )
        assertTrue(
            logs.messages.any { message ->
                "skippedBusy=1" in message &&
                    "skippedChanged=1" in message &&
                    "retry=true" in message
            },
        )
        assertTrue(
            logs.messages.any { message ->
                "status=Complete" in message &&
                    "entriesSeen=0" in message &&
                    "retry=false" in message
            },
        )
    }

    @Test
    fun `retry budget is enforced independently for each root`() = runTest {
        val attempts = mutableMapOf<LocalPath, Int>()
        val delayedRootEnteredBackoff = CompletableDeferred<Unit>()
        val releaseBackoff = CompletableDeferred<Unit>()
        val completedWithoutWaiting = CompletableDeferred<Unit>()
        val delayedRoot = LocalPath("/busy")
        val readyRoot = LocalPath("/ready")
        val logs = RecordingMaintenanceLogRepository()
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("busy") { delayedRoot },
                TemporaryArtifactRoot("ready") { readyRoot },
            ),
            sweeper = { directory, _ ->
                attempts[directory] = attempts.getOrElse(directory) { 0 } + 1
                if (directory == delayedRoot) {
                    busyReport()
                } else {
                    completedWithoutWaiting.complete(Unit)
                    completeReport()
                }
            },
            logs = logs,
            retryDelay = {
                delayedRootEnteredBackoff.complete(Unit)
                releaseBackoff.await()
            },
            retryDelays = listOf(1.seconds, 2.seconds),
        )

        val job = async {
            maintenance()
        }
        delayedRootEnteredBackoff.await()
        completedWithoutWaiting.await()
        assertEquals(1, attempts[readyRoot])

        releaseBackoff.complete(Unit)
        job.await()

        assertEquals(3, attempts[delayedRoot])
        assertEquals(1, attempts[readyRoot])
        assertTrue(
            logs.messages.any { message ->
                message == "event=sweep_retry_exhausted root=busy attempts=3"
            },
        )
    }

    @Test
    fun `nonfatal sweep failure is retried without logging its path-bearing message`() = runTest {
        val leakedPath = "/private/do-not-log"
        var attempts = 0
        val delays = mutableListOf<Duration>()
        val logs = RecordingMaintenanceLogRepository()
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("private-temporary") { LocalPath(leakedPath) },
            ),
            sweeper = { _, _ ->
                attempts += 1
                if (attempts == 1) {
                    throw IllegalStateException("Could not inspect $leakedPath")
                }
                completeReport()
            },
            logs = logs,
            retryDelay = delays::add,
            retryDelays = listOf(1.seconds),
        )

        maintenance()

        assertEquals(2, attempts)
        assertEquals(listOf(1.seconds), delays)
        assertTrue(logs.messages.any { "event=sweep_failed" in it })
        assertFalse(logs.messages.any { leakedPath in it })
    }

    @Test
    fun `cancellation from a sweep is propagated without retry`() = runTest {
        var delayCalls = 0
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("cache") { LocalPath("/cache") },
            ),
            sweeper = { _, _ ->
                throw CancellationException("cancel maintenance")
            },
            logs = RecordingMaintenanceLogRepository(),
            retryDelay = {
                delayCalls += 1
            },
        )

        assertFailsWith<CancellationException> {
            maintenance()
        }
        assertEquals(0, delayCalls)
    }

    @Test
    fun `fatal root provider error is propagated and stops root resolution`() = runTest {
        var laterProviderCalls = 0
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("private-temporary") {
                    throw AssertionError("fatal")
                },
                TemporaryArtifactRoot("cache") {
                    laterProviderCalls += 1
                    LocalPath("/cache")
                },
            ),
            sweeper = { _, _ -> completeReport() },
            logs = RecordingMaintenanceLogRepository(),
        )

        assertFailsWith<AssertionError> {
            maintenance()
        }
        assertEquals(0, laterProviderCalls)
    }

    @Test
    fun `cancellation during injected backoff stops further attempts`() = runTest {
        var attempts = 0
        val delayStarted = CompletableDeferred<Unit>()
        val neverResume = CompletableDeferred<Unit>()
        val maintenance = maintenance(
            roots = listOf(
                TemporaryArtifactRoot("cache") { LocalPath("/cache") },
            ),
            sweeper = { _, _ ->
                attempts += 1
                busyReport()
            },
            logs = RecordingMaintenanceLogRepository(),
            retryDelay = {
                delayStarted.complete(Unit)
                neverResume.await()
            },
        )

        val job = async {
            maintenance()
        }
        delayStarted.await()
        job.cancel()
        runCurrent()

        assertFailsWith<CancellationException> {
            job.await()
        }
        assertEquals(1, attempts)
    }

    private fun maintenance(
        roots: List<TemporaryArtifactRoot>,
        sweeper: suspend (LocalPath, Duration) -> SweepReport,
        logs: RecordingMaintenanceLogRepository,
        retryDelay: suspend (Duration) -> Unit = {},
        retryDelays: List<Duration> = listOf(1.seconds, 2.seconds),
    ) = TemporaryArtifactMaintenanceImpl(
        roots = roots,
        sweeper = sweeper,
        logRepository = logs,
        retryDelay = retryDelay,
        retryDelays = retryDelays,
    )
}

private class RecordingMaintenanceLogRepository : LogRepository {
    val entries = mutableListOf<MaintenanceLogEntry>()

    val messages: List<String>
        get() = entries.map { entry -> entry.message }

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        entries += MaintenanceLogEntry(
            tag = tag,
            message = message,
            level = level,
        )
    }
}

private object EmptyGetVaultSession : GetVaultSession {
    override val valueOrNull: MasterSession? = null

    override fun invoke(): Flow<MasterSession> =
        flowOf(MasterSession.Empty())
}

private object NoOpUpdateVersionLog : UpdateVersionLog {
    override fun invoke(): IO<Unit> = {}
}

private data class MaintenanceLogEntry(
    val tag: String,
    val message: String,
    val level: LogLevel,
)

private fun completeReport(
    candidateNames: ULong = 0uL,
    skippedBusy: ULong = 0uL,
    skippedChanged: ULong = 0uL,
): SweepReport = SweepReport(
    status = SweepStatus.Complete,
    entriesSeen = candidateNames,
    candidateNames = candidateNames,
    removed = candidateNames - skippedBusy - skippedChanged,
    skippedYoung = 0uL,
    skippedBusy = skippedBusy,
    skippedUnsafe = 0uL,
    skippedChanged = skippedChanged,
    inspectionFailed = 0uL,
    removalFailed = 0uL,
    firstFailure = null,
)

private fun busyReport(): SweepReport = SweepReport(
    status = SweepStatus.Busy,
    entriesSeen = 0uL,
    candidateNames = 0uL,
    removed = 0uL,
    skippedYoung = 0uL,
    skippedBusy = 0uL,
    skippedUnsafe = 0uL,
    skippedChanged = 0uL,
    inspectionFailed = 0uL,
    removalFailed = 0uL,
    firstFailure = null,
)

private fun incompleteReport(): SweepReport = SweepReport(
    status = SweepStatus.Incomplete,
    entriesSeen = 1uL,
    candidateNames = 1uL,
    removed = 0uL,
    skippedYoung = 0uL,
    skippedBusy = 0uL,
    skippedUnsafe = 0uL,
    skippedChanged = 0uL,
    inspectionFailed = 1uL,
    removalFailed = 0uL,
    firstFailure = FileSystemFailure(
        kind = FileSystemFailureKind.Other,
    ),
)
