package com.artemchep.keyguard.feature.home.vault.search.benchmark

import java.util.Locale
import kotlin.math.ceil

internal data class BenchmarkRun(
    val caseName: String,
    val corpusLabel: String,
    val itemCount: Int,
    val metadataSummary: String,
    val samplesNs: List<Long>,
) {
    val medianNs: Double = median(samplesNs)
    val p90Ns: Long = percentile(samplesNs, 0.90)
}

internal class VaultSearchBenchmarkHarness(
    private val warmupIterations: Int = 3,
    private val measurementIterations: Int = 5,
    private val output: (String) -> Unit = ::println,
) {
    private var blackhole: Any? = null

    suspend fun run(
        caseName: String,
        corpus: BenchmarkCorpus,
        block: suspend () -> Any?,
    ): BenchmarkRun = run(
        caseName = caseName,
        corpus = corpus,
        prepare = { Unit },
        block = { block() },
    )

    suspend fun <P> run(
        caseName: String,
        corpus: BenchmarkCorpus,
        prepare: suspend () -> P,
        block: suspend (P) -> Any?,
    ): BenchmarkRun {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            repeat(warmupIterations) {
                val prepared = prepare()
                blackhole = block(prepared)
            }

            val samples = mutableListOf<Long>()
            repeat(measurementIterations) {
                val prepared = prepare()
                val elapsedNs = measureSuspendNanos {
                    blackhole = block(prepared)
                }
                samples += elapsedNs
            }

            val run =
                BenchmarkRun(
                    caseName = caseName,
                    corpusLabel = corpus.size.label,
                    itemCount = corpus.itemCount,
                    metadataSummary = summarizeMetadata(corpus),
                    samplesNs = samples,
                )
            output(run.format())
            return run
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private suspend inline fun measureSuspendNanos(
        crossinline block: suspend () -> Unit,
    ): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun BenchmarkRun.format(): String = buildString {
        append("[vault-search-benchmark] case=")
        append(caseName)
        append(" corpus=")
        append(corpusLabel)
        append(" items=")
        append(itemCount)
        append(' ')
        append(metadataSummary)
        append(" warmup=")
        append(warmupIterations)
        append(" measured=")
        append(measurementIterations)
        append(" median=")
        append(formatMillis(medianNs))
        append(" p90=")
        append(formatMillis(p90Ns.toDouble()))
    }

    private fun summarizeMetadata(corpus: BenchmarkCorpus): String = buildString {
        append("metadata(accounts=")
        append(corpus.metadata.accounts.size)
        append(", folders=")
        append(corpus.metadata.folders.size)
        append(", tags=")
        append(corpus.metadata.tags.size)
        append(", collections=")
        append(corpus.metadata.collections.size)
        append(", organizations=")
        append(corpus.metadata.organizations.size)
        append(')')
    }

    private fun formatMillis(nanos: Double): String =
        String.format(Locale.US, "%.3f ms", nanos / 1_000_000.0)
}

private fun median(samples: List<Long>): Double {
    require(samples.isNotEmpty())
    val sorted = samples.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    } else {
        sorted[mid].toDouble()
    }
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
