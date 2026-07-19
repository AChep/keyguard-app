package com.artemchep.keyguard.common.usecase.impl.benchmark

import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchtowerBenchmarkTest {
    @Test
    fun `benchmark and profile every Watchtower check`() = runBlocking {
        val corpusSize = benchmarkIntProperty(
            name = "keyguard.watchtower.benchmark.corpus-size",
            defaultValue = WatchtowerBenchmarkFixtures.DEFAULT_CORPUS_SIZE,
        )
        val serviceCount = benchmarkIntProperty(
            name = "keyguard.watchtower.benchmark.service-count",
            defaultValue = WatchtowerBenchmarkFixtures.DEFAULT_SERVICE_COUNT,
        )
        val fixtures = WatchtowerBenchmarkFixtures(
            corpusSize = corpusSize,
            serviceCount = serviceCount,
        )
        val cases = fixtures.cases()

        assertEquals(
            DWatchtowerAlertType.entries.toSet(),
            cases.map { DWatchtowerAlertType.valueOf(it.alertType) }.toSet(),
            "Every Watchtower alert type must have a benchmark case.",
        )
        assertEquals(
            DWatchtowerAlertType.entries.size,
            cases.size,
            "Each Watchtower alert type must have exactly one benchmark case.",
        )

        val caseFilter = System.getProperty("keyguard.watchtower.benchmark.case")
        val selectedCases = caseFilter?.let { filter ->
            val names = filter
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            cases.filter { it.name in names }
                .also { selected ->
                    val unknownNames = names - selected.map { it.name }.toSet()
                    assertTrue(
                        unknownNames.isEmpty(),
                        "Unknown Watchtower benchmark cases: ${unknownNames.joinToString()}",
                    )
                }
        } ?: cases

        val results = WatchtowerBenchmarkHarness(
            warmupIterations = benchmarkIntProperty(
                name = "keyguard.watchtower.benchmark.warmup-iterations",
                defaultValue = 3,
            ),
            measurementIterations = benchmarkIntProperty(
                name = "keyguard.watchtower.benchmark.measurement-iterations",
                defaultValue = 7,
            ),
        ).run(selectedCases)

        assertEquals(selectedCases.size, results.size)
        results.forEach { result ->
            assertTrue(result.samplesNs.all { it > 0L }, result.name)
            assertTrue(result.resultCount > 0, result.name)
            assertTrue(result.threatCount > 0, "${result.name} did not exercise its alerting path")
        }
    }
}

private fun benchmarkIntProperty(
    name: String,
    defaultValue: Int,
): Int = System.getProperty(name)
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: defaultValue
