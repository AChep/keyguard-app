package com.artemchep.keyguard.common.usecase.impl.benchmark

import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil

internal data class CipherUrlBenchmarkObservation(
    val matchCount: Int,
    val checksum: Long,
)

internal data class CipherUrlBenchmarkCase(
    val name: String,
    val matchType: String,
    val operationCount: Int,
    val scenarioCount: Int,
    val run: suspend () -> CipherUrlBenchmarkObservation,
)

internal data class CipherUrlBenchmarkResult(
    val name: String,
    val matchType: String,
    val operationCount: Int,
    val scenarioCount: Int,
    val matchCount: Int,
    val samplesNs: List<Long>,
    val samplesAllocatedBytes: List<Long>,
) {
    val medianNs: Long = percentileCipherUrl(samplesNs, 0.50)
    val p90Ns: Long = percentileCipherUrl(samplesNs, 0.90)
    val operationsPerSecond: Double = operationCount * 1_000_000_000.0 / medianNs
    val medianAllocatedBytes: Long? = samplesAllocatedBytes
        .takeIf { it.isNotEmpty() }
        ?.let { percentileCipherUrl(it, 0.50) }
}

internal class CipherUrlBenchmarkHarness(
    private val warmupIterations: Int = 15,
    private val measurementIterations: Int = 9,
    private val output: (String) -> Unit = ::println,
) {
    @Volatile
    private var blackhole: Long = 0L

    suspend fun run(cases: List<CipherUrlBenchmarkCase>): List<CipherUrlBenchmarkResult> {
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
        benchmarkCase: CipherUrlBenchmarkCase,
    ): CipherUrlBenchmarkResult {
        repeat(warmupIterations) {
            consume(benchmarkCase.run())
        }

        val samplesNs = ArrayList<Long>(measurementIterations)
        val samplesAllocatedBytes = ArrayList<Long>(measurementIterations)
        var lastObservation: CipherUrlBenchmarkObservation? = null
        repeat(measurementIterations) { iteration ->
            val event = CipherUrlCheckEvent().apply {
                benchmark = benchmarkCase.name
                matchType = benchmarkCase.matchType
                operations = benchmarkCase.operationCount
                scenarios = benchmarkCase.scenarioCount
                this.iteration = iteration
            }
            event.begin()
            val allocationMark = CipherUrlThreadAllocationTracker.mark()
            val startedAt = System.nanoTime()
            val observation = benchmarkCase.run()
            val elapsedNs = System.nanoTime() - startedAt
            val allocatedBytes = CipherUrlThreadAllocationTracker.allocatedSince(allocationMark)
            event.end()
            event.matches = observation.matchCount
            event.allocatedBytes = allocatedBytes ?: -1L
            event.commit()

            consume(observation)
            lastObservation = observation
            samplesNs += elapsedNs
            allocatedBytes?.let(samplesAllocatedBytes::add)
        }

        val observation = requireNotNull(lastObservation)
        return CipherUrlBenchmarkResult(
            name = benchmarkCase.name,
            matchType = benchmarkCase.matchType,
            operationCount = benchmarkCase.operationCount,
            scenarioCount = benchmarkCase.scenarioCount,
            matchCount = observation.matchCount,
            samplesNs = samplesNs,
            samplesAllocatedBytes = samplesAllocatedBytes,
        ).also { result ->
            output(result.format())
        }
    }

    private fun consume(observation: CipherUrlBenchmarkObservation) {
        blackhole = blackhole xor observation.checksum xor observation.matchCount.toLong()
    }

    private fun writeCsvReport(results: List<CipherUrlBenchmarkResult>) {
        val outputPath = System.getProperty("keyguard.cipher-url-check.benchmark.output")
            ?: return
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine(
                    "benchmark,match_type,operations,scenarios,matches,median_ns,p90_ns," +
                            "median_allocated_bytes,allocated_bytes_per_operation," +
                            "operations_per_second,samples_ns,samples_allocated_bytes",
                )
                results.forEach { result ->
                    append(result.name)
                    append(',')
                    append(result.matchType)
                    append(',')
                    append(result.operationCount)
                    append(',')
                    append(result.scenarioCount)
                    append(',')
                    append(result.matchCount)
                    append(',')
                    append(result.medianNs)
                    append(',')
                    append(result.p90Ns)
                    append(',')
                    append(result.medianAllocatedBytes?.toString().orEmpty())
                    append(',')
                    append(
                        result.medianAllocatedBytes
                            ?.let { String.format(Locale.US, "%.2f", it.toDouble() / result.operationCount) }
                            .orEmpty(),
                    )
                    append(',')
                    append(String.format(Locale.US, "%.2f", result.operationsPerSecond))
                    append(',')
                    append(result.samplesNs.joinToString(separator = "|"))
                    append(',')
                    append(result.samplesAllocatedBytes.joinToString(separator = "|"))
                    appendLine()
                }
            },
        )
        output("[cipher-url-check-benchmark] report=${file.absolutePath}")
    }

    private fun CipherUrlBenchmarkResult.format(): String = String.format(
        Locale.US,
        "[cipher-url-check-benchmark] case=%s mode=%s operations=%d scenarios=%d matches=%d " +
                "warmup=%d measured=%d median=%.3f ms p90=%.3f ms allocated=%s throughput=%.1f ops/s",
        name,
        matchType,
        operationCount,
        scenarioCount,
        matchCount,
        warmupIterations,
        measurementIterations,
        medianNs / 1_000_000.0,
        p90Ns / 1_000_000.0,
        medianAllocatedBytes?.let { allocatedBytes ->
            String.format(
                Locale.US,
                "%.3f MiB (%.1f B/op)",
                allocatedBytes / (1024.0 * 1024.0),
                allocatedBytes.toDouble() / operationCount,
            )
        } ?: "unavailable",
        operationsPerSecond,
    )
}

private object CipherUrlThreadAllocationTracker {
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

@Name("keyguard.CipherUrlCheck")
@Label("Cipher URL check benchmark")
@Category("Keyguard")
private class CipherUrlCheckEvent : Event() {
    @Label("Benchmark")
    var benchmark: String = ""

    @Label("Match type")
    var matchType: String = ""

    @Label("Operations")
    var operations: Int = 0

    @Label("Scenarios")
    var scenarios: Int = 0

    @Label("Iteration")
    var iteration: Int = 0

    @Label("Matches")
    var matches: Int = 0

    @Label("Allocated bytes")
    var allocatedBytes: Long = -1L
}

private fun percentileCipherUrl(
    samples: List<Long>,
    percentile: Double,
): Long {
    require(samples.isNotEmpty())
    require(percentile in 0.0..1.0)
    val sorted = samples.sorted()
    val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
