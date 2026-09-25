package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.AutofillActArgs
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.FilterSection
import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.QueryHighlightRole
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VaultListHarnessSmokeTest {
    private val vaultListDiskKey =
        "${VaultListPersistence.DISK}@${VaultListPersistence.SCREEN_COMPOSE}"

    private val fixtures = VaultListFixtures.benchmarkSlice(itemCount = 30)

    private val fixtureIds = fixtures.ciphers
        .map { it.id }
        .toSet()

    @Test
    fun `canonical producer emits the fixture items with expected ids and titles`() {
        VaultListTestHarness(fixtures).run {
            val items = awaitItems(description = "all fixture items") {
                it.cipherItems().size == fixtures.ciphers.size
            }

            assertEquals(fixtureIds, items.cipherIds().toSet())
            assertEquals(fixtures.ciphers.size, items.count)

            // The quick filters bar leads the list on the main screen.
            assertIs<VaultItem2.QuickFilters>(items.list.first())

            // Titles are carried over from the fixture ciphers.
            val byId = items.cipherItems().associateBy { it.id }
            fixtures.ciphers.forEach { cipher ->
                val item = assertNotNull(byId[cipher.id], "missing item ${cipher.id}")
                assertEquals(cipher.name, item.title.text)
            }

            // Default sort places favourites before everything else.
            val favouriteIds = fixtures.ciphers
                .filter { it.favorite }
                .map { it.id }
                .toSet()
            assertEquals(
                favouriteIds,
                items.cipherItems().take(favouriteIds.size).map { it.id }.toSet(),
            )
        }
    }

    @Test
    fun `setting a query filters the list and highlights matches`() {
        VaultListTestHarness(fixtures).run {
            awaitItems { it.cipherItems().size == fixtures.ciphers.size }

            // "shared" exists only in the titles of the 'Shared Portal'
            // ciphers; the free-text search covers the hot fields
            // (title/url/username/...) and not the notes.
            val expectedIds = fixtures.ciphers
                .filter { "Shared" in it.name }
                .map { it.id }
                .toSet()
            assertTrue(expectedIds.size in 1 until fixtures.ciphers.size)

            setQuery("shared")
            val filtered = awaitItems(description = "items filtered by 'shared'") {
                it.cipherIds().toSet() == expectedIds
            }
            assertEquals(expectedIds, filtered.cipherIds().toSet())

            // The sort menu hides while a query is active.
            val queried = awaitState { it.query.text == "shared" }
            assertTrue(queried.sort.isEmpty())
            // A plain text clause paints no spans into the query string;
            // a qualified term does (facet role over "folder:work").
            assertTrue(queried.queryHighlighting.spans.isEmpty())
            setQuery("folder:work")
            awaitState(description = "facet spans over the qualifier query") { state ->
                state.query.text == "folder:work" &&
                        state.queryHighlighting.spans.any { span ->
                            span.role == QueryHighlightRole.FacetClause
                        }
            }

            // A title-matching query paints highlight spans into the
            // item titles using the harness highlight colors.
            setQuery("bank")
            awaitItems(description = "a 'Bank' titled item with highlight spans") { items ->
                items.cipherItems().any { item ->
                    item.title.text.startsWith("Bank") &&
                            item.title.spanStyles.any { span ->
                                span.item.background == VaultListTestHarness.HIGHLIGHT_BACKGROUND
                            }
                }
            }

            // Clearing the query restores the full list.
            setQuery("")
            awaitItems(description = "the full list restored") {
                it.cipherItems().size == fixtures.ciphers.size
            }
        }
    }

    @Test
    fun `toggling the login type filter chip narrows the list and checks the chip`() {
        val isLoginChip = { chip: FilterItem.ChipItem ->
            val toggle = chip.filter as? FilterItem.Item.Filter.Toggle
            toggle?.filters
                ?.any { it is DFilter.ByType && it.type == DSecret.Type.Login } == true
        }

        VaultListTestHarness(fixtures).run {
            awaitItems { it.cipherItems().size == fixtures.ciphers.size }

            val expectedIds = fixtures.ciphers
                .filter { it.type == DSecret.Type.Login }
                .map { it.id }
                .toSet()
            assertTrue(expectedIds.size in 1 until fixtures.ciphers.size)

            toggleFilterChip(FilterSection.TYPE.id, predicate = isLoginChip)
            val filtered = awaitState(description = "only logins with the chip checked") { state ->
                val items = state.itemsOrNull()
                        ?: return@awaitState false
                items.cipherIds().toSet() == expectedIds &&
                        state.filterChips(FilterSection.TYPE.id).first(isLoginChip).checked
            }
            assertTrue(
                filtered.itemsOrNull()!!
                    .cipherItems()
                    .all { it.source.type == DSecret.Type.Login },
            )

            // Toggling the same chip again clears the filter.
            toggleFilterChip(FilterSection.TYPE.id, predicate = isLoginChip)
            awaitState(description = "the full list with the chip unchecked") { state ->
                val items = state.itemsOrNull()
                        ?: return@awaitState false
                items.cipherItems().size == fixtures.ciphers.size &&
                        !state.filterChips(FilterSection.TYPE.id).first(isLoginChip).checked
            }
        }
    }

    @Test
    fun `selecting the reversed sort reorders the list and persists the config`() {
        VaultListTestHarness(fixtures).run {
            val before = awaitItems { it.cipherItems().size == fixtures.ciphers.size }
            val beforeIds = before.cipherIds()

            // The reversed order is fully derivable from the forward
            // order: each block (favourites first, then the rest) is
            // reversed in place.
            val favouritesCount = before.cipherItems().takeWhile { it.favourite }.count()
            val expectedIds = beforeIds.take(favouritesCount).reversed() +
                    beforeIds.drop(favouritesCount).reversed()

            selectSort("title_rev")
            awaitItems(description = "the reversed alphabetical order") {
                it.cipherIds() == expectedIds
            }
            val sorted = awaitState { state ->
                state.sortItems().any { it.id == "title_rev" && it.checked }
            }

            // The sort config is mirrored into the persisted storage.
            awaitPersisted(vaultListDiskKey) { entry ->
                val sort = entry[VaultListPersistence.KEY_SORT_PERSISTENT] as? Map<*, *>
                sort?.get("reversed").toString() == "true"
            }

            // Clearing the sort restores the default order.
            val clearSort = assertNotNull(sorted.clearSort)
            clearSort()
            awaitItems(description = "the default order restored") {
                it.cipherIds() == beforeIds
            }
        }
    }

    @Test
    fun `remember sorting round-trips the sort config across runs`() {
        val harness = VaultListTestHarness(fixtures)
        val expectedIds = harness.run {
            val before = awaitItems { it.cipherItems().size == fixtures.ciphers.size }
            val beforeIds = before.cipherIds()
            val favouritesCount = before.cipherItems().takeWhile { it.favourite }.count()
            val expectedIds = beforeIds.take(favouritesCount).reversed() +
                    beforeIds.drop(favouritesCount).reversed()

            // Turn the 'Remember sorting method' toggle on through the
            // toolbar action the state exposes.
            val toggle = awaitState(description = "the remember-sorting action") { state ->
                state.actions
                    .filterIsInstance<FlatItemAction>()
                    .any { it.id?.startsWith("vault.action.remember_sorting.") == true }
            }
                .actions
                .filterIsInstance<FlatItemAction>()
                .first { it.id?.startsWith("vault.action.remember_sorting.") == true }
            assertNotNull(toggle.onClick).invoke()
            awaitPersisted(vaultListDiskKey) { entry ->
                entry[VaultListPersistence.KEY_SORT_PERSISTENT_ENABLED] == true
            }

            selectSort("title_rev")
            awaitItems { it.cipherIds() == expectedIds }
            awaitPersisted(vaultListDiskKey) { entry ->
                val sort = entry[VaultListPersistence.KEY_SORT_PERSISTENT] as? Map<*, *>
                sort?.get("reversed").toString() == "true"
            }
            expectedIds
        }

        // A fresh producer session over the same persisted state
        // restores the remembered sort.
        harness.run {
            awaitItems(description = "the remembered reversed order") {
                it.cipherIds() == expectedIds
            }
            awaitState(description = "the restored sort selection") { state ->
                state.sortItems().any { it.id == "title_rev" && it.checked }
            }
        }
    }

    @Test
    fun `pick mode surfaces the preferred suggestions block`() {
        val mode = AppMode.Pick(
            args = AutofillActArgs(
                autofillTarget = AutofillTarget(
                    links = listOf(
                        LinkInfoPlatform.Web(
                            url = Url("https://portal.example.com"),
                            frontPageUrl = Url("https://portal.example.com"),
                        ),
                    ),
                    hints = listOf(
                        AutofillHint.USERNAME,
                        AutofillHint.PASSWORD,
                    ),
                ),
            ),
            onAutofill = { _, _ -> },
        )

        VaultListTestHarness(fixtures).run(mode = mode) {
            val items = awaitItems(description = "a preferred suggestions block") { items ->
                items.list.any { it.id.startsWith("preferred.") && it is VaultItem2.Item }
            }

            val preferredIds = items.list
                .filterIsInstance<VaultItem2.Item>()
                .filter { it.id.startsWith("preferred.") }
                .map { it.id.removePrefix("preferred.") }
            assertTrue(preferredIds.isNotEmpty())
            assertTrue(fixtureIds.containsAll(preferredIds), "unknown ids: $preferredIds")

            // The preferred block is separated from the full list by
            // the 'all items' section and never shows 'No suggestions'.
            assertTrue(items.list.any { it is VaultItem2.Section && it.id == "preferred.end" })
            assertFalse(items.list.any { it is VaultItem2.NoSuggestions })

            // Pick mode swaps the go-action for a dropdown menu.
            val preferredItem = items.list
                .filterIsInstance<VaultItem2.Item>()
                .first { it.id.startsWith("preferred.") }
            assertIs<VaultItem2.Item.Action.Dropdown>(preferredItem.action)
        }
    }

    @Test
    fun `pick mode without a matching target shows no suggestions`() {
        val mode = AppMode.Pick(
            args = AutofillActArgs(
                autofillTarget = AutofillTarget(
                    links = listOf(
                        LinkInfoPlatform.Web(
                            url = Url("https://zzz.qqq"),
                            frontPageUrl = Url("https://zzz.qqq"),
                        ),
                    ),
                    hints = listOf(
                        AutofillHint.USERNAME,
                        AutofillHint.PASSWORD,
                    ),
                ),
            ),
            onAutofill = { _, _ -> },
        )

        VaultListTestHarness(fixtures).run(mode = mode) {
            val items = awaitItems(description = "the no-suggestions placeholder") { items ->
                items.list.any { it is VaultItem2.NoSuggestions }
            }
            assertTrue(
                items.list
                    .filterIsInstance<VaultItem2.Item>()
                    .none { it.id.startsWith("preferred.") },
            )
            // The full list still renders below the placeholder.
            assertEquals(fixtureIds, items.cipherIds().toSet())
        }
    }

    @Test
    fun `translator resolves shared strings or falls back to stable keys`() {
        kotlinx.coroutines.runBlocking {
            val resolved = JvmTestTranslator.translate(
                Res.string.account,
            )
            assertTrue(resolved.isNotBlank())
            // Either the real catalog resolved ("Account") or the
            // deterministic fallback returned the resource key.
            println(
                "[vault-list-harness] translation mode: " +
                        if (resolved == "account") "fallback-keys" else "compose-resources ('$resolved')",
            )
        }
    }

    @Test
    fun `without accounts the content offers adding an account`() {
        val harness = VaultListTestHarness(fixtures)
        harness.flows.accounts.value = emptyList()
        harness.flows.profiles.value = emptyList()

        harness.run {
            val state = awaitState(description = "the add-account content") {
                it.content is VaultListState.Content.AddAccount
            }
            assertNotNull((state.content as VaultListState.Content.AddAccount).onAddAccount)
            assertTrue(state.primaryActions.isEmpty())
        }
    }
}
