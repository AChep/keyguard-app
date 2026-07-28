package com.artemchep.macrobenchmark.vault

import android.os.Build
import android.os.SystemClock
import android.os.Trace
import androidx.annotation.RequiresApi
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.traceprocessor.TraceProcessor
import androidx.test.uiautomator.UiObject2
import com.artemchep.macrobenchmark.PACKAGE_NAME
import com.artemchep.macrobenchmark.enableBenchmarkScreenRecording
import com.artemchep.test.feature.RootScreen
import com.artemchep.test.feature.coreFeature
import com.artemchep.test.feature.enterPasswordAndFindButton
import com.artemchep.test.feature.resourceName
import com.artemchep.test.feature.waitForRootScreen
import org.junit.Rule
import org.junit.Test

private const val MASTER_PASSWORD = "111111"
private const val TEST_VAULT_ENTRY_COUNT = 5_000
private const val POST_UNLOCK_OBSERVATION_MS = 10_000L
private const val POST_UNLOCK_OBSERVATION_TRACE_SECTION = "vaultPostUnlockObservation"
private const val SEED_ACTIVITY =
    "com.artemchep.keyguard.benchmark.VaultBenchmarkSeedActivity"
private const val SEED_STATUS_FAILED_PREFIX = "benchmark:vault-seed-failed:"
private const val SEED_TIMEOUT_MS = 300_000L

/**
 * Measures the real app process from the unlock action through ten seconds after main appears.
 *
 * A benchmark-build-only setup activity imports a deterministic, mixed 5,000-item KeePass vault
 * before the first iteration. Creation, encryption, import, and initial synchronization all happen
 * outside the measured block; the measured journey starts from a killed, locked app process.
 */
