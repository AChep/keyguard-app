package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT
import com.artemchep.keyguard.feature.home.vault.search.createItem
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.query.buildVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import com.artemchep.keyguard.ui.icons.AccentColors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultSearchIndexBuilderTest {
    private val tokenizer = DefaultSearchTokenizer()
    private val parser = DefaultVaultSearchParser()
    private val compiler = DefaultVaultSearchQueryCompiler(tokenizer)
    private val scorer = Bm25SearchScorer()
    private val executor = DefaultSearchExecutor()
    private val builder = DefaultVaultSearchIndexBuilder(
        tokenizer = tokenizer,
        scorer = scorer,
        executor = executor,
        parser = parser,
        compiler = compiler,
    )

    @Test
    fun `build index and resolve folder qualifier via metadata`() = runTest {
        val workSecret = createSecret(
            id = "work-id",
            name = "Work Account",
            folderId = "folder-work",
        )
        val homeSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            folderId = "folder-home",
        )
        val index = builder.build(
            items = listOf(workSecret, homeSecret),
            metadata = VaultSearchIndexMetadata(
                accounts = listOf(
                    DAccount(
                        id = AccountId("account-id"),
                        username = "me",
                        host = "vault.example.com",
                        webVaultUrl = null,
                        localVaultUrl = null,
                        type = AccountType.BITWARDEN,
                        faviconServer = null,
                    ),
                ),
                folders = listOf(
                    DFolder(
                        id = "folder-work",
                        accountId = "account-id",
                        revisionDate = TEST_INSTANT,
                        service = BitwardenService(),
                        deleted = false,
                        synced = true,
                        name = "Work",
                    ),
                ),
            ),
        )
        val plan = index.compile("folder:work", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(plan)
        val result = index.evaluate(
            plan = plan,
            candidates = listOf(createItem(workSecret), createItem(homeSecret)),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        )
        assertEquals(listOf("work-id"), result.map { it.id })
    }

    @Test
    fun `build index and resolve account qualifier fuzzily via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "account-match-id",
            name = "Account Match",
            accountId = "account-id",
        )
        val otherSecret = createSecret(
            id = "account-other-id",
            name = "Other Account",
            accountId = "other-account-id",
        )

        assertFacetQueryMatches(
            query = "account:vault",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                accounts = listOf(
                    DAccount(
                        id = AccountId("account-id"),
                        username = "me",
                        host = "vault.example.com",
                        webVaultUrl = null,
                        localVaultUrl = null,
                        type = AccountType.BITWARDEN,
                        faviconServer = null,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `build index and resolve localized folder qualifier via metadata`() = runTest {
        val workSecret = createSecret(
            id = "work-id",
            name = "Work Account",
            folderId = "folder-work",
        )
        val homeSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            folderId = "folder-home",
        )
        val index = builder.build(
            items = listOf(workSecret, homeSecret),
            metadata = metadata(folderName = "Work"),
        )
        val qualifierCatalog =
            buildVaultSearchQualifierCatalog(
                localizedAliasesByCanonicalName =
                    mapOf(
                        "folder" to setOf("dossier"),
                    ),
            )

        val plan = index.compile(
            query = "dossier:work",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            qualifierCatalog = qualifierCatalog,
        )
        assertNotNull(plan)
        val result = index.evaluate(
            plan = plan,
            candidates = listOf(createItem(workSecret), createItem(homeSecret)),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        )
        assertEquals(listOf("work-id"), result.map { it.id })
    }

    @Test
    fun `build index and resolve folder qualifier with separators via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "work-id",
            name = "Work Account",
            folderId = "folder-work-tools",
        )
        val otherSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            folderId = "folder-home",
        )

        assertFacetQueryMatches(
            query = "folder:work-tools",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                folders = listOf(
                    DFolder(
                        id = "folder-work-tools",
                        accountId = "account-id",
                        revisionDate = TEST_INSTANT,
                        service = BitwardenService(),
                        deleted = false,
                        synced = true,
                        name = "Work-Tools",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `build index and resolve folder qualifier fuzzily via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "folder-match-id",
            name = "Folder Match",
            folderId = "folder-work",
        )
        val otherSecret = createSecret(
            id = "folder-other-id",
            name = "Other Folder",
            folderId = "folder-home",
        )

        assertFacetQueryMatches(
            query = "folder:munchen",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                folders = listOf(
                    DFolder(
                        id = "folder-work",
                        accountId = "account-id",
                        revisionDate = TEST_INSTANT,
                        service = BitwardenService(),
                        deleted = false,
                        synced = true,
                        name = "München",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `build index and resolve tag qualifier with separators via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "ops-id",
            name = "Ops Account",
            tags = listOf("Ops-Team"),
        )
        val otherSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            tags = listOf("Personal"),
        )

        assertFacetQueryMatches(
            query = "tag:ops-team",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                tags = listOf(
                    DTag(name = "Ops-Team"),
                ),
            ),
        )
    }

    @Test
    fun `build index and resolve fuzzy tag qualifier via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "ops-id",
            name = "Ops Account",
            tags = listOf("Ops-Team"),
        )
        val otherSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            tags = listOf("Personal"),
        )
        val metadata = VaultSearchIndexMetadata(
            tags = listOf(
                DTag(name = "Ops-Team"),
            ),
        )

        assertFacetQueryMatches(
            query = "tag:ops",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = metadata,
        )
        assertFacetQueryMatches(
            query = "tag:\"ops team\"",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = metadata,
        )
        assertFacetQueryMatches(
            query = "tag:team",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = metadata,
        )
    }

    @Test
    fun `build index and reject nonmatching fuzzy tag qualifier via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "ops-id",
            name = "Ops Account",
            tags = listOf("Ops-Team"),
        )
        val otherSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            tags = listOf("Personal"),
        )
        val index = builder.build(
            items = listOf(matchingSecret, otherSecret),
            metadata = VaultSearchIndexMetadata(
                tags = listOf(
                    DTag(name = "Ops-Team"),
                ),
            ),
        )

        val plan = index.compile("tag:\"ops personal\"", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(plan)
        val result = index.evaluate(
            plan = plan,
            candidates = listOf(createItem(matchingSecret), createItem(otherSecret)),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        )

        assertEquals(emptyList(), result.map { it.id })
    }

    @Test
    fun `build index and resolve organization qualifier with separators via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "acme-id",
            name = "Acme Account",
            organizationId = "org-acme",
        )
        val otherSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            organizationId = "org-home",
        )

        assertFacetQueryMatches(
            query = "organization:acme-inc",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                organizations = listOf(
                    DOrganization(
                        id = "org-acme",
                        accountId = "account-id",
                        revisionDate = TEST_INSTANT,
                        keyBase64 = "",
                        name = "Acme, Inc.",
                        accentColor = AccentColors(
                            light = Color.Unspecified,
                            dark = Color.Unspecified,
                        ),
                        selfHost = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `build index and resolve organization qualifier fuzzily via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "organization-match-id",
            name = "Organization Match",
            organizationId = "org-acme",
        )
        val otherSecret = createSecret(
            id = "organization-other-id",
            name = "Other Organization",
            organizationId = "org-home",
        )

        assertFacetQueryMatches(
            query = "organization:uber",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                organizations = listOf(
                    DOrganization(
                        id = "org-acme",
                        accountId = "account-id",
                        revisionDate = TEST_INSTANT,
                        keyBase64 = "",
                        name = "Über Team",
                        accentColor = AccentColors(
                            light = Color.Unspecified,
                            dark = Color.Unspecified,
                        ),
                        selfHost = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `build index and resolve collection qualifier fuzzily via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "collection-match-id",
            name = "Collection Match",
            collectionIds = setOf("collection-shared-tools"),
        )
        val otherSecret = createSecret(
            id = "collection-other-id",
            name = "Other Collection",
            collectionIds = setOf("collection-home"),
        )

        assertFacetQueryMatches(
            query = "collection:sao",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                collections = listOf(
                    DCollection(
                        id = "collection-shared-tools",
                        externalId = null,
                        organizationId = null,
                        accountId = "account-id",
                        revisionDate = TEST_INSTANT,
                        name = "São Paulo",
                        readOnly = false,
                        hidePasswords = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `build index and resolve collection qualifier with separators via metadata`() = runTest {
        val matchingSecret = createSecret(
            id = "shared-id",
            name = "Shared Account",
            collectionIds = setOf("collection-shared-tools"),
        )
        val otherSecret = createSecret(
            id = "home-id",
            name = "Home Account",
            collectionIds = setOf("collection-home"),
        )

        assertFacetQueryMatches(
            query = "collection:shared-tools",
            matchingSecret = matchingSecret,
            otherSecret = otherSecret,
            metadata = VaultSearchIndexMetadata(
                collections = listOf(
                    DCollection(
                        id = "collection-shared-tools",
                        externalId = null,
                        organizationId = null,
                        accountId = "account-id",
                        revisionDate = TEST_INSTANT,
                        name = "Shared-Tools",
                        readOnly = false,
                        hidePasswords = false,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `compile returns null for blank query`() = runTest {
        val index = builder.build(items = emptyList())
        assertNull(index.compile("   ", VaultRoute.Args.SearchBy.ALL))
    }

    @Test
    fun `incremental rebuild reuses unchanged documents`() = runTest {
        val traceSink = BuilderRecordingVaultSearchTraceSink()
        val builder = createBuilder(traceSink)
        val alpha = createSecret(id = "alpha", name = "Alpha Account")
        val beta = createSecret(id = "beta", name = "Beta Account")
        val gamma = createSecret(id = "gamma", name = "Gamma Account")
        builder.build(
            items = listOf(alpha, beta, gamma),
            surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        )

        val updatedBeta = beta.copy(name = "Delta Account")
        val rebuiltIndex = builder.build(
            items = listOf(alpha, updatedBeta, gamma),
            surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        )

        val rebuildEvent = traceSink.indexEvents.last()
        assertEquals(IndexTracePhase.Finish, rebuildEvent.phase)
        assertEquals(2, rebuildEvent.reusedDocumentCount)
        assertEquals(1, rebuildEvent.rebuiltDocumentCount)
        assertEquals(0, rebuildEvent.removedDocumentCount)

        val candidates = listOf(
            createItem(alpha),
            createItem(updatedBeta),
            createItem(gamma),
        )
        val deltaPlan = rebuiltIndex.compile("delta", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(deltaPlan)
        assertEquals(
            listOf("beta"),
            rebuiltIndex.evaluate(
                plan = deltaPlan,
                candidates = candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            ).map { it.id },
        )
        val alphaPlan = rebuiltIndex.compile("alpha", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(alphaPlan)
        assertEquals(
            listOf("alpha"),
            rebuiltIndex.evaluate(
                plan = alphaPlan,
                candidates = candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            ).map { it.id },
        )
    }

    @Test
    fun `metadata only refresh does not rebuild text corpus`() = runTest {
        val traceSink = BuilderRecordingVaultSearchTraceSink()
        val builder = createBuilder(traceSink)
        val workSecret = createSecret(
            id = "work-id",
            name = "Work Account",
            folderId = "folder-work",
        )
        val oldMetadata = metadata(folderName = "Work")
        val newMetadata = metadata(folderName = "Office")
        val oldIndex = builder.build(
            items = listOf(workSecret),
            metadata = oldMetadata,
            surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        )
        val refreshedIndex = builder.build(
            items = listOf(workSecret),
            metadata = newMetadata,
            surface = VAULT_SEARCH_SURFACE_VAULT_LIST,
        )

        val rebuildEvent = traceSink.indexEvents.last()
        assertEquals(1, rebuildEvent.reusedDocumentCount)
        assertEquals(0, rebuildEvent.rebuiltDocumentCount)
        assertEquals(0, rebuildEvent.removedDocumentCount)

        val candidates = listOf(createItem(workSecret))
        val oldFolderPlan = oldIndex.compile("folder:work", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(oldFolderPlan)
        assertEquals(
            listOf("work-id"),
            oldIndex.evaluate(
                plan = oldFolderPlan,
                candidates = candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            ).map { it.id },
        )

        val newFolderPlan = refreshedIndex.compile("folder:office", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(newFolderPlan)
        assertEquals(
            listOf("work-id"),
            refreshedIndex.evaluate(
                plan = newFolderPlan,
                candidates = candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            ).map { it.id },
        )

        val titlePlan = refreshedIndex.compile("work", VaultRoute.Args.SearchBy.ALL)
        assertNotNull(titlePlan)
        assertEquals(
            listOf("work-id"),
            refreshedIndex.evaluate(
                plan = titlePlan,
                candidates = candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            ).map { it.id },
        )
        assertTrue(refreshedIndex.compile("folder:work", VaultRoute.Args.SearchBy.ALL) != null)
        assertEquals(
            emptyList(),
            refreshedIndex.evaluate(
                plan = refreshedIndex.compile("folder:work", VaultRoute.Args.SearchBy.ALL),
                candidates = candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            ).map { it.id },
        )
    }

    @Test
    fun `cold indexed values cache terms while hot indexed values do not`() = runTest {
        val secret =
            createSecret(
                id = "cache-id",
                name = "Repeated Title",
                notes = "secret secret note",
                login =
                    DSecret.Login(
                        username = "alice@example.com",
                        password = "super-secret",
                    ),
            )

        val index = builder.build(items = listOf(secret))
        val documents: Map<*, *> = index.privateField("documents")
        val document = requireNotNull(documents.values.single())

        val hotFields: Map<*, *> = document.privateField("hotFields")
        val coldFields: Map<*, *> = document.privateField("coldFields")

        val titleFieldData = requireNotNull(hotFields[VaultTextField.Title])
        val titleValue = requireNotNull(titleFieldData.privateList("values").single())
        assertNull(titleValue.privateField("normalizedTerms"))
        assertNull(titleValue.privateField("exactNormalizedTerms"))

        val noteFieldData = requireNotNull(coldFields[VaultTextField.Note])
        val noteValue = requireNotNull(noteFieldData.privateList("values").single())
        assertEquals(
            listOf("secret", "note"),
            noteValue.privateField("normalizedTerms"),
        )
        assertEquals(
            listOf("secret", "note"),
            noteValue.privateField("exactNormalizedTerms"),
        )

        val passwordFieldData = requireNotNull(coldFields[VaultTextField.Password])
        val passwordValue = requireNotNull(passwordFieldData.privateList("values").single())
        assertEquals(
            listOf("super", "secret"),
            passwordValue.privateField("normalizedTerms"),
        )
        assertEquals(
            listOf("super", "secret"),
            passwordValue.privateField("exactNormalizedTerms"),
        )
    }

    private fun createBuilder(
        traceSink: VaultSearchTraceSink,
    ): DefaultVaultSearchIndexBuilder = DefaultVaultSearchIndexBuilder(
        tokenizer = tokenizer,
        scorer = scorer,
        executor = executor,
        parser = parser,
        compiler = compiler,
        traceSink = traceSink,
    )

    private suspend fun assertFacetQueryMatches(
        query: String,
        matchingSecret: DSecret,
        otherSecret: DSecret,
        metadata: VaultSearchIndexMetadata,
    ) {
        val index = builder.build(
            items = listOf(matchingSecret, otherSecret),
            metadata = metadata,
        )
        val plan = index.compile(query, VaultRoute.Args.SearchBy.ALL)
        assertNotNull(plan)
        val result = index.evaluate(
            plan = plan,
            candidates = listOf(createItem(matchingSecret), createItem(otherSecret)),
            highlightBackgroundColor = Color.Unspecified,
            highlightContentColor = Color.Unspecified,
        )
        assertEquals(listOf(matchingSecret.id), result.map { it.id })
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> Any.privateField(name: String): T =
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(this) as T

    private fun Any.privateList(name: String): List<*> =
        privateField(name)

    private fun metadata(
        folderName: String,
        tags: List<DTag> = emptyList(),
    ) = VaultSearchIndexMetadata(
        accounts = listOf(
            DAccount(
                id = AccountId("account-id"),
                username = "me",
                host = "vault.example.com",
                webVaultUrl = null,
                localVaultUrl = null,
                type = AccountType.BITWARDEN,
                faviconServer = null,
            ),
        ),
        folders = listOf(
            DFolder(
                id = "folder-work",
                accountId = "account-id",
                revisionDate = TEST_INSTANT,
                service = BitwardenService(),
                deleted = false,
                synced = true,
                name = folderName,
            ),
        ),
        tags = tags,
    )
}

private class BuilderRecordingVaultSearchTraceSink : VaultSearchTraceSink {
    override val isEnabled: Boolean = true

    val indexEvents = mutableListOf<IndexTraceEvent>()

    override fun index(event: IndexTraceEvent) {
        indexEvents += event
    }

    override fun query(event: QueryTraceEvent) = Unit

    override fun evaluation(event: EvaluationTraceEvent) = Unit

    override fun item(event: ItemTraceEvent) = Unit

    override fun surface(event: SurfaceTraceEvent) = Unit
}
