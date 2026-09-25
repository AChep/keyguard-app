package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.screen.preferredRowId
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleVaultSelectionIdentityTest {
    private val fixtures = VaultListFixtures.benchmarkSlice(itemCount = 8)

    @Test
    fun `selection publishes cipher identities and same count replacements`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = "apple_selection_identity") {
            val snapshot = awaitStructureParity("initial list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val ids = snapshot.items.cipherItems().map { it.source.id }
            val first = ids.take(2).toSet()

            // Preferred rows address the same cipher as the ordinary row.
            apple.source.toggleSelection(preferredRowId(ids[0]))
            apple.source.toggleSelection(ids[1])
            assertEquals(first, apple.awaitSelection { it.count == 2 }.selectedIds)

            val replacement = ids.drop(2).take(2).toSet()
            val selection = scope.mutablePersistedFlow(VaultListPersistence.KEY_SELECTION) {
                emptySet<String>()
            }
            selection.value = replacement

            val replaced = apple.awaitSelection("same-count identity replacement") {
                it.count == 2 && it.selectedIds == replacement
            }
            assertEquals(replacement, replaced.selectedIds)
            apple.source.clearSelection()
            assertTrue(apple.awaitSelection { it.count == 0 }.selectedIds.isEmpty())
        }
    }

    @Test
    fun `contextual invocation rejects changed selection and accepts matching identities`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = "apple_contextual_selection_identity") {
            val snapshot = awaitStructureParity("initial list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val ids = snapshot.items.cipherItems().map { it.source.id }
            val first = ids.take(2).toSet()
            val replacement = ids.drop(2).take(2).toSet()
            val selection = scope.mutablePersistedFlow(VaultListPersistence.KEY_SELECTION) {
                emptySet<String>()
            }
            selection.value = first
            apple.awaitSelection("initial contextual selection") { it.selectedIds == first }

            // Invoke immediately after replacing the raw selection, before waiting
            // for the derived action snapshot: either old or new actions may be cached.
            selection.value = replacement
            apple.source.invokeSelectionAction("cipher.export", expectedSelectedIds = first)
            apple.awaitSelection("replacement contextual selection") { it.selectedIds == replacement }
            val unexpectedNavigation = withTimeoutOrNull(500L) {
                while (canonical.navigation.intents.isEmpty()) delay(10L)
                canonical.navigation.intents.first()
            }
            assertNull(unexpectedNavigation)

            // The recording navigation controller never opens a real export or vault.
            apple.source.invokeSelectionAction("cipher.export", expectedSelectedIds = replacement)
            withTimeout(5_000L) {
                while (canonical.navigation.intents.isEmpty()) delay(10L)
            }
            assertEquals(1, canonical.navigation.intents.size)
        }
    }
}
