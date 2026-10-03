package com.artemchep.keyguard.feature.home.vault.apple

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.AutofillActArgs
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.model.SortItem
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.model.resolveWebsiteIconUrl
import com.artemchep.keyguard.feature.home.vault.model.short
import com.artemchep.keyguard.feature.home.vault.screen.FilterSection
import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.screen.VaultListState
import com.artemchep.keyguard.feature.home.vault.screen.buildVaultItemCopyActions
import com.artemchep.keyguard.feature.home.vault.screen.richFields
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.feature.search.filter.model.FilterItemModel
import com.artemchep.keyguard.ui.FlatItemAction
import io.ktor.http.Url
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class VaultListParityTest {
    private companion object {
        val FIXTURES = VaultListFixtures.benchmarkSlice(itemCount = 30)
    }

    private val fixtures = FIXTURES

    private fun screenNameOf(case: String): String =
        "vault_list_parity_" + case.replace(Regex("[^a-zA-Z0-9]+"), "_")

    private fun pickMode(
        url: String = "https://portal.example.com",
    ) = AppMode.Pick(
        args = AutofillActArgs(
            autofillTarget = AutofillTarget(
                links = listOf(
                    LinkInfoPlatform.Web(
                        url = Url(url),
                        frontPageUrl = Url(url),
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

    //
    // Surface: queries
    //

    private class QueryCase(
        val name: String,
        val query: String,
        val settled: (VaultListState.Content.Items) -> Boolean,
    )

    @Test
    fun `queries project an identical structure content and decorations`() {
        val sharedIds = fixtures.ciphers
            .filter { "Shared" in it.name }
            .map { it.id }
            .toSet()
        val workIds = fixtures.ciphers
            .filter { it.folderId == "folder-work" }
            .map { it.id }
            .toSet()
        val cases = listOf(
            QueryCase("empty", "") { items ->
                items.cipherItems().size == fixtures.ciphers.size
            },
            QueryCase("plain-shared", "shared") { items ->
                items.cipherIds().toSet() == sharedIds
            },
            QueryCase("scored-bank", "bank") { items ->
                items.cipherItems().any { it.title.spanStyles.isNotEmpty() }
            },
            QueryCase("qualifier-folder-work", "folder:work") { items ->
                items.cipherIds().toSet() == workIds
            },
            QueryCase("multi-term-bank-portal", "bank portal") { items ->
                items.cipherItems().any { it.title.spanStyles.isNotEmpty() }
            },
            QueryCase("no-match", "wombatzzz") { items ->
                items.cipherItems().isEmpty()
            },
        )

        val harness = VaultListTestHarness(fixtures)
        for (case in cases) {
            val name = "query-${case.name}"
            harness.runDual(screenName = screenNameOf(name)) {
                awaitStructureParity("[$name] initial full list") { state ->
                    state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
                }
                if (case.query.isNotEmpty()) {
                    canonical.setQuery(case.query)
                }
                val snapshot = awaitStructureParity("[$name] settled '${case.query}'") { state ->
                    val items = state.itemsOrNull()
                            ?: return@awaitStructureParity false
                    state.query.text == case.query && case.settled(items)
                }
                assertCellParity(name, snapshot)
                if (case.query.isEmpty()) {
                    assertTrue(
                        snapshot.apple.decorations.isEmpty(),
                        "[$name] no decorations without a query",
                    )
                }
                if (case.name == "no-match") {
                    assertEquals(0, snapshot.apple.itemCount, "[$name] itemCount")
                    assertTrue(
                        snapshot.apple.entries.any { it.kind == AppleVaultEntry.KIND_NO_ITEMS },
                        "[$name] the NoItems marker is present",
                    )
                }
            }
        }
    }

    //
    // Surface: filters
    //

    private class FilterCase(
        val name: String,
        /** Chip lookups against the canonical filter list, in click order. */
        val chips: List<(FilterItem.Item) -> Boolean>,
        val settled: (VaultListState.Content.Items) -> Boolean,
        val extraAssert: (DualParitySnapshot) -> Unit = {},
    )

    @Test
    fun `filter chips narrow both pipelines identically`() {
        fun toggleWith(vararg filters: DFilter.Primitive): (FilterItem.Item) -> Boolean =
            { item ->
                val toggle = item.filter as? FilterItem.Item.Filter.Toggle
                toggle != null && filters.all { it in toggle.filters }
            }

        val loginChip = { item: FilterItem.Item ->
            val toggle = item.filter as? FilterItem.Item.Filter.Toggle
            toggle?.filters
                ?.any { it is DFilter.ByType && it.type == DSecret.Type.Login } == true
        }
        val workFolderChip = { item: FilterItem.Item ->
            val toggle = item.filter as? FilterItem.Item.Filter.Toggle
            toggle?.filters?.any {
                it is DFilter.ById &&
                        it.what == DFilter.ById.What.FOLDER &&
                        it.id == "folder-work"
            } == true
        }
        val workAccountChip = { item: FilterItem.Item ->
            val toggle = item.filter as? FilterItem.Item.Filter.Toggle
            toggle?.filters?.any {
                it is DFilter.ById &&
                        it.what == DFilter.ById.What.ACCOUNT &&
                        it.id == "account-work"
            } == true
        }

        val loginIds = fixtures.ciphers
            .filter { it.type == DSecret.Type.Login }
            .map { it.id }
            .toSet()
        val cases = listOf(
            FilterCase(
                name = "type-login",
                chips = listOf(loginChip),
                settled = { it.cipherIds().toSet() == loginIds },
            ),
            FilterCase(
                name = "account-work",
                chips = listOf(workAccountChip),
                settled = { items ->
                    val expected = fixtures.ciphers
                        .filter { it.accountId == "account-work" }
                        .map { it.id }
                        .toSet()
                    items.cipherIds().toSet() == expected
                },
            ),
            FilterCase(
                name = "type-login-and-folder-work",
                chips = listOf(loginChip, workFolderChip),
                settled = { items ->
                    val expected = fixtures.ciphers
                        .filter { it.type == DSecret.Type.Login && it.folderId == "folder-work" }
                        .map { it.id }
                        .toSet()
                    expected.isNotEmpty() && items.cipherIds().toSet() == expected
                },
            ),
            FilterCase(
                name = "misc-attachments",
                chips = listOf(toggleWith(DFilter.ByAttachments)),
                settled = { items ->
                    val expected = fixtures.ciphers
                        .filter { it.attachments.isNotEmpty() }
                        .map { it.id }
                        .toSet()
                    expected.isNotEmpty() && items.cipherIds().toSet() == expected
                },
                extraAssert = { snapshot ->
                    // The attachment-filter keeps the attachment badges; the
                    // badge parity of assertCellParity is non-vacuous here.
                    assertTrue(
                        snapshot.apple.rows.values.any { row ->
                            row.badges.any { it.kind == AppleVaultRowBadge.KIND_ATTACHMENT }
                        },
                        "[misc-attachments] some row carries an attachment badge",
                    )
                },
            ),
            FilterCase(
                name = "misc-passkeys",
                chips = listOf(toggleWith(DFilter.ByPasskeys)),
                settled = { items ->
                    val expected = fixtures.ciphers
                        .filter { !it.login?.fido2Credentials.isNullOrEmpty() }
                        .map { it.id }
                        .toSet()
                    expected.isNotEmpty() && items.cipherIds().toSet() == expected
                },
                extraAssert = { snapshot ->
                    assertTrue(
                        snapshot.apple.rows.values.any { row ->
                            row.badges.any { it.kind == AppleVaultRowBadge.KIND_PASSKEY }
                        },
                        "[misc-passkeys] some row carries a passkey badge",
                    )
                },
            ),
        )

        val harness = VaultListTestHarness(fixtures)
        for (case in cases) {
            val name = "filter-${case.name}"
            harness.runDual(screenName = screenNameOf(name)) {
                awaitStructureParity("[$name] initial full list") { state ->
                    state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
                }
                val chipIds = mutableListOf<String>()
                for (chipPredicate in case.chips) {
                    val chip = canonical.awaitState(
                        description = "[$name] an enabled target filter chip",
                    ) { state ->
                        state.filters
                            .filterIsInstance<FilterItem.Item>()
                            .any { it.enabled && chipPredicate(it) }
                    }
                        .filters
                        .filterIsInstance<FilterItem.Item>()
                        .first { it.enabled && chipPredicate(it) }
                    chipIds += chip.id
                    // Dispatch through the Apple id-addressed command surface;
                    // the shared filter sink drives the canonical pipeline.
                    apple.source.invokeFilter(chip.id)
                    canonical.awaitState(
                        description = "[$name] chip '${chip.id}' checked",
                    ) { state ->
                        state.filters
                            .filterIsInstance<FilterItem.Item>()
                            .first { it.id == chip.id }
                            .checked
                    }
                }

                val snapshot = awaitStructureParity("[$name] filtered list") { state ->
                    val items = state.itemsOrNull()
                            ?: return@awaitStructureParity false
                    case.settled(items)
                }
                assertCellParity(name, snapshot)
                case.extraAssert(snapshot)

                // (h) checked / enabled / clear parity of the filter state
                // channel against the canonical filter list.
                val canonicalState = canonical.awaitState(description = "[$name] canonical filters") { state ->
                    state.filters
                        .filterIsInstance<FilterItem.Item>()
                        .filter { it.checked }
                        .map { it.id }
                        .toSet() == chipIds.toSet()
                }
                val filterState = apple.awaitFilterState("[$name] apple checked ids") { fs ->
                    fs.checkedIds.toSet() == chipIds.toSet()
                }
                val canonicalEnabled = canonicalState.filters
                    .filterIsInstance<FilterItem.Item>()
                    .filter { it.enabled }
                    .map { it.id }
                assertEquals(
                    canonicalEnabled,
                    filterState.enabledIds,
                    "[$name] enabled filter ids",
                )
                assertEquals(
                    canonicalState.clearFilters != null,
                    filterState.canClear,
                    "[$name] canClear",
                )
                assertEquals(
                    canonicalState.saveFilters != null,
                    filterState.canSave,
                    "[$name] canSave",
                )
                assertEquals(
                    filterState.checkedIds.size,
                    filterState.activeCount,
                    "[$name] activeCount",
                )
            }
        }
    }

    //
    // Surface: sorts
    //

    private class SortCase(
        val name: String,
        /** The sort-menu id to invoke; `null` keeps the default sort. */
        val sortId: String?,
        val settled: (VaultListState) -> Boolean,
    )

    @Test
    fun `sorts order both pipelines identically and the menu matches`() {
        val cases = listOf(
            SortCase("alphabetical", sortId = null) { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            },
            SortCase("alphabetical-reversed", sortId = "title_rev") { state ->
                val items = state.itemsOrNull()
                        ?: return@SortCase false
                // The favourites block and the rest are each reversed once
                // the new comparator lands; checked flag alone can race the
                // (slower) items flow.
                val ciphers = items.cipherItems()
                ciphers.size == fixtures.ciphers.size &&
                        state.sortItems().any { it.id == "title_rev" && it.checked } &&
                        ciphers.takeWhile { it.favourite }
                            .zipWithNext()
                            .all { (a, b) -> a.title.text >= b.title.text }
            },
            SortCase("last-modified", sortId = "modify_date") { state ->
                // All fixture dates are equal: the date decorator collapses
                // to exactly one section (the alphabetical view has many).
                val items = state.itemsOrNull()
                        ?: return@SortCase false
                state.sortItems().any { it.id == "modify_date" && it.checked } &&
                        items.list.filterIsInstance<VaultItem2.Section>().size == 1
            },
            SortCase("password-strength", sortId = "password_strength") { state ->
                val items = state.itemsOrNull()
                        ?: return@SortCase false
                state.sortItems().any { it.id == "password_strength" && it.checked } &&
                        items.list.any {
                            it is VaultItem2.Section && it.id.startsWith("decorator.pw_strength")
                        }
            },
        )

        val harness = VaultListTestHarness(fixtures)
        for (case in cases) {
            val name = "sort-${case.name}"
            harness.runDual(screenName = screenNameOf(name)) {
                awaitStructureParity("[$name] initial full list") { state ->
                    state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
                }
                if (case.sortId != null) {
                    apple.source.invokeSort(case.sortId)
                }
                val snapshot = awaitStructureParity("[$name] sorted list", canonicalPredicate = case.settled)
                assertCellParity(name, snapshot)

                // (i) sort menu: ids, checked marks, section markers, titles.
                val canonicalSort = snapshot.canonical.sort
                assertTrue(canonicalSort.isNotEmpty(), "[$name] canonical sort menu present")
                val expectedChecked = canonicalSort
                    .filterIsInstance<SortItem.Item>()
                    .filter { it.checked }
                    .map { it.id }
                    .toSet()
                val menu = apple.awaitSortMenu("[$name] apple sort menu") { menu ->
                    menu.items.filter { it.checked }.map { it.id }.toSet() == expectedChecked
                }
                assertEquals(
                    canonicalSort.map { it.id },
                    menu.items.map { it.id },
                    "[$name] sort menu ids",
                )
                assertEquals(
                    canonicalSort.filterIsInstance<SortItem.Section>().map { it.id },
                    menu.items.filter { it.isSection }.map { it.id },
                    "[$name] sort menu section markers",
                )
                menu.items
                    .filter { !it.isSection }
                    .forEach { appleItem ->
                        val canonicalItem = canonicalSort
                            .filterIsInstance<SortItem.Item>()
                            .first { it.id == appleItem.id }
                        val expectedTitle = scope.translate(canonicalItem.title)
                        assertEquals(expectedTitle, appleItem.title, "[$name] title of '${appleItem.id}'")
                    }
                assertEquals(
                    snapshot.canonical.clearSort != null,
                    menu.canClear,
                    "[$name] canClear",
                )
                assertTrue(menu.visible, "[$name] menu visible without a query")
            }
        }
    }

    //
    // Surface: pick mode (preferred block + dropdown actions)
    //

    @Test
    fun `pick mode projects the preferred block identically`() {
        val sharedIds = fixtures.ciphers
            .filter { "Shared" in it.name }
            .map { it.id }
            .toSet()
        val harness = VaultListTestHarness(fixtures)

        // Cell: empty query.
        harness.runDual(
            mode = pickMode(),
            screenName = screenNameOf("pick-empty"),
        ) {
            val snapshot = awaitStructureParity("[pick-empty] preferred block") { state ->
                val items = state.itemsOrNull()
                        ?: return@awaitStructureParity false
                items.list.any { it.id.startsWith("preferred.") && it is VaultItem2.Item }
            }
            assertCellParity("pick-empty", snapshot)
            assertTrue(
                snapshot.apple.entries.any {
                    it.kind == AppleVaultEntry.KIND_ITEM && it.id.startsWith("preferred.")
                },
                "[pick-empty] apple has preferred entries",
            )

            // (k) Pick rows: Apple rowActions == the canonical dropdown id list,
            // for a preferred row and for a plain row.
            val preferred = snapshot.items.cipherItems()
                .first { it.id.startsWith("preferred.") }
            val plain = snapshot.items.cipherItems()
                .first { !it.id.startsWith("preferred.") }
            for (item in listOf(preferred, plain)) {
                val dropdown = item.action as? VaultItem2.Item.Action.Dropdown
                    ?: fail("[pick-empty] canonical '${item.id}' has no dropdown")
                val expectedIds = dropdown.actions
                    .filterIsInstance<FlatItemAction>()
                    .map { it.id }
                val descriptors = apple.source.rowActions(item.id)
                assertEquals(
                    expectedIds,
                    descriptors.map { it.id },
                    "[pick-empty] rowActions of '${item.id}'",
                )
                assertTrue(
                    descriptors.any { it.id == "vaultList.pick.autofill" },
                    "[pick-empty] '${item.id}' offers autofill",
                )
                assertTrue(
                    descriptors.any { it.id == "vaultList.pick.viewDetails" },
                    "[pick-empty] '${item.id}' offers view-details",
                )
            }
        }

        // Cell: plain query in pick mode.
        harness.runDual(
            mode = pickMode(),
            screenName = screenNameOf("pick-query-shared"),
        ) {
            canonical.setQuery("shared")
            val snapshot = awaitStructureParity("[pick-query-shared] queried") { state ->
                val items = state.itemsOrNull()
                        ?: return@awaitStructureParity false
                state.query.text == "shared" &&
                        items.cipherItems()
                            .filter { !it.id.startsWith("preferred.") }
                            .map { it.id }
                            .toSet() == sharedIds
            }
            assertCellParity("pick-query-shared", snapshot)
        }

        // Cell: type filter in pick mode.
        harness.runDual(
            mode = pickMode(),
            screenName = screenNameOf("pick-filter-login"),
        ) {
            awaitStructureParity("[pick-filter-login] initial") { state ->
                state.itemsOrNull() != null
            }
            val chip = canonical.awaitState(description = "[pick-filter-login] login chip") { state ->
                state.filters.filterIsInstance<FilterItem.Item>().any { item ->
                    val toggle = item.filter as? FilterItem.Item.Filter.Toggle
                    item.enabled && toggle?.filters
                        ?.any { it is DFilter.ByType && it.type == DSecret.Type.Login } == true
                }
            }
                .filters
                .filterIsInstance<FilterItem.Item>()
                .first { item ->
                    val toggle = item.filter as? FilterItem.Item.Filter.Toggle
                    item.enabled && toggle?.filters
                        ?.any { it is DFilter.ByType && it.type == DSecret.Type.Login } == true
                }
            apple.source.invokeFilter(chip.id)
            val snapshot = awaitStructureParity("[pick-filter-login] filtered") { state ->
                val items = state.itemsOrNull()
                        ?: return@awaitStructureParity false
                val ciphers = items.cipherItems()
                ciphers.isNotEmpty() && ciphers.all { it.source.type == DSecret.Type.Login }
            }
            assertCellParity("pick-filter-login", snapshot)
        }

        // Cell: a target nothing matches -> the NoSuggestions marker.
        harness.runDual(
            mode = pickMode(url = "https://zzz.qqq"),
            screenName = screenNameOf("pick-no-suggestions"),
        ) {
            val snapshot = awaitStructureParity("[pick-no-suggestions] marker") { state ->
                state.itemsOrNull()?.list?.any { it is VaultItem2.NoSuggestions } == true
            }
            assertCellParity("pick-no-suggestions", snapshot)
            assertTrue(
                snapshot.apple.entries.any { it.kind == AppleVaultEntry.KIND_NO_SUGGESTIONS },
                "[pick-no-suggestions] apple carries the marker",
            )
        }
    }

    //
    // Surface: row actions in the Main mode
    //

    @Test
    fun `main mode row actions mirror the shared copy-action builder`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = screenNameOf("main-row-actions")) {
            val snapshot = awaitStructureParity("initial full list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val getTotpCode: GetTotpCode = koinScope.get()
            val copy = scope.copier()

            suspend fun expectedCopyActions(secret: DSecret): List<FlatItemAction> =
                secret.buildVaultItemCopyActions(
                    copy = copy,
                    getTotpCode = getTotpCode,
                    // The conceal preference is off in the fixtures; the
                    // per-item reprompt still conceals.
                    concealFields = secret.reprompt,
                )

            val ciphers = snapshot.items.cipherItems()
            val login = ciphers.first {
                it.source.type == DSecret.Type.Login && !it.source.reprompt
            }
            val card = ciphers.first {
                it.source.type == DSecret.Type.Card && !it.source.reprompt
            }
            val repromptCard = ciphers.first {
                it.source.type == DSecret.Type.Card && it.source.reprompt
            }

            // Named cells: login / card.
            for ((cell, item) in listOf(
                "main-actions-login" to login,
                "main-actions-card" to card,
            )) {
                val expected = expectedCopyActions(item.source)
                assertTrue(expected.isNotEmpty(), "[$cell] the shared builder offers actions")
                val descriptors = apple.source.rowActions(item.id)
                assertEquals(
                    expected.map { it.id },
                    descriptors.map { it.id },
                    "[$cell] rowActions ids == shared buildVaultItemCopyActions ids",
                )
                descriptors.forEach { descriptor ->
                    assertTrue(descriptor.isCopy, "[$cell] '${descriptor.id}' is a copy action")
                }
            }

            // The plain card exposes its number in the action subtitle;
            // the re-prompt card must not offer secret copy actions.
            val cardDescriptors = apple.source.rowActions(card.id)
            val cardNumber = card.source.card?.number.orEmpty()
            assertTrue(cardNumber.isNotEmpty(), "fixture card has a number")
            assertEquals(
                cardNumber,
                cardDescriptors.first { it.id == "vaultList.item.copyCardNumber" }.subtitle,
                "[main-actions-card] visible card number subtitle",
            )
            assertEquals(
                emptyList(),
                expectedCopyActions(repromptCard.source),
                "[main-actions-reprompt-card] the shared builder omits secret copy actions",
            )
            assertEquals(
                emptyList(),
                apple.source.rowActions(repromptCard.id),
                "[main-actions-reprompt-card] Apple omits secret copy actions",
            )
        }
    }

    //
    // Surface: header
    //

    @Test
    fun `header mirrors the canonical query create and keyboard state`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = screenNameOf("header")) {
            val snapshot = awaitStructureParity("initial full list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            // Cell: header-initial.
            val header = apple.awaitHeader("[header-initial] loaded header") { it.loaded }
            assertEquals(false, header.needsAccount, "[header-initial] needsAccount")
            assertEquals(false, header.refreshing, "[header-initial] refreshing")
            assertEquals(false, header.paywalled, "[header-initial] paywalled")
            assertEquals("", header.query, "[header-initial] query echo")
            assertEquals(
                snapshot.canonical.showKeyboard,
                header.showKeyboard,
                "[header-initial] showKeyboard",
            )
            assertEquals(
                snapshot.canonical.primaryActions.map { it.id },
                header.createActions.map { it.id },
                "[header-initial] create menu ids",
            )
            assertTrue(
                header.createActions.any { it.id == "vaultList.create.Login" },
                "[header-initial] create menu offers a login",
            )

            // Cell: header-query — programmatic write bumps the shared
            // revision; the query-string highlighting matches span-for-span.
            canonical.setQuery("folder:work")
            val queried = canonical.awaitState(description = "[header-query] canonical query") { state ->
                state.query.text == "folder:work" &&
                        state.queryHighlighting.spans.isNotEmpty()
            }
            val queriedHeader = apple.awaitHeader("[header-query] apple query echo") {
                it.query == "folder:work" && it.queryHighlighting.isNotEmpty()
            }
            assertTrue(queried.query.textRevision > 0, "[header-query] revision bumped")
            assertEquals(
                queried.query.textRevision,
                queriedHeader.queryRevision,
                "[header-query] queryRevision",
            )
            assertEquals(
                queried.queryHighlighting.spans
                    .flatMap { listOf(it.start, it.end, it.role.ordinal) },
                queriedHeader.queryHighlighting,
                "[header-query] highlighting triplets",
            )

            // Cell: header-suggestion — a partial qualifier surfaces the same
            // autocomplete on both sides, and Apple pre-computes the applied query.
            canonical.setQuery("fol")
            val suggesting = canonical.awaitState(description = "[header-suggestion] canonical suggestion") {
                it.query.text == "fol" && it.queryQualifierSuggestion != null
            }
            val suggestingHeader = apple.awaitHeader("[header-suggestion] apple suggestion") {
                it.query == "fol" && it.qualifierSuggestion.isNotEmpty()
            }
            assertEquals(
                suggesting.queryQualifierSuggestion.orEmpty(),
                suggestingHeader.qualifierSuggestion,
                "[header-suggestion] suggestion label",
            )
            val expectedApplied = assertNotNull(suggesting.onQueryQualifierSuggestion)
                .invoke(suggesting.query.text)
            assertEquals(
                expectedApplied?.text.orEmpty(),
                suggestingHeader.qualifierSuggestionQuery,
                "[header-suggestion] applied query text",
            )
        }

        // Cell: header-needs-account.
        val emptyHarness = VaultListTestHarness(fixtures)
        emptyHarness.flows.accounts.value = emptyList()
        emptyHarness.flows.profiles.value = emptyList()
        emptyHarness.runDual(screenName = screenNameOf("header-needs-account")) {
            val header = apple.awaitHeader("[header-needs-account] placeholder") { it.needsAccount }
            assertEquals(false, header.loaded, "[header-needs-account] loaded")
            assertEquals("", header.query, "[header-needs-account] query")
            assertTrue(
                header.createActions.isEmpty(),
                "[header-needs-account] no create actions",
            )
            canonical.awaitState(description = "[header-needs-account] canonical AddAccount") {
                it.content is VaultListState.Content.AddAccount
            }
        }

        // Cell: header-paywalled — GetCanWrite=false swaps the create menu
        // for the subscriptions link on both sides.
        val paywalledHarness = VaultListTestHarness(fixtures)
        paywalledHarness.flows.canWrite.value = false
        paywalledHarness.runDual(screenName = screenNameOf("header-paywalled")) {
            val canonicalState = canonical.awaitState(description = "[header-paywalled] canonical swap") { state ->
                state.primaryActions.map { it.id } == listOf("vaultList.subscriptions")
            }
            val header = apple.awaitHeader("[header-paywalled] apple swap") { it.paywalled }
            assertEquals(
                canonicalState.primaryActions.map { it.id },
                header.createActions.map { it.id },
                "[header-paywalled] swapped create menu",
            )
        }
    }

    //
    // Surface: filter catalog + collapse persistence
    //

    @Test
    fun `filter catalog carries the full tree and shares collapse persistence`() {
        val harness = VaultListTestHarness(fixtures)
        val screenName = screenNameOf("filter-catalog")
        val filterDiskKey = "${VaultListPersistence.DISK_FILTER}@$screenName"
        harness.runDual(screenName = screenName) {
            awaitStructureParity("initial full list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val canonicalState = canonical.awaitState(description = "canonical filter list") {
                it.filters.isNotEmpty()
            }
            val catalog = apple.awaitFilterCatalog("apple filter catalog") { it.groups.isNotEmpty() }

            // Nothing is collapsed yet: the FULL apple catalog must equal the
            // canonical filter list group-for-group and chip-for-chip.
            assertEquals(
                expectedCatalogOf(canonicalState.filters),
                catalog.toComparableGroups(),
                "[catalog-initial] catalog groups",
            )

            // Collapse the type section through the Apple command surface.
            apple.source.toggleFilterSection(FilterSection.TYPE.id)

            // The collapsed set is persisted under the SHARED disk key.
            val sectionsSink = scope.mutablePersistedFlow<List<String>>(
                VaultListPersistence.KEY_SECTIONS,
            ) { emptyList() }
            awaitPersistedStable(
                diskKey = filterDiskKey,
                description = "the collapsed 'type' section id",
                rewrite = {
                    sectionsSink.value = emptyList()
                    sectionsSink.value = listOf(FilterSection.TYPE.id)
                },
            ) { entry ->
                val sections = entry[VaultListPersistence.KEY_SECTIONS] as? List<*>
                sections?.contains(FilterSection.TYPE.id) == true
            }

            // Canonical strips the collapsed chips; Apple keeps them, flagged.
            val collapsedState = canonical.awaitState(description = "canonical collapsed type section") { state ->
                state.filters.any {
                    it is FilterItem.Section && it.sectionId == FilterSection.TYPE.id && !it.expanded
                } && state.filters.none {
                    it is FilterItem.Item && it.sectionId == FilterSection.TYPE.id
                }
            }
            val collapsedCatalog = apple.awaitFilterCatalog("apple collapsed type group") { catalog ->
                catalog.groups.any { it.sectionId == FilterSection.TYPE.id && it.collapsed }
            }
            val typeGroup = collapsedCatalog.groups
                .first { it.sectionId == FilterSection.TYPE.id }
            assertTrue(
                typeGroup.items.isNotEmpty(),
                "[catalog-collapsed] the FULL catalog keeps the collapsed chips",
            )
            // Dropping the chips of collapsed groups reproduces exactly the
            // canonical visible chip sequence.
            val appleVisible = collapsedCatalog.groups
                .flatMap { group -> if (group.collapsed) emptyList() else group.items }
                .map { it.id }
            val canonicalVisible = collapsedState.filters
                .filterIsInstance<FilterItem.Item>()
                .map { it.id }
            assertEquals(
                canonicalVisible,
                appleVisible,
                "[catalog-collapsed] canonical-visible chips == apple minus collapsed groups",
            )
        }

        // A FRESH canonical session over the same persistence still sees the
        // collapsed section: the "ciphers.sections" set is shared state.
        harness.run(screenName = screenName) {
            awaitState(description = "restored collapsed type section") { state ->
                state.filters.any {
                    it is FilterItem.Section && it.sectionId == FilterSection.TYPE.id && !it.expanded
                }
            }
        }
    }

    //
    // Surface: sort menu while querying (pinned divergence)
    //

    @Test
    fun `sort menu ships its items while querying where canonical empties`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = screenNameOf("sort-menu-query")) {
            awaitStructureParity("initial full list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val menuBefore = apple.awaitSortMenu("visible menu before the query") { it.visible }
            assertTrue(menuBefore.items.isNotEmpty(), "menu has items before the query")

            canonical.setQuery("bank")
            // Canonical: the sort list EMPTIES while a query is active.
            canonical.awaitState(description = "canonical sort emptied") { state ->
                state.query.text == "bank" && state.sort.isEmpty()
            }
            // Apple (documented divergence): the items stay, visible=false —
            // hiding is a client-side rule.
            val menu = apple.awaitSortMenu("apple menu hidden but populated") { !it.visible }
            assertEquals(
                menuBefore.items.map { it.id },
                menu.items.map { it.id },
                "the hidden menu keeps the same items",
            )
        }
    }

    //
    // Surface: toolbar
    //

    @Test
    fun `toolbar actions sections and toggle roles match`() {
        val harness = VaultListTestHarness(fixtures)
        val screenName = screenNameOf("toolbar")
        harness.runDual(screenName = screenName) {
            awaitStructureParity("initial full list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val canonicalState = canonical.awaitState(description = "canonical toolbar") {
                it.actions.isNotEmpty()
            }
            val toolbar = apple.awaitToolbar("apple toolbar") { it.actions.isNotEmpty() }

            // (j) id sequence.
            val canonicalIds = canonicalState.actions
                .filterIsInstance<FlatItemAction>()
                .map { it.id }
            assertEquals(canonicalIds, toolbar.actions.map { it.id }, "toolbar ids")
            for (expected in listOf(
                "vaultList.archive",
                "vaultList.trash",
                "vaultList.downloads",
                "vaultList.sync",
                "vaultList.lock",
            )) {
                assertTrue(expected in canonicalIds, "canonical toolbar offers '$expected'")
            }

            // Section markers, recomputed independently from the canonical
            // ContextItem walk.
            val expectedSections = buildList {
                var pending = false
                var emitted = 0
                canonicalState.actions.forEach { item ->
                    when (item) {
                        is FlatItemAction -> {
                            add(pending && emitted > 0)
                            pending = false
                            emitted += 1
                        }

                        else -> pending = true
                    }
                }
            }
            assertEquals(
                expectedSections,
                toolbar.actions.map { it.startsSection },
                "toolbar section markers",
            )

            // Toggle roles ride the id suffix.
            toolbar.actions.forEach { action ->
                val expectedRole = when {
                    action.id.startsWith("vault.action.") && action.id.endsWith(".true") ->
                        AppleVaultActionDescriptor.ROLE_TOGGLE_ON

                    action.id.startsWith("vault.action.") && action.id.endsWith(".false") ->
                        AppleVaultActionDescriptor.ROLE_TOGGLE_OFF

                    else -> AppleVaultActionDescriptor.ROLE_NORMAL
                }
                assertEquals(expectedRole, action.role, "role of '${action.id}'")
            }
            assertEquals(false, toolbar.syncing, "not syncing")

            // Flip the remember-sorting toggle through the Apple command
            // surface; both pipelines re-emit the .true action and the flag
            // reaches the shared disk entry.
            val rememberOff = toolbar.actions
                .first { it.id.startsWith("vault.action.remember_sorting.") }
            assertEquals(AppleVaultActionDescriptor.ROLE_TOGGLE_OFF, rememberOff.role, "toggle initially off")
            apple.source.invokeToolbarAction(rememberOff.id)
            val toggled = apple.awaitToolbar("toggled remember-sorting") { bar ->
                bar.actions.any { it.id == "vault.action.remember_sorting.true" }
            }
            assertEquals(
                AppleVaultActionDescriptor.ROLE_TOGGLE_ON,
                toggled.actions.first { it.id.startsWith("vault.action.remember_sorting.") }.role,
                "toggled role",
            )
            canonical.awaitState(description = "canonical toggled remember-sorting") { state ->
                state.actions
                    .filterIsInstance<FlatItemAction>()
                    .any { it.id == "vault.action.remember_sorting.true" }
            }
            val rememberSink = scope.mutablePersistedFlow(
                VaultListPersistence.KEY_SORT_PERSISTENT_ENABLED,
            ) { false }
            awaitPersistedStable(
                diskKey = "${VaultListPersistence.DISK}@$screenName",
                description = "remember-sorting enabled on disk",
                rewrite = {
                    rememberSink.value = false
                    rememberSink.value = true
                },
            ) { entry ->
                entry[VaultListPersistence.KEY_SORT_PERSISTENT_ENABLED] == true
            }
        }
    }

    //
    // Surface: selection
    //

    @Test
    fun `selection is shared between the pipelines and actions match`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = screenNameOf("selection")) {
            val snapshot = awaitStructureParity("initial full list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val createActions = apple.awaitHeader("initial create actions") {
                it.createActions.isNotEmpty() && !it.paywalled
            }.createActions.map { it.id }
            val targets = snapshot.items.cipherIds().take(2)
            targets.forEach { apple.source.toggleSelection(it) }

            // (l) both pipelines observe the same selection through the
            // shared "selection" sink.
            val appleSelection = apple.awaitSelection("apple selection of 2") { it.count == 2 }
            val canonicalSelection = canonical.awaitState(description = "canonical selection of 2") { state ->
                state.itemsOrNull()?.selection?.count == 2
            }.itemsOrNull()!!.selection!!
            assertEquals(
                canonicalSelection.actions
                    .filterIsInstance<FlatItemAction>()
                    .mapNotNull { it.id },
                appleSelection.actions.map { it.id },
                "selection action ids",
            )

            val selectedHeader = apple.awaitHeader("selection preserves create actions") {
                !it.paywalled && it.createActions.map { action -> action.id } == createActions
            }
            assertEquals(createActions, selectedHeader.createActions.map { it.id })

            // Entitlement still gates creation, including while rows are selected.
            harness.flows.canWrite.value = false
            apple.awaitHeader("selected non-premium create paywall") { it.paywalled }
            harness.flows.canWrite.value = true
            apple.awaitHeader("selected premium create actions restored") {
                !it.paywalled && it.createActions.map { action -> action.id } == createActions
            }

            apple.source.clearSelection()
            apple.awaitSelection("apple selection cleared") { it.count == 0 }
            canonical.awaitState(description = "canonical selection cleared") { state ->
                state.itemsOrNull()?.selection == null
            }
        }
    }

    //
    // Surface: scroll anchoring
    //

    @Test
    fun `scroll reports are revision-guarded and interoperate with canonical`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = screenNameOf("scroll")) {
            val snapshot = awaitStructureParity("initial full list") { state ->
                state.itemsOrNull()?.cipherItems()?.size == fixtures.ciphers.size
            }
            val anchors = snapshot.apple.entries.filter { it.kind == AppleVaultEntry.KIND_ITEM }
            val anchor = anchors[3].id

            // (m) a report against the CURRENT structure revision persists…
            reportScrollWithCurrentRevision(anchorId = anchor, offset = 42)
            val persisted = apple.scrollState()
            assertEquals(anchor, persisted.id, "persisted anchor id")
            assertEquals(42, persisted.offset, "persisted offset")
            // …and the persisted revision equals the canonical Revision id —
            // the guard the canonical restore path checks, which proves the
            // two pipelines interoperate on the same scroll memory.
            assertEquals(
                snapshot.items.revision.id,
                persisted.revision,
                "persisted ScrollPositionState.revision == canonical Items.Revision.id",
            )
            // The canonical live getters resolve the same anchor.
            val expectedIndex = snapshot.items.list.indexOfFirst { it.id == anchor }
            assertTrue(expectedIndex > 0, "the anchor is a real row")
            assertEquals(
                expectedIndex,
                snapshot.items.revision.firstVisibleItemIndex.value,
                "canonical firstVisibleItemIndex",
            )
            assertEquals(
                42,
                snapshot.items.revision.firstVisibleItemScrollOffset.value,
                "canonical firstVisibleItemScrollOffset",
            )

            // A STALE structure revision is rejected: the persisted state
            // must not move.
            val stale = snapshot.apple.entries.first { it.kind == AppleVaultEntry.KIND_ITEM }.id
            apple.source.reportScroll(
                anchorId = stale,
                offset = 99,
                structureRevision = apple.stateFlow.value!!.revision + 999,
            )
            val after = apple.scrollState()
            assertEquals(anchor, after.id, "stale report did not move the anchor")
            assertEquals(42, after.offset, "stale report did not move the offset")
        }
    }

    //
    // Surface: live fixture mutations (per-row rev perf contract)
    //

    @Test
    fun `live mutations converge and keep untouched row revs stable`() {
        val harness = VaultListTestHarness(fixtures)
        harness.runDual(screenName = screenNameOf("dynamic")) {
            canonical.setQuery("shared")
            val sharedIds = fixtures.ciphers
                .filter { "Shared" in it.name }
                .map { it.id }
            val before = awaitStructureParity("queried shared list") { state ->
                state.query.text == "shared" &&
                        state.itemsOrNull()?.cipherIds()?.toSet() == sharedIds.toSet()
            }

            val editedId = sharedIds[sharedIds.size / 2]
            val removedId = sharedIds.last()
            val addedBase = fixtures.ciphers.first { it.id == sharedIds.first() }
            val added = addedBase.copy(
                id = "secret-added",
                name = "Shared Portal Added",
            )
            canonical.flows.ciphers.value = canonical.flows.ciphers.value
                .mapNotNull { cipher ->
                    when (cipher.id) {
                        editedId -> cipher.copy(name = cipher.name + " Renamed")
                        removedId -> null
                        else -> cipher
                    }
                } + added

            val after = awaitStructureParity("mutated shared list") { state ->
                val items = state.itemsOrNull()
                        ?: return@awaitStructureParity false
                val ids = items.cipherIds()
                "secret-added" in ids && removedId !in ids &&
                        items.cipherItems()
                            .firstOrNull { it.id == editedId }
                            ?.title?.text?.endsWith("Renamed") == true
            }
            assertCellParity("dynamic-mutation", after)

            val beforeRows = before.apple.rows
            val afterRows = after.apple.rows
            val editedBefore = assertNotNull(beforeRows[editedId], "edited row before")
            val editedAfter = assertNotNull(afterRows[editedId], "edited row after")
            assertTrue(
                editedBefore.rev != editedAfter.rev,
                "the edited row's rev changed (${editedBefore.rev} -> ${editedAfter.rev})",
            )
            val untouched = afterRows.values.firstOrNull { row ->
                row.kind == AppleVaultEntry.KIND_ITEM &&
                        row.id != editedId &&
                        row.id != "secret-added" &&
                        beforeRows[row.id]?.shapeState == row.shapeState
            }
            val witness = assertNotNull(
                untouched,
                "a shape-stable untouched row exists " +
                        "(before=${beforeRows.keys}, after=${afterRows.keys})",
            )
            assertEquals(
                beforeRows.getValue(witness.id).rev,
                witness.rev,
                "the untouched row '${witness.id}' kept its rev",
            )
            // Sanity: the removed row's content is gone from the state map.
            assertNull(afterRows[removedId], "the removed row left the content map")
        }
    }

    private suspend fun DualVaultListHandle.awaitPersistedStable(
        diskKey: String,
        description: String,
        rewrite: () -> Unit,
        predicate: (Map<String, Any?>) -> Boolean,
    ) {
        repeat(20) {
            val ok = runCatching {
                canonical.awaitPersisted(diskKey, timeout = 2.seconds, predicate = predicate)
            }.isSuccess
            if (ok) {
                // Out-wait the (180ms-debounced) one-shot clobber window and
                // re-check that the value survived.
                delay(450)
                val entry = canonical.persistence.snapshot()[diskKey].orEmpty()
                if (predicate(entry)) {
                    return
                }
            }
            rewrite()
        }
        throw AssertionError(
            "Persisted state at '$diskKey' never stabilized: $description; " +
                    "last entry: ${canonical.persistence.snapshot()[diskKey]}",
        )
    }


    private suspend fun DualVaultListHandle.assertCellParity(
        case: String,
        snapshot: DualParitySnapshot,
    ) {
        val items = snapshot.items
        val appleState = snapshot.apple

        // (a) ordered ids + kinds (the converge condition, re-asserted so a
        // direct call on a stale snapshot still reports clearly).
        assertEquals(
            items.toExpectedAppleEntries(),
            appleState.entryKeys(),
            "[$case] entries",
        )
        // (b) itemCount counts the queried MAIN list only.
        assertEquals(items.count, appleState.itemCount, "[$case] itemCount")
        // The rows map covers every entry.
        appleState.entries.forEach { entry ->
            assertTrue(entry.id in appleState.rows, "[$case] missing row content for '${entry.id}'")
        }

        items.list.forEach { element ->
            when (element) {
                is VaultItem2.Item -> assertItemRowParity(case, element, appleState)
                is VaultItem2.Section -> {
                    val row = appleState.rows.getValue("section." + element.id)
                    val expectedTitle = element.text
                        ?.let { scope.translate(it) }
                        .orEmpty()
                    assertEquals(
                        expectedTitle,
                        row.title,
                        "[$case] title of section '${element.id}'",
                    )
                }

                else -> Unit
            }
        }

        assertDecorationParity(case, items, appleState)
    }

    private fun assertItemRowParity(
        case: String,
        item: VaultItem2.Item,
        appleState: AppleVaultListState,
    ) {
        val ctx = "[$case] row '${item.id}'"
        val row = appleState.rows[item.id]
            ?: fail("$ctx has no Apple row content")
        val rich = item.richFields()

        // (c) plain title + subtitle.
        assertEquals(item.title.text, row.title, "$ctx title")
        assertEquals(item.text.orEmpty(), row.subtitle, "$ctx subtitle")
        assertEquals(item.source.id, row.secretId, "$ctx secretId")
        assertEquals(item.accountId, row.accountId, "$ctx accountId")
        assertEquals(item.shapeState, row.shapeState, "$ctx shapeState")

        // (e) flags.
        assertFlag(ctx, "favourite", item.favourite, row.flags, AppleVaultRowContent.FLAG_FAVOURITE)
        assertFlag(ctx, "reprompt", rich.reprompt, row.flags, AppleVaultRowContent.FLAG_REPROMPT)
        assertFlag(ctx, "attachments", rich.hasAttachments, row.flags, AppleVaultRowContent.FLAG_ATTACHMENTS)
        assertFlag(ctx, "error", rich.hasError, row.flags, AppleVaultRowContent.FLAG_ERROR)
        assertFlag(ctx, "hasTotp", rich.hasTotp, row.flags, AppleVaultRowContent.FLAG_HAS_TOTP)
        assertFlag(ctx, "multiline", rich.isMultiline, row.flags, AppleVaultRowContent.FLAG_MULTILINE)
        assertFlag(
            ctx,
            "chevron",
            item.action is VaultItem2.Item.Action.Go,
            row.flags,
            AppleVaultRowContent.FLAG_CHEVRON,
        )

        // Icon + org chrome.
        assertEquals(
            item.icon.resolveWebsiteIconUrl().orEmpty(),
            row.iconUrl,
            "$ctx iconUrl",
        )
        assertEquals(
            VaultItemIcon.TextIcon.short(item.title.text).text,
            row.iconInitials,
            "$ctx iconInitials",
        )
        assertEquals(rich.organizationName.orEmpty(), row.orgName, "$ctx orgName")
        assertEquals(rich.accentArgbLight ?: 0, row.accentLightArgb, "$ctx accentLightArgb")
        assertEquals(rich.accentArgbDark ?: 0, row.accentDarkArgb, "$ctx accentDarkArgb")
        assertEquals(
            rich.organizationAccentArgbLight ?: 0,
            row.orgAccentLightArgb,
            "$ctx orgAccentLightArgb",
        )
        assertEquals(
            rich.organizationAccentArgbDark ?: 0,
            row.orgAccentDarkArgb,
            "$ctx orgAccentDarkArgb",
        )

        // (d) badges — kind + stable id + texts + tap availability, in
        // render order, POST badge-trim (the canonical item is the trimmed
        // one, so its badge lists are the oracle).
        val passwordSources = item.passwords.filter { it.source.password != null }
        val expectedBadges = buildList {
            rich.passwordBadges.forEachIndexed { index, badge ->
                val tap = if (passwordSources.getOrNull(index)?.onClick != null) {
                    AppleVaultRowBadge.TAP_LARGE_TYPE
                } else {
                    AppleVaultRowBadge.TAP_NONE
                }
                add(listOf(AppleVaultRowBadge.KIND_PASSWORD, "password.$index", badge.title, badge.text.orEmpty(), tap))
            }
            rich.passkeyBadges.forEachIndexed { index, badge ->
                val credentialId = item.passkeys.getOrNull(index)?.source?.credentialId.orEmpty()
                add(
                    listOf(
                        AppleVaultRowBadge.KIND_PASSKEY,
                        "passkey.$credentialId",
                        badge.title,
                        badge.text.orEmpty(),
                        AppleVaultRowBadge.TAP_OPEN_PASSKEY,
                    ),
                )
            }
            rich.attachmentBadges.forEachIndexed { index, badge ->
                val attachmentId = item.attachments2.getOrNull(index)?.source?.id.orEmpty()
                add(
                    listOf(
                        AppleVaultRowBadge.KIND_ATTACHMENT,
                        "attachment.$attachmentId",
                        badge.title,
                        badge.text.orEmpty(),
                        AppleVaultRowBadge.TAP_NONE,
                    ),
                )
            }
        }
        assertEquals(
            expectedBadges,
            row.badges.map { listOf(it.kind, it.id, it.text, it.text2, it.tapKind) },
            "$ctx badges",
        )
    }

    private fun assertFlag(
        ctx: String,
        name: String,
        expected: Boolean,
        flags: Int,
        mask: Int,
    ) {
        assertEquals(expected, flags and mask != 0, "$ctx flag '$name'")
    }

    private fun assertDecorationParity(
        case: String,
        items: VaultListState.Content.Items,
        appleState: AppleVaultListState,
    ) {
        val expected = mutableMapOf<String, Pair<List<Pair<Int, Int>>, VaultItem2.Item.SearchContextBadge?>>()
        items.list
            .filterIsInstance<VaultItem2.Item>()
            .forEach { item ->
                val ranges = item.title.spanStyles
                    .filter { it.item.background == VaultListTestHarness.HIGHLIGHT_BACKGROUND }
                    .map { it.start to it.end }
                    .sortedWith(compareBy({ it.first }, { it.second }))
                val badge = item.searchContextBadge
                if (ranges.isNotEmpty() || badge != null) {
                    expected[item.id] = ranges to badge
                }
            }
        assertEquals(
            expected.keys,
            appleState.decorations.keys,
            "[$case] decorated row ids",
        )
        expected.forEach { (id, exp) ->
            val (ranges, badge) = exp
            val decoration = appleState.decorations.getValue(id)
            val actualRanges = decoration.titleRanges
                .chunked(2)
                .map { it[0] to it[1] }
                .sortedWith(compareBy({ it.first }, { it.second }))
            assertEquals(ranges, actualRanges, "[$case] title ranges of '$id'")
            assertEquals(
                badge?.text.orEmpty(),
                decoration.contextBadgeText,
                "[$case] context badge text of '$id'",
            )
            assertEquals(
                badge?.field?.let(::appleVaultTextFieldSymbol).orEmpty(),
                decoration.contextBadgeSymbol,
                "[$case] context badge symbol of '$id'",
            )
        }
    }

    //
    // Filter catalog comparison model
    //

    private data class ComparableChip(
        val id: String,
        val sectionId: String,
        val title: String,
        val text: String,
        val depth: Int,
        val nodeId: String,
        val parentNodeId: String,
        val expandable: Boolean,
        val isApply: Boolean,
    )

    private data class ComparableGroup(
        val sectionId: String,
        val title: String,
        val collapsed: Boolean,
        val treeLayout: Boolean,
        val chips: List<ComparableChip>,
    )

    /** Rebuilds the group structure from the flat canonical filter list. */
    private fun expectedCatalogOf(items: List<FilterItem>): List<ComparableGroup> {
        val groups = mutableListOf<ComparableGroup>()
        var section: FilterItem.Section? = null
        var chips = mutableListOf<ComparableChip>()

        fun flush() {
            val currentSection = section
            if (currentSection == null && chips.isEmpty()) {
                return
            }
            groups += ComparableGroup(
                sectionId = currentSection?.sectionId
                    ?: chips.first().sectionId,
                title = currentSection?.text.orEmpty(),
                collapsed = currentSection?.expanded == false,
                treeLayout = currentSection?.layout == FilterItemModel.Section.Layout.List,
                chips = chips,
            )
        }

        items.forEach { item ->
            when (item) {
                is FilterItem.Section -> {
                    flush()
                    section = item
                    chips = mutableListOf()
                }

                is FilterItem.ChipItem -> chips += ComparableChip(
                    id = item.id,
                    sectionId = item.sectionId,
                    title = item.title,
                    text = item.text.orEmpty(),
                    depth = 0,
                    nodeId = "",
                    parentNodeId = "",
                    expandable = false,
                    isApply = item.filter is FilterItem.Item.Filter.Apply,
                )

                is FilterItem.ListItem -> chips += ComparableChip(
                    id = item.id,
                    sectionId = item.sectionId,
                    title = item.title,
                    text = item.text.orEmpty(),
                    depth = item.depth,
                    nodeId = item.nodeId,
                    parentNodeId = item.parentNodeId.orEmpty(),
                    expandable = item.expandable,
                    isApply = item.filter is FilterItem.Item.Filter.Apply,
                )
            }
        }
        flush()
        return groups
    }

    private fun AppleVaultFilterCatalog.toComparableGroups(): List<ComparableGroup> =
        groups.map { group ->
            ComparableGroup(
                sectionId = group.sectionId,
                title = group.title,
                collapsed = group.collapsed,
                treeLayout = group.treeLayout,
                chips = group.items.map { chip ->
                    ComparableChip(
                        id = chip.id,
                        sectionId = chip.sectionId,
                        title = chip.title,
                        text = chip.text,
                        depth = chip.depth,
                        nodeId = chip.nodeId,
                        parentNodeId = chip.parentNodeId,
                        expandable = chip.expandable,
                        isApply = chip.isApply,
                    )
                },
            )
        }
}
