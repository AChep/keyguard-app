package com.artemchep.keyguard.common.usecase.impl.benchmark

import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil

internal data class WatchtowerBenchmarkObservation(
    val resultCount: Int,
    val threatCount: Int,
    val checksum: Long,
)

internal data class WatchtowerBenchmarkCase(
    val name: String,
    val alertType: String,
    val cipherCount: Int,
    val run: suspend () -> Any,
    val observe: (Any) -> WatchtowerBenchmarkObservation,
)

internal data class WatchtowerBenchmarkResult(
    val name: String,
    val alertType: String,
    val cipherCount: Int,
    val resultCount: Int,
    val threatCount: Int,
    val samplesNs: List<Long>,
    val samplesAllocatedBytes: List<Long>,
) {
    val medianNs: Long = percentile(samplesNs, 0.50)
    val p90Ns: Long = percentile(samplesNs, 0.90)
    val throughputPerSecond: Double = cipherCount * 1_000_000_000.0 / medianNs
    val medianAllocatedBytes: Long? = samplesAllocatedBytes
        .takeIf { it.isNotEmpty() }
        ?.let { percentile(it, 0.50) }
}

internal class WatchtowerBenchmarkHarness(
    private val warmupIterations: Int = 3,
    private val measurementIterations: Int = 7,
    private val output: (String) -> Unit = ::println,
) {
    @Volatile
    private var blackhole: Long = 0L

    suspend fun run(
        cases: List<WatchtowerBenchmarkCase>,
    ): List<WatchtowerBenchmarkResult> {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        return try {
            cases.map { benchmarkCase ->
                runCase(benchmarkCase)
            }.also(::writeCsvReport)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private suspend fun runCase(
        benchmarkCase: WatchtowerBenchmarkCase,
    ): WatchtowerBenchmarkResult {
        repeat(warmupIterations) {
            consume(benchmarkCase.observe(benchmarkCase.run()))
        }

        val samplesNs = ArrayList<Long>(measurementIterations)
        val samplesAllocatedBytes = ArrayList<Long>(measurementIterations)
        var lastObservation: WatchtowerBenchmarkObservation? = null
        repeat(measurementIterations) { iteration ->
            val event = WatchtowerCheckEvent().apply {
                check = benchmarkCase.name
                alertType = benchmarkCase.alertType
                ciphers = benchmarkCase.cipherCount
                this.iteration = iteration
            }
            event.begin()
            val allocationMark = ThreadAllocationTracker.mark()
            val startedAt = System.nanoTime()
            val rawResult = benchmarkCase.run()
            val elapsedNs = System.nanoTime() - startedAt
            val allocatedBytes = ThreadAllocationTracker.allocatedSince(allocationMark)
            event.end()
            val observation = benchmarkCase.observe(rawResult)
            event.results = observation.resultCount
            event.threats = observation.threatCount
            event.allocatedBytes = allocatedBytes ?: -1L
            event.commit()

            consume(observation)
            lastObservation = observation
            samplesNs += elapsedNs
            allocatedBytes?.let(samplesAllocatedBytes::add)
        }

        val observation = requireNotNull(lastObservation)
        val result = WatchtowerBenchmarkResult(
            name = benchmarkCase.name,
            alertType = benchmarkCase.alertType,
            cipherCount = benchmarkCase.cipherCount,
            resultCount = observation.resultCount,
            threatCount = observation.threatCount,
            samplesNs = samplesNs,
            samplesAllocatedBytes = samplesAllocatedBytes,
        )
        output(result.format())
        return result
    }

    private fun consume(observation: WatchtowerBenchmarkObservation) {
        blackhole = blackhole xor observation.checksum xor observation.threatCount.toLong()
    }

    private fun writeCsvReport(results: List<WatchtowerBenchmarkResult>) {
        val outputPath = System.getProperty("keyguard.watchtower.benchmark.output")
            ?: return
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine(
                    "check,alert_type,ciphers,results,threats,median_ns,p90_ns," +
                            "median_allocated_bytes,allocated_bytes_per_cipher," +
                            "throughput_ciphers_per_second,samples_ns,samples_allocated_bytes",
                )
                results.forEach { result ->
                    append(result.name)
                    append(',')
                    append(result.alertType)
                    append(',')
                    append(result.cipherCount)
                    append(',')
                    append(result.resultCount)
                    append(',')
                    append(result.threatCount)
                    append(',')
                    append(result.medianNs)
                    append(',')
                    append(result.p90Ns)
                    append(',')
                    append(result.medianAllocatedBytes.orEmpty())
                    append(',')
                    append(
                        result.medianAllocatedBytes
                            ?.let { String.format(Locale.US, "%.2f", it.toDouble() / result.cipherCount) }
                            .orEmpty(),
                    )
                    append(',')
                    append(String.format(Locale.US, "%.2f", result.throughputPerSecond))
                    append(',')
                    append(result.samplesNs.joinToString(separator = "|"))
                    append(',')
                    append(result.samplesAllocatedBytes.joinToString(separator = "|"))
                    appendLine()
                }
            },
        )
        output("[watchtower-benchmark] report=${file.absolutePath}")
    }

    private fun WatchtowerBenchmarkResult.format(): String = String.format(
        Locale.US,
        "[watchtower-benchmark] check=%s type=%s ciphers=%d results=%d threats=%d " +
        "warmup=%d measured=%d median=%.3f ms p90=%.3f ms allocated=%s throughput=%.1f ciphers/s",
        name,
        alertType,
        cipherCount,
        resultCount,
        threatCount,
        warmupIterations,
        measurementIterations,
        medianNs / 1_000_000.0,
        p90Ns / 1_000_000.0,
        medianAllocatedBytes?.let { allocatedBytes ->
            String.format(
                Locale.US,
                "%.3f MiB (%.1f B/cipher)",
                allocatedBytes / (1024.0 * 1024.0),
                allocatedBytes.toDouble() / cipherCount,
            )
        } ?: "unavailable",
        throughputPerSecond,
    )
}