@RequiresApi(Build.VERSION_CODES.P)
@OptIn(ExperimentalMetricApi::class)
class VaultUnlockMemoryBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private lateinit var unlockButton: UiObject2
    private var corpusPrepared = false

    @Test
    fun unlockAndObserveForTenSeconds() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(
            ArtGarbageCollectionMetric(),
            MemoryUsageMetric(
                mode = MemoryUsageMetric.Mode.Last,
                subMetrics = MEMORY_SUB_METRICS,
            ),
            MemoryUsageMetric(
                mode = MemoryUsageMetric.Mode.Max,
                subMetrics = MEMORY_SUB_METRICS,
            ),
        ),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            pressHome()
            killProcess()
            if (!corpusPrepared) {
                clearTargetApplicationData()
                enableBenchmarkScreenRecording()
            }
            startActivityAndWait()
            prepareUnlockScreen()
        },
    ) {
        unlockButton.click()

        val mainScreen = coreFeature.waitForRootScreen(RootScreen.MAIN)
        requireNotNull(mainScreen) {
            "Vault did not reach the main screen after unlock."
        }
        Trace.beginSection(POST_UNLOCK_OBSERVATION_TRACE_SECTION)
        try {
            SystemClock.sleep(POST_UNLOCK_OBSERVATION_MS)
        } finally {
            Trace.endSection()
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.prepareUnlockScreen() {
        var rootScreen = requireNotNull(
            coreFeature.waitForRootScreen(
                RootScreen.SETUP,
                RootScreen.UNLOCK,
                RootScreen.MAIN,
            ),
        ) {
            "Could not find a Keyguard root screen."
        }

        if (!corpusPrepared) {
            if (
                rootScreen.resourceName == RootScreen.SETUP.resourceName() ||
                rootScreen.resourceName == RootScreen.UNLOCK.resourceName()
            ) {
                coreFeature.enterPasswordAndFindButton(MASTER_PASSWORD).click()
                rootScreen = requireNotNull(
                    coreFeature.waitForRootScreen(RootScreen.MAIN),
                ) {
                    "Vault preparation did not reach the main screen."
                }
            }

            require(rootScreen.resourceName == RootScreen.MAIN.resourceName()) {
                "Expected the main screen before preparing benchmark data, but found " +
                    "'${rootScreen.resourceName}'."
            }
            seedTestVault()
            corpusPrepared = true

            killProcess()
            startActivityAndWait()
            rootScreen = requireNotNull(
                coreFeature.waitForRootScreen(RootScreen.UNLOCK),
            ) {
                "Fresh vault did not return to the unlock screen after process death."
            }
        }

        require(rootScreen.resourceName == RootScreen.UNLOCK.resourceName()) {
            "Expected a locked vault, but found '${rootScreen.resourceName}'. " +
                "Disable any device-specific automatic unlock before benchmarking."
        }
        unlockButton = coreFeature.enterPasswordAndFindButton(MASTER_PASSWORD)
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.seedTestVault() {
        val result = device.executeShellCommand(
            "am start -W -n $PACKAGE_NAME/$SEED_ACTIVITY " +
                "--ei entryCount $TEST_VAULT_ENTRY_COUNT",
        )
        require("Error:" !in result) {
            "Could not launch the benchmark vault seeder: $result"
        }

        val completeDescription = "benchmark:vault-seed-complete:$TEST_VAULT_ENTRY_COUNT"
        val completed = onElementOrNull(timeoutMs = SEED_TIMEOUT_MS) {
            contentDescription?.toString() == completeDescription
        }
        if (completed == null) {
            val failure = onElementOrNull(timeoutMs = 0L) {
                contentDescription?.toString()?.startsWith(SEED_STATUS_FAILED_PREFIX) == true
            }
                ?.contentDescription
            error(failure ?: "Benchmark vault seeding timed out after $SEED_TIMEOUT_MS ms.")
        }
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clearTargetApplicationData() {
        val result = device.executeShellCommand("pm clear $PACKAGE_NAME").trim()
        require(result == "Success") {
            "Could not clear stale benchmark application data: $result"
        }
    }

    private companion object {
        val MEMORY_SUB_METRICS = listOf(
            MemoryUsageMetric.SubMetric.HeapSize,
            MemoryUsageMetric.SubMetric.RssAnon,
            MemoryUsageMetric.SubMetric.RssFile,
        )
    }
}

/**
 * Reports ART garbage-collection work from the unlock action through the observation window.
 *
 * The query uses Perfetto's public `android.garbage_collection` standard-library module rather
 * than matching runtime-specific slice names. Wall and CPU time are clipped to the unlock and
 * observation windows. Counts and reclaimed memory are assigned to the window in which a
 * collection completes, so the phase measurements do not double-count boundary-spanning GCs.
 */
@OptIn(ExperimentalMetricApi::class)
private class ArtGarbageCollectionMetric : TraceMetric() {
    override fun getMeasurements(
        captureInfo: Metric.CaptureInfo,
        traceSession: TraceProcessor.Session,
    ): List<Metric.Measurement> {
        val processName = captureInfo.targetPackageName.replace("'", "''")
        val row = traceSession.query(
            """
            INCLUDE PERFETTO MODULE android.garbage_collection;
            INCLUDE PERFETTO MODULE android.monitor_contention;

            WITH measured_block AS (
                SELECT ts, dur
                FROM slice
                WHERE name = 'measureBlock'
                LIMIT 1
            ),
            observation_block AS (
                SELECT ts, dur
                FROM slice
                WHERE name = '$POST_UNLOCK_OBSERVATION_TRACE_SECTION'
                LIMIT 1
            ),
            windows AS (
                SELECT
                    measured_block.ts AS journey_start,
                    observation_block.ts AS observation_start,
                    observation_block.ts + observation_block.dur AS journey_end
                FROM measured_block, observation_block
            ),
            gc_events AS (
                SELECT
                    gc.gc_id,
                    gc.utid,
                    gc.gc_ts,
                    gc.gc_dur,
                    gc.gc_ts + gc.gc_dur AS gc_end,
                    gc.reclaimed_mb,
                    gc.max_heap_mb
                FROM android_garbage_collection_events gc, windows
                WHERE gc.process_name = '$processName'
                    AND gc.gc_ts < windows.journey_end
                    AND gc.gc_ts + gc.gc_dur > windows.journey_start
            ),
            gc_running_spans AS (
                SELECT
                    gc.gc_id,
                    MAX(run.ts, gc.gc_ts) AS run_ts,
                    MIN(run.ts + run.dur, gc.gc_end) -
                        MAX(run.ts, gc.gc_ts) AS run_dur
                FROM gc_events gc
                JOIN sched run
                    ON run.utid = gc.utid
                        AND run.ts < gc.gc_end
                        AND run.ts + run.dur > gc.gc_ts
                WHERE run.dur > 0
            )
            SELECT
                windows.observation_start - windows.journey_start AS unlock_elapsed_ns,
                (
                    SELECT COUNT(*)
                    FROM slice frame
                    JOIN thread_track frame_track ON frame.track_id = frame_track.id
                    JOIN thread frame_thread ON frame_track.utid = frame_thread.utid
                    JOIN process frame_process ON frame_thread.upid = frame_process.upid
                    WHERE frame_process.name = '$processName'
                        AND frame.name GLOB 'draw-VRI*'
                        AND frame.ts < windows.journey_end
                        AND frame.ts + frame.dur > windows.observation_start
                ) AS observation_frame_count,
                (
                    SELECT COALESCE(SUM(
                        MIN(render.ts + render.dur, windows.journey_end) -
                            MAX(render.ts, windows.observation_start)
                    ), 0)
                    FROM sched render
                    JOIN thread render_thread ON render.utid = render_thread.utid
                    JOIN process render_process ON render_thread.upid = render_process.upid
                    WHERE render_process.name = '$processName'
                        AND render_thread.name = 'RenderThread'
                        AND render.ts < windows.journey_end
                        AND render.ts + render.dur > windows.observation_start
                ) AS observation_render_cpu_time_ns,
                (
                    SELECT COUNT(*)
                    FROM android_monitor_contention contention
                    WHERE contention.process_name = '$processName'
                        AND contention.blocked_method GLOB '*SynchronizedLazyImpl.getValue*'
                        AND contention.ts < windows.observation_start
                        AND contention.ts + contention.dur > windows.journey_start
                ) AS unlock_synchronized_lazy_contention_count,
                (
                    SELECT COALESCE(MAX(
                        MIN(contention.ts + contention.dur, windows.observation_start) -
                            MAX(contention.ts, windows.journey_start)
                    ), 0)
                    FROM android_monitor_contention contention
                    WHERE contention.process_name = '$processName'
                        AND contention.blocked_method GLOB '*SynchronizedLazyImpl.getValue*'
                        AND contention.ts < windows.observation_start
                        AND contention.ts + contention.dur > windows.journey_start
                ) AS unlock_synchronized_lazy_contention_max_ns,
                (
                    SELECT COUNT(*)
                    FROM gc_events
                    WHERE gc_end >= windows.journey_start
                        AND gc_end < windows.journey_end
                ) AS gc_completed_count,
                (
                    SELECT COALESCE(SUM(
                        MIN(gc_end, windows.journey_end) -
                            MAX(gc_ts, windows.journey_start)
                    ), 0)
                    FROM gc_events
                ) AS gc_wall_time_ns,
                (
                    SELECT COALESCE(SUM(
                        MIN(run_ts + run_dur, windows.journey_end) -
                            MAX(run_ts, windows.journey_start)
                    ), 0)
                    FROM gc_running_spans
                    WHERE run_ts < windows.journey_end
                        AND run_ts + run_dur > windows.journey_start
                ) AS gc_cpu_time_ns,
                (
                    SELECT COALESCE(SUM(reclaimed_mb), 0.0)
                    FROM gc_events
                    WHERE gc_end >= windows.journey_start
                        AND gc_end < windows.journey_end
                ) AS gc_reclaimed_mb,
                (
                    SELECT COALESCE(MAX(max_heap_mb), 0.0)
                    FROM gc_events
                ) AS gc_peak_heap_mb,
                (
                    SELECT COUNT(*)
                    FROM gc_events
                    WHERE gc_end >= windows.journey_start
                        AND gc_end < windows.observation_start
                ) AS unlock_gc_completed_count,
                (
                    SELECT COALESCE(SUM(
                        MIN(gc_end, windows.observation_start) -
                            MAX(gc_ts, windows.journey_start)
                    ), 0)
                    FROM gc_events
                    WHERE gc_ts < windows.observation_start
                        AND gc_end > windows.journey_start
                ) AS unlock_gc_wall_time_ns,
                (
                    SELECT COALESCE(SUM(
                        MIN(run_ts + run_dur, windows.observation_start) -
                            MAX(run_ts, windows.journey_start)
                    ), 0)
                    FROM gc_running_spans
                    WHERE run_ts < windows.observation_start
                        AND run_ts + run_dur > windows.journey_start
                ) AS unlock_gc_cpu_time_ns,
                (
                    SELECT COALESCE(SUM(reclaimed_mb), 0.0)
                    FROM gc_events
                    WHERE gc_end >= windows.journey_start
                        AND gc_end < windows.observation_start
                ) AS unlock_gc_reclaimed_mb,
                (
                    SELECT COUNT(*)
                    FROM gc_events
                    WHERE gc_end >= windows.observation_start
                        AND gc_end < windows.journey_end
                ) AS observation_gc_completed_count,
                (
                    SELECT COALESCE(SUM(
                        MIN(gc_end, windows.journey_end) -
                            MAX(gc_ts, windows.observation_start)
                    ), 0)
                    FROM gc_events
                    WHERE gc_ts < windows.journey_end
                        AND gc_end > windows.observation_start
                ) AS observation_gc_wall_time_ns,
                (
                    SELECT COALESCE(SUM(
                        MIN(run_ts + run_dur, windows.journey_end) -
                            MAX(run_ts, windows.observation_start)
                    ), 0)
                    FROM gc_running_spans
                    WHERE run_ts < windows.journey_end
                        AND run_ts + run_dur > windows.observation_start
                ) AS observation_gc_cpu_time_ns,
                (
                    SELECT COALESCE(SUM(reclaimed_mb), 0.0)
                    FROM gc_events
                    WHERE gc_end >= windows.observation_start
                        AND gc_end < windows.journey_end
                ) AS observation_gc_reclaimed_mb
            FROM windows
            """.trimIndent(),
        ).firstOrNull() ?: error(
            "Required benchmark trace sections were not captured.",
        )

        return listOf(
            Metric.Measurement(
                "vaultUnlockElapsedMs",
                row.long("unlock_elapsed_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "vaultObservationFrameCount",
                row.long("observation_frame_count").toDouble(),
            ),
            Metric.Measurement(
                "vaultObservationRenderThreadCpuTimeMs",
                row.long("observation_render_cpu_time_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "vaultUnlockSynchronizedLazyContentionCount",
                row.long("unlock_synchronized_lazy_contention_count").toDouble(),
            ),
            Metric.Measurement(
                "vaultUnlockSynchronizedLazyContentionMaxMs",
                row.long("unlock_synchronized_lazy_contention_max_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "artGcCompletedCount",
                row.long("gc_completed_count").toDouble(),
            ),
            Metric.Measurement(
                "artGcWallTimeMs",
                row.long("gc_wall_time_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "artGcCpuTimeMs",
                row.long("gc_cpu_time_ns") / 1_000_000.0,
            ),
            Metric.Measurement("artGcReclaimedMb", row.double("gc_reclaimed_mb")),
            Metric.Measurement("artGcPeakHeapMb", row.double("gc_peak_heap_mb")),
            Metric.Measurement(
                "artGcUnlockCompletedCount",
                row.long("unlock_gc_completed_count").toDouble(),
            ),
            Metric.Measurement(
                "artGcUnlockWallTimeMs",
                row.long("unlock_gc_wall_time_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "artGcUnlockCpuTimeMs",
                row.long("unlock_gc_cpu_time_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "artGcUnlockReclaimedMb",
                row.double("unlock_gc_reclaimed_mb"),
            ),
            Metric.Measurement(
                "artGcObservationCompletedCount",
                row.long("observation_gc_completed_count").toDouble(),
            ),
            Metric.Measurement(
                "artGcObservationWallTimeMs",
                row.long("observation_gc_wall_time_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "artGcObservationCpuTimeMs",
                row.long("observation_gc_cpu_time_ns") / 1_000_000.0,
            ),
            Metric.Measurement(
                "artGcObservationReclaimedMb",
                row.double("observation_gc_reclaimed_mb"),
            ),
        )
    }
}
