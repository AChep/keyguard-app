package com.artemchep.keyguard.crypto.benchmark

import java.util.Locale
import kotlin.math.ceil

internal data class BitwardenCryptoBenchmarkSpec(
    val name: String,
    val payloadBytes: Int? = null,
    val operationsPerSample: Int,
    val warmupSamples: Int = 5,
    val measurementSamples: Int = 10,
) {
    init {
        require(name.isNotBlank())
        require(payloadBytes == null || payloadBytes >= 0)
        require(operationsPerSample > 0)
        require(warmupSamples >= 0)
        require(measurementSamples > 0)
    }
}

internal data class BitwardenCryptoBenchmarkRun(
    val implementation: String,
    val samplesNsPerOperation: List<Double>,
) {
    val medianNsPerOperation: Double = median(samplesNsPerOperation)
    val p90NsPerOperation: Double = percentile(samplesNsPerOperation, 0.90)
}

internal data class BitwardenCryptoBenchmarkComparison(
    val spec: BitwardenCryptoBenchmarkSpec,
    val bouncyCastle: BitwardenCryptoBenchmarkRun,
    val nativeCrypto: BitwardenCryptoBenchmarkRun,
) {
    /** Greater than one means Native Crypto was faster than Bouncy Castle. */
    val nativeSpeedup: Double =
        bouncyCastle.medianNsPerOperation / nativeCrypto.medianNsPerOperation

    /** Negative means Native Crypto took less time than Bouncy Castle. */
    val nativeDeltaPercent: Double =
        (nativeCrypto.medianNsPerOperation / bouncyCastle.medianNsPerOperation - 1.0) * 100.0
}

/**
 * Small, repository-local comparison harness.
 *
 * This is intentionally not a pass/fail performance gate. Correctness is checked by the caller;
 * timing results are evidence for fixed-host review and remain sensitive to host load and thermals.
 */
internal class BitwardenCryptoBenchmarkHarness(
    private val output: (String) -> Unit = ::println,
) {
    @Volatile
    private var blackhole: Long = 0L

    fun compare(
        spec: BitwardenCryptoBenchmarkSpec,
        bouncyCastle: () -> ByteArray,
        nativeCrypto: () -> ByteArray,
    ): BitwardenCryptoBenchmarkComparison {
        repeat(spec.warmupSamples) { sampleIndex ->
            if (sampleIndex % 2 == 0) {
                measureSample(spec.operationsPerSample, bouncyCastle)
                measureSample(spec.operationsPerSample, nativeCrypto)
            } else {
                measureSample(spec.operationsPerSample, nativeCrypto)
                measureSample(spec.operationsPerSample, bouncyCastle)
            }
        }

        val bouncyCastleSamples = ArrayList<Double>(spec.measurementSamples)
        val nativeCryptoSamples = ArrayList<Double>(spec.measurementSamples)
        repeat(spec.measurementSamples) { sampleIndex ->
            if (sampleIndex % 2 == 0) {
                bouncyCastleSamples += measureSample(spec.operationsPerSample, bouncyCastle)
                nativeCryptoSamples += measureSample(spec.operationsPerSample, nativeCrypto)
            } else {
                nativeCryptoSamples += measureSample(spec.operationsPerSample, nativeCrypto)
                bouncyCastleSamples += measureSample(spec.operationsPerSample, bouncyCastle)
            }
        }

        val comparison =
            BitwardenCryptoBenchmarkComparison(
                spec = spec,
                bouncyCastle =
                    BitwardenCryptoBenchmarkRun(
                        implementation = "bouncy_castle",
                        samplesNsPerOperation = bouncyCastleSamples,
                    ),
                nativeCrypto =
                    BitwardenCryptoBenchmarkRun(
                        implementation = "native_crypto",
                        samplesNsPerOperation = nativeCryptoSamples,
                    ),
            )
        output(comparison.bouncyCastle.format(spec))
        output(comparison.nativeCrypto.format(spec))
        output(comparison.formatSummary())
        return comparison
    }

    private fun measureSample(
        operations: Int,
        block: () -> ByteArray,
    ): Double {
        var sampleBlackhole = blackhole
        val start = System.nanoTime()
        repeat(operations) {
            val value = block()
            sampleBlackhole = consume(sampleBlackhole, value)
        }
        val elapsed = System.nanoTime() - start
        blackhole = sampleBlackhole
        return elapsed.toDouble() / operations
    }

    private fun consume(
        state: Long,
        value: ByteArray,
    ): Long {
        if (value.isEmpty()) return state * 31L + 1L
        val middle = value[value.size / 2].toLong() and 0xffL
        return state * 31L +
            value.size.toLong() * 17L +
            (value.first().toLong() and 0xffL) +
            middle * 3L +
            (value.last().toLong() and 0xffL) * 7L
    }

    private fun BitwardenCryptoBenchmarkRun.format(spec: BitwardenCryptoBenchmarkSpec): String =
        buildString {
            append(PREFIX)
            append(" kind=implementation")
            append(" case=")
            append(spec.name)
            append(" implementation=")
            append(implementation)
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

    private fun BitwardenCryptoBenchmarkComparison.formatSummary(): String =
        buildString {
            append(PREFIX)
            append(" kind=comparison")
            append(" case=")
            append(spec.name)
            append(" native_speedup_x=")
            append(formatDecimal(nativeSpeedup))
            append(" native_delta_percent=")
            append(formatDecimal(nativeDeltaPercent))
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
        const val PREFIX = "[bitwarden-crypto-benchmark]"
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val BYTES_PER_MEBIBYTE = 1024.0 * 1024.0
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
