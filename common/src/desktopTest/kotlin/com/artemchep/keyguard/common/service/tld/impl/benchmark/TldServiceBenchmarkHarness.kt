package com.artemchep.keyguard.common.service.tld.impl.benchmark

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil

internal data class TldServiceBenchmarkSpec(
    val name: String,
    val operationsPerSample: Int,
    val warmupSamples: Int = 3,
    val measurementSamples: Int = 7,
) {
    init {
        require(name.isNotBlank())
        require(operationsPerSample > 0)
        require(warmupSamples >= 0)
        require(measurementSamples > 0)
    }
}

internal data class TldServiceBenchmarkSample(
    val nanosPerOperation: Double,
    val allocatedBytesPerOperation: Double?,
)

internal data class TldServiceBenchmarkRun(
    val spec: TldServiceBenchmarkSpec,
    val samples: List<TldServiceBenchmarkSample>,
) {
    val medianNanosPerOperation: Double = median(samples.map { it.nanosPerOperation })
    val p90NanosPerOperation: Double = percentile(samples.map { it.nanosPerOperation }, 0.90)
    val medianAllocatedBytesPerOperation: Double? = samples
        .mapNotNull { it.allocatedBytesPerOperation }
        .takeIf { it.size == samples.size }
        ?.let(::median)
    val p90AllocatedBytesPerOperation: Double? = samples
        .mapNotNull { it.allocatedBytesPerOperation }
        .takeIf { it.size == samples.size }
        ?.let { percentile(it, 0.90) }
}

/**
 * Repository-local benchmark for comparing TLD trie implementations on the same JVM.
 *
 * Allocated bytes are measured across the JVM threads that exist after warmup. This captures the
 * `Dispatchers.IO` work used to load the public suffix list and is a measure of allocation pressure,
 * not retained heap. Results are informational rather than a pass/fail performance gate.
 */
internal class TldServiceBenchmarkHarness(
    private val output: (String) -> Unit = ::println,
) {
    @Volatile
    private var blackhole: Long = 0L

    suspend fun measure(
        spec: TldServiceBenchmarkSpec,
        block: suspend () -> Long,
    ): TldServiceBenchmarkRun {
        repeat(spec.warmupSamples) {
            measureSample(
                operations = spec.operationsPerSample,
                allocationTracker = null,
                block = block,
            )
        }

        // Dispatchers used by the measured operation should have started during warmup. Capture
        // their IDs now so every allocation sample observes the same set of threads.
        val allocationTracker = JvmAllocationTracker.createOrNull()
        val samples = List(spec.measurementSamples) {
            measureSample(
                operations = spec.operationsPerSample,
                allocationTracker = allocationTracker,
                block = block,
            )
        }
        return TldServiceBenchmarkRun(
            spec = spec,
            samples = samples,
        ).also { run ->
            output(run.format())
        }
    }

    private suspend fun measureSample(
        operations: Int,
        allocationTracker: JvmAllocationTracker?,
        block: suspend () -> Long,
    ): TldServiceBenchmarkSample {
        val allocatedBefore = allocationTracker?.snapshot()
        val startedAt = System.nanoTime()
        var sampleBlackhole = blackhole
        repeat(operations) {
            sampleBlackhole = sampleBlackhole * 31L + block()
        }
        val elapsedNanos = System.nanoTime() - startedAt
        val allocatedAfter = allocationTracker?.snapshot()
        blackhole = sampleBlackhole

        val allocatedBytes = if (allocatedBefore != null && allocatedAfter != null) {
            allocationTracker.allocatedSince(
                before = allocatedBefore,
                after = allocatedAfter,
            )
        } else {
            null
        }
        return TldServiceBenchmarkSample(
            nanosPerOperation = elapsedNanos.toDouble() / operations,
            allocatedBytesPerOperation = allocatedBytes?.toDouble()?.div(operations),
        )
    }

    private fun TldServiceBenchmarkRun.format(): String = buildString {
        append(PREFIX)
        append(" case=")
        append(spec.name)
        append(" operations_per_sample=")
        append(spec.operationsPerSample)
        append(" warmup_samples=")
        append(spec.warmupSamples)
        append(" measurement_samples=")
        append(spec.measurementSamples)
        append(" median_ns_per_operation=")
        append(formatDecimal(medianNanosPerOperation))
        append(" p90_ns_per_operation=")
        append(formatDecimal(p90NanosPerOperation))
        append(" median_allocated_bytes_per_operation=")
        append(medianAllocatedBytesPerOperation?.let(::formatDecimal) ?: "na")
        append(" p90_allocated_bytes_per_operation=")
        append(p90AllocatedBytesPerOperation?.let(::formatDecimal) ?: "na")
    }

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.3f", value)

    private companion object {
        const val PREFIX = "[tld-service-benchmark]"
    }
}

private class JvmAllocationTracker(
    private val bean: ThreadMXBean,
    private val threadIds: LongArray,
) {
    fun snapshot(): LongArray = bean.getThreadAllocatedBytes(threadIds)

    fun allocatedSince(
        before: LongArray,
        after: LongArray,
    ): Long {
        require(before.size == after.size)
        var total = 0L
        for (index in before.indices) {
            val beforeBytes = before[index]
            val afterBytes = after[index]
            if (beforeBytes >= 0L && afterBytes >= beforeBytes) {
                total += afterBytes - beforeBytes
            }
        }
        return total
    }

    companion object {
        fun createOrNull(): JvmAllocationTracker? {
            val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
                ?: return null
            if (!bean.isThreadAllocatedMemorySupported) {
                return null
            }
            if (!bean.isThreadAllocatedMemoryEnabled) {
                bean.isThreadAllocatedMemoryEnabled = true
            }
            return JvmAllocationTracker(
                bean = bean,
                threadIds = bean.allThreadIds,
            )
        }
    }
}

private fun median(samples: List<Double>): Double {
    require(samples.isNotEmpty())
    val sorted = samples.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun percentile(
    samples: List<Double>,
    percentile: Double,
): Double {
    require(samples.isNotEmpty())
    require(percentile in 0.0..1.0)
    val sorted = samples.sorted()
    val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}
