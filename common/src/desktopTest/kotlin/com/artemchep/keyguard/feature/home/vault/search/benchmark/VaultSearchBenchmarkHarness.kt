package com.artemchep.keyguard.feature.home.vault.search.benchmark

import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.math.ceil

internal data class BenchmarkRun(
    val caseName: String,
    val corpusLabel: String,
    val itemCount: Int,
    val metadataSummary: String,
    val samplesNs: List<Long>,
    val samplesAllocatedBytes: List<Long>,
    val samplesGcCollections: List<Long>,
) {
    val medianNs: Double = median(samplesNs)
    val p90Ns: Long = percentile(samplesNs, 0.90)
    val medianAllocatedBytes: Double? = samplesAllocatedBytes
        .takeIf(List<Long>::isNotEmpty)
        ?.let(::median)
    val totalGcCollections: Long = samplesGcCollections.sum()
}

internal class VaultSearchBenchmarkHarness(
    private val warmupIterations: Int = benchmarkIntProperty(
        name = "keyguard.vault-search.benchmark.warmup-iterations",
        defaultValue = 3,
    ),
    private val measurementIterations: Int = benchmarkIntProperty(
        name = "keyguard.vault-search.benchmark.measurement-iterations",
        defaultValue = 5,
    ),
    private val output: (String) -> Unit = ::println,
) {
    @Volatile
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

            val samples = ArrayList<Long>(measurementIterations)
            val allocatedByteSamples = ArrayList<Long>(measurementIterations)
            val gcCollectionSamples = ArrayList<Long>(measurementIterations)
            repeat(measurementIterations) { iteration ->
                val prepared = prepare()
                val event = VaultSearchBenchmarkEvent().apply {
                    benchmark = caseName
                    this.corpus = corpus.size.label
                    itemCount = corpus.itemCount
                    this.iteration = iteration
                }
                event.begin()
                val allocationMark = VaultSearchAllocationTracker.mark()
                val gcMark = VaultSearchGcTracker.mark()
                val elapsedNs = measureSuspendNanos {
                    blackhole = block(prepared)
                }
                val allocatedBytes = VaultSearchAllocationTracker.allocatedSince(allocationMark)
                val gcCollections = VaultSearchGcTracker.collectionsSince(gcMark)
                event.end()
                event.allocatedBytes = allocatedBytes ?: -1L
                event.gcCollections = gcCollections
                event.commit()
                samples += elapsedNs
                allocatedBytes?.let(allocatedByteSamples::add)
                gcCollectionSamples += gcCollections
            }

            val run =
                BenchmarkRun(
                    caseName = caseName,
                    corpusLabel = corpus.size.label,
                    itemCount = corpus.itemCount,
                    metadataSummary = summarizeMetadata(corpus),
                    samplesNs = samples,
                    samplesAllocatedBytes = allocatedByteSamples,
                    samplesGcCollections = gcCollectionSamples,
                )
            output(run.format())
            VaultSearchCsvReport.append(run)
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
        append(" allocated=")
        append(
            medianAllocatedBytes?.let { bytes ->
                String.format(
                    Locale.US,
                    "%.3f MiB (%.1f KiB/item)",
                    bytes / (1024.0 * 1024.0),
                    bytes / itemCount / 1024.0,
                )
            } ?: "unavailable",
        )
        append(" gc=")
        append(totalGcCollections)
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

private object VaultSearchAllocationTracker {
    private val bean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported }
        ?.also { tracker ->
            if (!tracker.isThreadAllocatedMemoryEnabled) {
                tracker.isThreadAllocatedMemoryEnabled = true
            }
        }

    data class Mark(
        val bytesByThread: Map<Long, Long>,
    )

    fun mark(): Mark? {
        val tracker = bean ?: return null
        return Mark(readAllocatedBytes(tracker))
    }

    fun allocatedSince(mark: Mark?): Long? {
        val tracker = bean ?: return null
        mark ?: return null
        return readAllocatedBytes(tracker).entries.sumOf { (threadId, allocatedBytes) ->
            val previousBytes = mark.bytesByThread[threadId] ?: 0L
            (allocatedBytes - previousBytes).coerceAtLeast(0L)
        }
    }

    private fun readAllocatedBytes(
        tracker: com.sun.management.ThreadMXBean,
    ): Map<Long, Long> {
        val threadIds = tracker.allThreadIds
        val allocatedBytes = tracker.getThreadAllocatedBytes(threadIds)
        return buildMap(threadIds.size) {
            threadIds.forEachIndexed { index, threadId ->
                allocatedBytes[index]
                    .takeIf { it >= 0L }
                    ?.let { put(threadId, it) }
            }
        }
    }
}

private object VaultSearchGcTracker {
    data class Mark(
        val collections: Long,
    )

    fun mark(): Mark = Mark(collections = currentCollections())

    fun collectionsSince(mark: Mark): Long =
        (currentCollections() - mark.collections).coerceAtLeast(0L)

    private fun currentCollections(): Long = ManagementFactory
        .getGarbageCollectorMXBeans()
        .sumOf { collector -> collector.collectionCount.coerceAtLeast(0L) }
}

private object VaultSearchCsvReport {
    private val lock = Any()

    fun append(run: BenchmarkRun) {
        val outputPath = System.getProperty("keyguard.vault-search.benchmark.output")
            ?: return
        synchronized(lock) {
            val file = File(outputPath)
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                file.appendText(
                    "benchmark,corpus,items,metadata,median_ns,p90_ns,median_allocated_bytes," +
                            "allocated_bytes_per_item,gc_collections,samples_ns," +
                            "samples_allocated_bytes\n",
                )
            }
            file.appendText(
                buildString {
                    append(csvCell(run.caseName))
                    append(',')
                    append(csvCell(run.corpusLabel))
                    append(',')
                    append(run.itemCount)
                    append(',')
                    append(csvCell(run.metadataSummary))
                    append(',')
                    append(run.medianNs.toLong())
                    append(',')
                    append(run.p90Ns)
                    append(',')
                    append(run.medianAllocatedBytes?.toLong()?.toString().orEmpty())
                    append(',')
                    append(
                        run.medianAllocatedBytes
                            ?.let { bytes ->
                                String.format(Locale.US, "%.2f", bytes / run.itemCount)
                            }.orEmpty(),
                    )
                    append(',')
                    append(run.totalGcCollections)
                    append(',')
                    append(csvCell(run.samplesNs.joinToString("|")))
                    append(',')
                    append(csvCell(run.samplesAllocatedBytes.joinToString("|")))
                    appendLine()
                },
            )
            println("[vault-search-benchmark] report=${file.absolutePath}")
        }
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}

@Name("keyguard.VaultSearchBenchmark")
@Label("Vault search benchmark")
@Category("Keyguard")
private class VaultSearchBenchmarkEvent : Event() {
    @Label("Benchmark")
    var benchmark: String = ""

    @Label("Corpus")
    var corpus: String = ""

    @Label("Items")
    var itemCount: Int = 0

    @Label("Iteration")
    var iteration: Int = 0

    @Label("Allocated bytes")
    var allocatedBytes: Long = -1L

    @Label("GC collections")
    var gcCollections: Long = 0L
}

private fun benchmarkIntProperty(
    name: String,
    defaultValue: Int,
): Int = System.getProperty(name)
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: defaultValue

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
