package com.artemchep.keyguard.feature.home.vault.apple

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class AppleVaultSortStateTest {
    @Test
    fun `every sort menu emission has consistent checked items and clear availability`() {
        val harness = VaultListTestHarness(VaultListFixtures.benchmarkSlice(itemCount = 8))
        harness.runDual(screenName = "apple_sort_state") {
            coroutineScope {
                // Retain every emission, including intermediate menus that a
                // StateFlow or a predicate-based wait could skip.
                val menus = Channel<AppleVaultSortMenu>(Channel.UNLIMITED)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    apple.source.sortMenu.collect(menus::send)
                }

                suspend fun awaitSort(id: String) = withTimeout(VaultListHandle.DEFAULT_TIMEOUT) {
                    while (true) {
                        val menu = menus.receive()
                        val checkedIds = menu.items.filter { it.checked }.map { it.id }.toSet()
                        val reversed = "title_rev" in checkedIds
                        assertEquals(
                            setOf("title", if (reversed) "title_rev" else "title_normal"),
                            checkedIds,
                        )
                        assertEquals(reversed, menu.canClear, "checked=$checkedIds")
                        if (id in checkedIds) break
                    }
                }

                try {
                    awaitSort("title_normal")
                    repeat(100) {
                        apple.source.invokeSort("title_rev")
                        awaitSort("title_rev")
                        apple.source.clearSort()
                        awaitSort("title_normal")
                    }
                } finally {
                    collector.cancelAndJoin()
                    menus.close()
                }
            }
        }
    }
}
