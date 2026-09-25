package com.artemchep.keyguard.feature.home.vault.filter

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.home.vault.quicksearch.createSecret
import com.artemchep.keyguard.feature.home.vault.screen.FilterSection
import com.artemchep.keyguard.feature.home.vault.screen.buildFilterCipherIdSets
import com.artemchep.keyguard.feature.home.vault.screen.buildFilterItemsEnabledState
import com.artemchep.keyguard.feature.home.vault.screen.buildFilterItemsEnabledStateLegacy
import com.artemchep.keyguard.feature.home.vault.screen.collectFilterToggleFilters
import com.artemchep.keyguard.feature.home.vault.search.benchmark.BenchmarkCorpusSize
import com.artemchep.keyguard.feature.home.vault.search.benchmark.VaultSearchBenchmarkFixtures
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultFilterEnablementGoldenTest {
    private val filterContext = testCipherFilterContext()

    private val corpus = VaultSearchBenchmarkFixtures.buildCorpora()
        .getValue(BenchmarkCorpusSize.Small)
        .items

    private val baseItems = buildList {
        add(section(FilterSection.CUSTOM.id))
        add(
            FilterItem.ChipItem(
                sectionId = FilterSection.CUSTOM.id,
                filterSectionId = FilterSection.CUSTOM.id,
                filter = FilterItem.Item.Filter.Apply(
                    filters = mapOf(
                        FilterSection.TYPE.id to setOf(
                            DFilter.ByType(DSecret.Type.Login),
                        ),
                    ),
                    id = "custom-filter",
                ),
                leading = null,
                title = "Logins only",
                text = null,
                onClick = {},
                checked = false,
            ),
        )

        add(section(FilterSection.ACCOUNT.id))
        add(accountChip("account-work"))
        add(accountChip("account-personal"))
        add(accountChip("account-shared"))
        add(accountChip("account-missing"))
        add(
            chip(
                sectionId = FilterSection.ACCOUNT.id,
                filters = setOf(
                    byId("account-missing", DFilter.ById.What.ACCOUNT),
                    byId("account-work", DFilter.ById.What.ACCOUNT),
                ),
                title = "Merged accounts",
            ),
        )

        add(section(FilterSection.ORGANIZATION.id))
        add(organizationChip("org-engineering"))
        add(organizationChip("org-operations"))
        add(organizationChip(null))

        add(section(FilterSection.TYPE.id))
        DSecret.Type.entries
            .filter { it != DSecret.Type.None }
            .forEach { type ->
                add(
                    chip(
                        sectionId = FilterSection.TYPE.id,
                        filters = setOf(DFilter.ByType(type)),
                        title = "Type $type",
                    ),
                )
            }

        add(section(FilterSection.TAG.id))
        add(tagChip("ops"))
        add(tagChip("travel"))
        add(tagChip(null))

        add(section(FilterSection.FOLDER.id))
        add(folderListItem(setOf("folder-work"), nodeId = "folder-work"))
        add(folderListItem(setOf("folder-personal"), nodeId = "folder-personal"))
        add(
            folderListItem(
                folderIds = setOf("folder-work", "folder-archive"),
                nodeId = "folder-merged",
            ),
        )
        add(
            FilterItem.ListItem(
                sectionId = FilterSection.FOLDER.id,
                filterSectionId = FilterSection.FOLDER.id,
                filter = null,
                nodeId = "folder-group",
                parentNodeId = null,
                leading = null,
                title = "Group node",
                text = null,
                onClick = null,
                expandable = true,
                depth = 0,
                checked = false,
            ),
        )
        add(folderChip(setOf(null)))

        add(section(FilterSection.COLLECTION.id))
        add(collectionChip("collection-work"))
        add(collectionChip("collection-shared"))
        add(collectionChip(null))

        add(section(FilterSection.MISC.id))
        add(miscListItem(DFilter.ByOtp, "otp"))
        add(miscListItem(DFilter.ByAttachments, "attachments"))
        add(miscListItem(DFilter.ByPasskeys, "passkeys"))
        add(miscListItem(DFilter.ByReprompt(reprompt = true), "reprompt"))
        add(miscListItem(DFilter.BySync(synced = false), "sync"))
        add(miscListItem(DFilter.ByError(error = true), "error"))
        add(miscListItem(DFilter.ByIgnoredAlerts, "watchtower_alerts"))
    }

    private val checkedCombos = mapOf(
        "none" to emptySet(),
        "account" to setOf("Account account-work"),
        "misc-otp" to setOf("Misc otp"),
        "type-and-folder" to setOf("Type Login", "Folder folder-personal"),
        "custom-apply" to setOf("Logins only"),
    ).mapValues { (_, checkedTitles) ->
        baseItems
            .map { item ->
                item.withCheckedByTitle(checkedTitles)
            }
    }

    private val resultSubsets = mapOf(
        "all" to corpus,
        "empty" to emptyList(),
        "logins" to corpus.filter { it.type == DSecret.Type.Login },
        "account-work" to corpus.filter { it.accountId == "account-work" },
        "folder-personal" to corpus.filter { it.folderId == "folder-personal" },
        "attachments" to corpus.filter { it.attachments.isNotEmpty() },
        "sparse" to corpus.filterIndexed { index, _ -> index % 7 == 0 },
    )

    @Test
    fun idSetEnablementMatchesLegacyEnablement() = runTest {
        val filterCipherIdSets = buildFilterCipherIdSets(
            filterContext = filterContext,
            filters = collectFilterToggleFilters(baseItems),
            ciphers = corpus,
        )

        var enabledCount = 0
        var disabledCount = 0
        checkedCombos.forEach { (comboName, items) ->
            resultSubsets.forEach { (subsetName, subset) ->
                val expected = buildFilterItemsEnabledStateLegacy(
                    filterContext = filterContext,
                    items = items,
                    outputCiphers = subset,
                )
                val actual = buildFilterItemsEnabledState(
                    filterContext = filterContext,
                    items = items,
                    outputCiphers = subset,
                    filterCipherIdSets = filterCipherIdSets,
                )
                val context = "combo=$comboName subset=$subsetName"
                assertEquals(expected.map(::digest), actual.map(::digest), context)
                assertEquals(expected, actual, context)

                actual.forEach { item ->
                    when (enabledOf(item)) {
                        true -> enabledCount++
                        false -> disabledCount++
                        null -> Unit
                    }
                }
            }
        }
        assertTrue(enabledCount > 0, "matrix never enabled a chip")
        assertTrue(disabledCount > 0, "matrix never disabled a chip")
    }

    @Test
    fun missingIdSetsFallBackToLegacyScan() = runTest {
        resultSubsets.forEach { (subsetName, subset) ->
            val expected = buildFilterItemsEnabledStateLegacy(
                filterContext = filterContext,
                items = baseItems,
                outputCiphers = subset,
            )
            val actual = buildFilterItemsEnabledState(
                filterContext = filterContext,
                items = baseItems,
                outputCiphers = subset,
                filterCipherIdSets = emptyMap(),
            )
            assertEquals(expected, actual, "subset=$subsetName")
        }
    }

    @Test
    fun listSensitiveFilterKeepsPerResultSemantics() = runTest {
        val a = createSecret(
            id = "dup-a",
            login = DSecret.Login(password = "shared-password"),
        )
        val b = createSecret(
            id = "dup-b",
            login = DSecret.Login(password = "shared-password"),
        )
        val c = createSecret(
            id = "unique-c",
            login = DSecret.Login(password = "unique-password"),
        )
        val universe = listOf(a, b, c)

        val items = listOf(
            section(FilterSection.MISC.id),
            chip(
                sectionId = FilterSection.MISC.id,
                filters = setOf(DFilter.ByPasswordDuplicates),
                title = "Reused passwords",
                filterSectionId = "${FilterSection.MISC.id}.pwd_duplicates",
            ),
        )
        val filterCipherIdSets = buildFilterCipherIdSets(
            filterContext = filterContext,
            filters = collectFilterToggleFilters(items),
            ciphers = universe,
        )
        assertFalse(DFilter.ByPasswordDuplicates in filterCipherIdSets)

        mapOf(
            "one-of-two-duplicates" to listOf(a, c),
            "both-duplicates" to listOf(a, b),
            "all" to universe,
            "empty" to emptyList(),
        ).forEach { (subsetName, subset) ->
            val expected = buildFilterItemsEnabledStateLegacy(
                filterContext = filterContext,
                items = items,
                outputCiphers = subset,
            )
            val actual = buildFilterItemsEnabledState(
                filterContext = filterContext,
                items = items,
                outputCiphers = subset,
                filterCipherIdSets = filterCipherIdSets,
            )
            assertEquals(expected, actual, "subset=$subsetName")
        }

        val enabledOnPartialSubset = buildFilterItemsEnabledState(
            filterContext = filterContext,
            items = items,
            outputCiphers = listOf(a, c),
            filterCipherIdSets = filterCipherIdSets,
        )
            .filterIsInstance<FilterItem.ChipItem>()
            .single()
            .enabled
        assertFalse(
            enabledOnPartialSubset,
            "duplicates chip must stay disabled when the result list " +
                    "holds only one of the duplicates",
        )
    }

    private fun digest(item: FilterItem) = Triple(
        item.id,
        enabledOf(item),
        onClickOf(item),
    )

    private fun enabledOf(item: FilterItem): Boolean? = when (item) {
        is FilterItem.ChipItem -> item.enabled
        is FilterItem.ListItem -> item.enabled
        is FilterItem.Section -> null
    }

    private fun onClickOf(item: FilterItem): Boolean? = when (item) {
        is FilterItem.ChipItem -> item.onClick != null
        is FilterItem.ListItem -> item.onClick != null
        is FilterItem.Section -> null
    }

    private fun FilterItem.withCheckedByTitle(
        checkedTitles: Set<String>,
    ): FilterItem = when (this) {
        is FilterItem.ChipItem -> copy(checked = title in checkedTitles)
        is FilterItem.ListItem -> copy(checked = title in checkedTitles)
        is FilterItem.Section -> this
    }

    private fun section(
        sectionId: String,
    ) = FilterItem.Section(
        sectionId = sectionId,
        text = "Section $sectionId",
        onClick = null,
    )

    private fun chip(
        sectionId: String,
        filters: Set<DFilter.Primitive>,
        title: String,
        filterSectionId: String = sectionId,
    ) = FilterItem.ChipItem(
        sectionId = sectionId,
        filterSectionId = filterSectionId,
        filter = FilterItem.Item.Filter.Toggle(
            filters = filters,
        ),
        leading = null,
        title = title,
        text = null,
        onClick = {},
        checked = false,
    )

    private fun byId(
        id: String?,
        what: DFilter.ById.What,
    ) = DFilter.ById(
        id = id,
        what = what,
    )

    private fun accountChip(
        accountId: String?,
    ) = chip(
        sectionId = FilterSection.ACCOUNT.id,
        filters = setOf(byId(accountId, DFilter.ById.What.ACCOUNT)),
        title = "Account $accountId",
    )

    private fun organizationChip(
        organizationId: String?,
    ) = chip(
        sectionId = FilterSection.ORGANIZATION.id,
        filters = setOf(byId(organizationId, DFilter.ById.What.ORGANIZATION)),
        title = "Organization $organizationId",
    )

    private fun tagChip(
        tag: String?,
    ) = chip(
        sectionId = FilterSection.TAG.id,
        filters = setOf(byId(tag, DFilter.ById.What.TAG)),
        title = "Tag $tag",
    )

    private fun collectionChip(
        collectionId: String?,
    ) = chip(
        sectionId = FilterSection.COLLECTION.id,
        filters = setOf(byId(collectionId, DFilter.ById.What.COLLECTION)),
        title = "Collection $collectionId",
    )

    private fun folderChip(
        folderIds: Set<String?>,
    ) = chip(
        sectionId = FilterSection.FOLDER.id,
        filters = folderIds
            .asSequence()
            .map { byId(it, DFilter.ById.What.FOLDER) }
            .toSet(),
        title = "Folder ${folderIds.joinToString()}",
    )

    private fun folderListItem(
        folderIds: Set<String>,
        nodeId: String,
    ) = FilterItem.ListItem(
        sectionId = FilterSection.FOLDER.id,
        filterSectionId = FilterSection.FOLDER.id,
        filter = FilterItem.Item.Filter.Toggle(
            filters = folderIds
                .asSequence()
                .map { byId(it, DFilter.ById.What.FOLDER) }
                .toSet(),
        ),
        nodeId = nodeId,
        parentNodeId = null,
        leading = null,
        title = "Folder $nodeId",
        text = null,
        onClick = {},
        expandable = false,
        depth = 0,
        checked = false,
    )

    private fun miscListItem(
        filter: DFilter.Primitive,
        suffix: String,
    ) = FilterItem.ListItem(
        sectionId = FilterSection.MISC.id,
        filterSectionId = "${FilterSection.MISC.id}.$suffix",
        filter = FilterItem.Item.Filter.Toggle(
            filters = setOf(filter),
        ),
        nodeId = "",
        parentNodeId = null,
        leading = null,
        title = "Misc $suffix",
        text = null,
        onClick = {},
        expandable = false,
        depth = 0,
        checked = false,
    )
}