private object ThreadAllocationTracker {
    private val bean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }
        ?.also { tracker ->
            if (!tracker.isThreadAllocatedMemoryEnabled) {
                tracker.isThreadAllocatedMemoryEnabled = true
            }
        }

    data class Mark(
        val threadId: Long,
        val allocatedBytes: Long,
    )

    fun mark(): Mark? {
        val tracker = bean ?: return null
        val threadId = Thread.currentThread().threadId()
        return Mark(
            threadId = threadId,
            allocatedBytes = tracker.getThreadAllocatedBytes(threadId),
        )
    }

    fun allocatedSince(mark: Mark?): Long? {
        val tracker = bean ?: return null
        mark ?: return null
        val threadId = Thread.currentThread().threadId()
        if (threadId != mark.threadId) {
            return null
        }
        return (tracker.getThreadAllocatedBytes(threadId) - mark.allocatedBytes)
            .coerceAtLeast(0L)
    }
}

private fun Long?.orEmpty(): String = this?.toString().orEmpty()

@Name("keyguard.WatchtowerCheck")
@Label("Watchtower check")
@Category("Keyguard")
private class WatchtowerCheckEvent : Event() {
    @Label("Check")
    var check: String = ""

    @Label("Alert type")
    var alertType: String = ""

    @Label("Ciphers")
    var ciphers: Int = 0

    @Label("Iteration")
    var iteration: Int = 0

    @Label("Results")
    var results: Int = 0

    @Label("Threats")
    var threats: Int = 0

    @Label("Allocated bytes")
    var allocatedBytes: Long = -1L
}

private fun percentile(
    samples: List<Long>,
    percentile: Double,
): Long {
    require(samples.isNotEmpty())
    require(percentile in 0.0..1.0)
    val sorted = samples.sorted()
    val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
