package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.feature.home.vault.search.benchmark.BenchmarkCorpusSize
import com.artemchep.keyguard.feature.home.vault.search.benchmark.VaultSearchBenchmarkFixtures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppleFilterPerfReproTest {
    private fun big(itemCount: Int): VaultListFixtures {
        val corpus = VaultSearchBenchmarkFixtures.buildCorpora()
            .getValue(BenchmarkCorpusSize.Medium)
        return VaultListFixtures.of(
            ciphers = corpus.items.take(itemCount),
            metadata = corpus.metadata,
        )
    }

    @Test
    fun steadyStateTogglesDoNotBurstEmissions() {
        val fixtures = big(617)
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = "apple_perf_repro") {
            var emissions = 0
            apple.source.filterState
                .onEach { emissions += 1 }
                .launchIn(scope.screenScope)

            // Settle the catalog + initial filter state.
            val catalog = withTimeout(20.seconds) {
                apple.source.filterCatalog.first { it.groups.isNotEmpty() }
            }
            withTimeout(20.seconds) { apple.source.filterState.first { it.enabledIds.isNotEmpty() } }
            // Out-wait the universe's startup settling (an equal-content
            // re-emission of the cipher list).
            delay(300)

            val target = catalog.groups
                .first { it.sectionId == "type" }
                .items.first().id

            val perToggleEmissions = ArrayList<Int>()

            for (round in 0 until 6) {
                val wantChecked = round % 2 == 0
                val before = emissions
                val t0 = System.nanoTime()
                apple.source.invokeFilter(target)
                withTimeout(20.seconds) {
                    apple.source.filterState.first { fs -> (target in fs.checkedIds) == wantChecked }
                }
                val latencyMs = (System.nanoTime() - t0) / 1e6
                // Allow any trailing duplicate a beat to arrive so we count it.
                delay(80)
                perToggleEmissions += emissions - before
                println("[GUARD] toggle#$round latencyMs=$latencyMs emissions=${emissions - before}")
            }

            println("[GUARD] emissions per toggle=$perToggleEmissions")
            assertTrue(
                perToggleEmissions.all { it <= 3 },
                "each toggle must emit filterState at most three times; got $perToggleEmissions",
            )
        }
    }
}
