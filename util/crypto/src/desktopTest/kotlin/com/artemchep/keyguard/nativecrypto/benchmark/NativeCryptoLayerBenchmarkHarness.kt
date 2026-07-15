package com.artemchep.keyguard.nativecrypto.benchmark

import java.util.Locale
import kotlin.math.ceil

internal data class NativeCryptoLayerBenchmarkSpec(
    val name: String,
    val layer: String,
    val payloadBytes: Int? = null,
    val operationsPerSample: Int,
    val warmupSamples: Int = 5,
    val measurementSamples: Int = 10,
) {
    init {
        require(name.isNotBlank())
        require(layer.isNotBlank())
        require(payloadBytes == null || payloadBytes >= 0)
        require(operationsPerSample > 0)
        require(warmupSamples >= 0)
        require(measurementSamples > 0)
    }
}

internal data class NativeCryptoLayerBenchmarkRun(
    val spec: NativeCryptoLayerBenchmarkSpec,
    val samplesNsPerOperation: List<Double>,
) {
    val medianNsPerOperation: Double = median(samplesNsPerOperation)
    val p90NsPerOperation: Double = percentile(samplesNsPerOperation, 0.90)
}

/**
 * Repository-local diagnostic harness for decomposing Native Crypto call overhead.
 *
 * Results are informational rather than a pass/fail performance gate. The measured block returns
 * a checksum so that the JVM cannot discard the operation or its result as unused work.
 */
internal class NativeCryptoLayerBenchmarkHarness(
    private val output: (String) -> Unit = ::println,
) {
    @Volatile
    private var blackhole: Long = 0L

    fun measure(
        spec: NativeCryptoLayerBenchmarkSpec,
        block: () -> Long,
    ): NativeCryptoLayerBenchmarkRun {
        repeat(spec.warmupSamples) {
            measureSample(spec.operationsPerSample, block)
        }

        val samples = ArrayList<Double>(spec.measurementSamples)
        repeat(spec.measurementSamples) {
            samples += measureSample(spec.operationsPerSample, block)
        }

        return NativeCryptoLayerBenchmarkRun(
            spec = spec,
            samplesNsPerOperation = samples,
        ).also { run ->
            output(run.format())
        }
    }

    private fun measureSample(
        operations: Int,
        block: () -> Long,
    ): Double {
        var sampleBlackhole = blackhole
        val start = System.nanoTime()
        repeat(operations) {
            sampleBlackhole = sampleBlackhole * 31L + block()
        }
        val elapsed = System.nanoTime() - start
        blackhole = sampleBlackhole
        return elapsed.toDouble() / operations
    }

    private fun NativeCryptoLayerBenchmarkRun.format(): String =
        buildString {
            append(PREFIX)
            append(" kind=layer")
            append(" case=")
            append(spec.name)
            append(" layer=")
            append(spec.layer)
            append(" payload_bytes=")
            append(spec.payloadBytes ?: "na")
            append(" operations_per_sample=")
            append(spec.operationsPerSample)
            append(" warmup_samples=")
            append(spec.warmupSamples)
            append(" measurement_samples=")
            append(spec.measurementSamples)
            append(" median_ns_per_operation=")
            append(formatDecimal(medianNsPerOperation))
            append(" p90_ns_per_operation=")
            append(formatDecimal(p90NsPerOperation))
            spec.payloadBytes?.let { payloadBytes ->
                append(" throughput_mib_per_second=")
                append(formatDecimal(throughputMibPerSecond(payloadBytes, medianNsPerOperation)))
            }
        }

    private fun throughputMibPerSecond(
        payloadBytes: Int,
        nanosPerOperation: Double,
    ): Double =
        if (nanosPerOperation == 0.0) {
            Double.POSITIVE_INFINITY
        } else {
            payloadBytes.toDouble() * NANOS_PER_SECOND / BYTES_PER_MEBIBYTE / nanosPerOperation
        }

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.3f", value)

    private companion object {
        const val PREFIX = "[native-crypto-layer-benchmark]"
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val BYTES_PER_MEBIBYTE = 1024.0 * 1024.0
    }
}

internal fun benchmarkChecksum(value: ByteArray): Long {
    if (value.isEmpty()) return 1L
    val middle = value[value.size / 2].toLong() and 0xffL
    return value.size.toLong() * 17L +
        (value.first().toLong() and 0xffL) +
        middle * 3L +
        (value.last().toLong() and 0xffL) * 7L
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
