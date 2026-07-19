package com.artemchep.keyguard.common.usecase.impl.benchmark

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CipherUrlCheckBenchmarkTest {
    @Test
    fun `benchmark and profile every CipherUrlCheck match mode`() = runBlocking {
        val fixtures = CipherUrlCheckBenchmarkFixtures(
            operationCount = cipherUrlBenchmarkIntProperty(
                name = "keyguard.cipher-url-check.benchmark.operation-count",
                defaultValue = 512,
            ),
        )
        val cases = fixtures.cases()

        assertEquals(
            setOf(
                "domain-diverse",
                "host-diverse",
                "starts-with-diverse",
                "exact-diverse",
                "regex-diverse",
                "never-diverse",
            ),
            cases.filter { it.matchType != "Mixed" }.map(CipherUrlBenchmarkCase::name).toSet(),
            "Every CipherUrlCheck match mode must have a dedicated benchmark case.",
        )

        val caseFilter = System.getProperty("keyguard.cipher-url-check.benchmark.case")
        val selectedCases = caseFilter?.let { filter ->
            val names = filter
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            cases.filter { it.name in names }
                .also { selected ->
                    val unknownNames = names - selected.map(CipherUrlBenchmarkCase::name).toSet()
                    assertTrue(
                        unknownNames.isEmpty(),
                        "Unknown CipherUrlCheck benchmark cases: ${unknownNames.joinToString()}",
                    )
                }
        } ?: cases

        val results = CipherUrlBenchmarkHarness(
            warmupIterations = cipherUrlBenchmarkIntProperty(
                name = "keyguard.cipher-url-check.benchmark.warmup-iterations",
                defaultValue = 15,
            ),
            measurementIterations = cipherUrlBenchmarkIntProperty(
                name = "keyguard.cipher-url-check.benchmark.measurement-iterations",
                defaultValue = 9,
            ),
        ).run(selectedCases)

        assertEquals(selectedCases.size, results.size)
        results.forEach { result ->
            assertTrue(result.samplesNs.all { it > 0L }, result.name)
            assertTrue(result.scenarioCount > 1, "${result.name} is not a diverse benchmark")
            assertTrue(result.matchCount in 0..result.operationCount, result.name)
        }
    }
}

private fun cipherUrlBenchmarkIntProperty(
    name: String,
    defaultValue: Int,
): Int = System.getProperty(name)
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: defaultValue
