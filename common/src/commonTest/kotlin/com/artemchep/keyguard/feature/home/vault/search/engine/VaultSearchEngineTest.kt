package com.artemchep.keyguard.feature.home.vault.search.engine

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.home.vault.component.obscureCardNumber
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT
import com.artemchep.keyguard.feature.home.vault.search.createItem
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultTextField
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import com.artemchep.keyguard.ui.icons.AccentColors
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VaultSearchEngineTest {
    private val tokenizer = DefaultSearchTokenizer()
    private val parser = DefaultVaultSearchParser()
    private val compiler = DefaultVaultSearchQueryCompiler(tokenizer)
    private val scorer = Bm25SearchScorer()
    private val executor = DefaultSearchExecutor()
    private val builder =
        DefaultVaultSearchIndexBuilder(
            tokenizer = tokenizer,
            scorer = scorer,
            executor = executor,
            parser = parser,
            compiler = compiler,
        )

    private inline fun <T> withLocale(
        locale: Locale,
        block: () -> T,
    ): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        return try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `qualifier query matches off-title and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "alpha",
                    name = "Email Login",
                    login =
                        DSecret.Login(
                            username = "alice@example.com",
                            password = "p@ss",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("username:ali", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Username,
                text = "alice@example.com",
            )
        }

    @Test
    fun `email qualifier matches and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "email-id",
                    name = "Email Login",
                    identity =
                        DSecret.Identity(
                            email = "alice@example.com",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("email:ali", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Email,
                text = "alice@example.com",
            )
        }

    @Test
    fun `bare query matches username and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "username-id",
                    name = "Email Login",
                    login =
                        DSecret.Login(
                            username = "alice@example.com",
                            password = "p@ss",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("alice", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Username,
                text = "alice@example.com",
            )
        }

    @Test
    fun `bare query highlights title`() =
        runTest {
            val secret =
                createSecret(
                    id = "id-1",
                    name = "Bank Portal",
                    login = DSecret.Login(username = "user"),
                )
            val item = createItem(secret)
            val index = builder.build(listOf(secret))

            val plan = index.compile("ban", VaultRoute.Args.SearchBy.ALL)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Blue,
                    highlightContentColor = Color.White,
                )
            assertEquals(1, out.size)
            assertNotEquals(item.title, out.single().title)
            assertEquals(null, out.single().searchContextBadge)
        }

    @Test
    fun `bare query highlights title and keeps context when non-title field wins`() =
        runTest {
            val secret =
                createSecret(
                    id = "mixed-id",
                    name = "test",
                    uris =
                        listOf(
                            DSecret.Uri(
                                uri = "https://te.testing.tester.technical.template.telescope.com",
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("te", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Blue,
                    highlightContentColor = Color.White,
                )

            assertEquals(1, out.size)
            assertEquals(1, out.single().title.spanStyles.size)
            assertEquals(0, out.single().title.spanStyles.single().start)
            assertEquals(2, out.single().title.spanStyles.single().end)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Host,
                text = "te.testing.tester.technical.template.telescope.com",
            )
        }

    @Test
    fun `repeated bare query ranks repeated title higher`() =
        runTest {
            val single =
                createItem(
                    createSecret(
                        id = "single-id",
                        name = "test xyz",
                    ),
                )
            val repeated =
                createItem(
                    createSecret(
                        id = "repeated-id",
                        name = "test test xyz",
                    ),
                )
            val index = builder.build(listOf(single.source, repeated.source))

            val plan = index.compile("test test", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(single, repeated),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(
                listOf("repeated-id", "single-id"),
                out.map { it.id },
            )
        }

    @Test
    fun `repeated bare query still matches single occurrence`() =
        runTest {
            val item =
                createItem(
                    createSecret(
                        id = "single-id",
                        name = "test xyz",
                    ),
                )
            val index = builder.build(listOf(item.source))

            val plan = index.compile("test test", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(listOf("single-id"), out.map { it.id })
        }

    @Test
    fun `filter only query keeps candidate order`() =
        runTest {
            val first = createItem(createSecret(id = "a", name = "One", tags = listOf("Ops-Team")))
            val second = createItem(createSecret(id = "b", name = "Two", tags = listOf("Ops-Team")))
            val index = builder.build(listOf(first.source, second.source))

            val plan = index.compile("tag:ops", VaultRoute.Args.SearchBy.ALL)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(second, first),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(listOf("b", "a"), out.map { it.id })
        }

    @Test
    fun `malformed qualified query compiles and returns no results`() =
        runTest {
            val secret =
                createSecret(
                    id = "malformed",
                    name = "Alpha",
                    login = DSecret.Login(username = "alice@example.com"),
                )
            val item = createItem(secret)
            val index = builder.build(listOf(secret))

            val plan = index.compile("username:", VaultRoute.Args.SearchBy.ALL)

            assertNotNull(plan)
            assertFalse(plan.hasActiveClauses)
            assertTrue(plan.diagnostics.isNotEmpty())

            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertTrue(out.isEmpty())
        }

    @Test
    fun `malformed negated qualified query compiles and returns no results`() =
        runTest {
            val secret =
                createSecret(
                    id = "malformed-negated",
                    name = "Alpha",
                    login = DSecret.Login(username = "alice@example.com"),
                )
            val item = createItem(secret)
            val index = builder.build(listOf(secret))

            val plan = index.compile("-folder:", VaultRoute.Args.SearchBy.ALL)

            assertNotNull(plan)
            assertFalse(plan.hasActiveClauses)
            assertTrue(plan.diagnostics.isNotEmpty())

            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertTrue(out.isEmpty())
        }

    @Test
    fun `localized facet metadata resolves with non-english names`() =
        runTest {
            val secret =
                createSecret(
                    id = "localized-id",
                    name = "José Account",
                    folderId = "folder-münchen",
                    collectionIds = setOf("collection-são-paulo"),
                    organizationId = "org-über-team",
                    tags = listOf("Niño-Team"),
                )
            val index =
                builder.build(
                    items = listOf(secret),
                    metadata =
                        VaultSearchIndexMetadata(
                            folders =
                                listOf(
                                    DFolder(
                                        id = "folder-münchen",
                                        accountId = "account-id",
                                        revisionDate = TEST_INSTANT,
                                        service = BitwardenService(),
                                        deleted = false,
                                        synced = true,
                                        name = "München",
                                    ),
                                ),
                            tags =
                                listOf(
                                    DTag(name = "Niño-Team"),
                                ),
                            organizations =
                                listOf(
                                    DOrganization(
                                        id = "org-über-team",
                                        accountId = "account-id",
                                        revisionDate = TEST_INSTANT,
                                        keyBase64 = "",
                                        name = "Über Team",
                                        accentColor =
                                            AccentColors(
                                                light = Color.Unspecified,
                                                dark = Color.Unspecified,
                                            ),
                                        selfHost = false,
                                    ),
                                ),
                            collections =
                                listOf(
                                    DCollection(
                                        id = "collection-são-paulo",
                                        externalId = null,
                                        organizationId = "org-über-team",
                                        accountId = "account-id",
                                        revisionDate = TEST_INSTANT,
                                        name = "São Paulo",
                                        readOnly = false,
                                        hidePasswords = false,
                                    ),
                                ),
                        ),
                )

            val candidates = listOf(createItem(secret))

            assertEquals(
                listOf("localized-id"),
                evaluateIds(
                    index = index,
                    query = "folder:munchen",
                    candidates = candidates,
                ),
            )
            assertEquals(
                listOf("localized-id"),
                evaluateIds(
                    index = index,
                    query = "tag:nino-team",
                    candidates = candidates,
                ),
            )
            assertEquals(
                listOf("localized-id"),
                evaluateIds(
                    index = index,
                    query = "organization:uber-team",
                    candidates = candidates,
                ),
            )
            assertEquals(
                listOf("localized-id"),
                evaluateIds(
                    index = index,
                    query = "collection:sao-paulo",
                    candidates = candidates,
                ),
            )
        }

    @Test
    fun `bare query matches localized title with accentless input`() =
        runTest {
            val item =
                createItem(
                    createSecret(
                        id = "localized-title-id",
                        name = "José Account",
                    ),
                )
            val index = builder.build(listOf(item.source))

            val plan = index.compile("jose", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(listOf("localized-title-id"), out.map { it.id })
            assertEquals(1, out.single().title.spanStyles.size)
            assertEquals(0, out.single().title.spanStyles.single().start)
            assertEquals("José".length, out.single().title.spanStyles.single().end)
        }

    @Test
    fun `folded sharp s query highlights the original title text`() =
        runTest {
            val item =
                createItem(
                    createSecret(
                        id = "sharp-s-id",
                        name = "Straße Portal",
                    ),
                )
            val index = builder.build(listOf(item.source))

            val plan = index.compile("strasse", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(listOf("sharp-s-id"), out.map { it.id })
            assertEquals(1, out.single().title.spanStyles.size)
            assertEquals(0, out.single().title.spanStyles.single().start)
            assertEquals("Straße".length, out.single().title.spanStyles.single().end)
        }

    @Test
    fun `bare query prefers exact-script title over folded variant`() =
        runTest {
            val folded =
                createItem(
                    createSecret(
                        id = "folded-id",
                        name = "Jose Account",
                    ),
                )
            val exact =
                createItem(
                    createSecret(
                        id = "exact-id",
                        name = "José Account",
                    ),
                )
            val index = builder.build(listOf(folded.source, exact.source))

            val plan = index.compile("josé", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(folded, exact),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(
                listOf("exact-id", "folded-id"),
                out.map { it.id },
            )
        }

    @Test
    fun `password search matches substring`() =
        runTest {
            val secret =
                createSecret(
                    id = "pw-id",
                    name = "Vault Login",
                    login =
                        DSecret.Login(
                            username = "user",
                            password = "secret-pass",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("cret", VaultRoute.Args.SearchBy.PASSWORD)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Password,
                text = HIDDEN_FIELD_MASK,
            )
        }

    @Test
    fun `note qualifier matches note content and writes note context`() =
        runTest {
            val secret =
                createSecret(
                    id = "note-id",
                    name = "Note Vault",
                    notes = "very secret note",
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("note:secret", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Note,
                text = "very secret note",
            )
        }

    @Test
    fun `card-number qualifier preserves obscured presentation`() =
        runTest {
            val secret =
                createSecret(
                    id = "card-id",
                    name = "Card Vault",
                    type = DSecret.Type.Card,
                    card =
                        DSecret.Card(
                            cardholderName = "Jane Cardholder",
                            number = "4111111111111111",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("card-number:4111", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.CardNumber,
                text = obscureCardNumber("4111111111111111"),
            )
        }

    @Test
    fun `structured fields do not transliterate localized queries`() =
        runTest {
            val secret =
                createSecret(
                    id = "structured-id",
                    name = "Jose Account",
                    login =
                        DSecret.Login(
                            username = "jose@example.com",
                            password = "secret-pass",
                        ),
                    uris =
                        listOf(
                            DSecret.Uri(uri = "https://example.com"),
                        ),
                )
            val item = createItem(secret)
            val index = builder.build(listOf(secret))

            listOf(
                "username:josé",
                "email:josé@example.com",
                "host:exämple",
                "password:sécret",
            ).forEach { query ->
                val plan = index.compile(query, VaultRoute.Args.SearchBy.ALL)
                assertNotNull(plan)
                val out =
                    index.evaluate(
                        plan = plan,
                        candidates = listOf(item),
                        highlightBackgroundColor = Color.Unspecified,
                        highlightContentColor = Color.Unspecified,
                    )
                assertEquals(emptyList(), out.map { it.id }, query)
            }
        }

    @Test
    fun `spaced Japanese query matches unspaced title`() = withLocale(Locale.JAPANESE) {
        runTest {
            val item =
                createItem(
                    createSecret(
                        id = "jp-id",
                        name = "東京都メモ",
                    ),
                )
            val index = builder.build(listOf(item.source))

            assertEquals(
                listOf("jp-id"),
                evaluateIds(
                    index = index,
                    query = "東京 メモ",
                    candidates = listOf(item),
                ),
            )
        }
    }

    @Test
    fun `spaced Thai query matches unspaced title`() = withLocale(Locale.forLanguageTag("th-TH")) {
        runTest {
            val item =
                createItem(
                    createSecret(
                        id = "th-id",
                        name = "รหัสผ่านไทย",
                    ),
                )
            val index = builder.build(listOf(item.source))

            assertEquals(
                listOf("th-id"),
                evaluateIds(
                    index = index,
                    query = "รหัส ไทย",
                    candidates = listOf(item),
                ),
            )
        }
    }

    @Test
    fun `cold fields are not tokenized during evaluation`() =
        runTest {
            val tokenizer = CountingSearchTokenizer(DefaultSearchTokenizer())
            val builder =
                DefaultVaultSearchIndexBuilder(
                    tokenizer = tokenizer,
                    scorer = scorer,
                    executor = executor,
                    parser = DefaultVaultSearchParser(),
                    compiler = DefaultVaultSearchQueryCompiler(tokenizer),
                )
            val secret =
                createSecret(
                    id = "cold-id",
                    name = "Secure Login",
                    notes = "very secret note",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "api-key",
                                value = "internal-token",
                                type = DSecret.Field.Type.Text,
                            ),
                        ),
                    login =
                        DSecret.Login(
                            username = "user",
                            password = "secret-pass",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("password:secret", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            tokenizer.reset()
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals(0, tokenizer.tokenizeCalls)
        }

    @Test
    fun `field query matches custom text field by name`() =
        runTest {
            val secret =
                createSecret(
                    id = "field-name-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "api-key",
                                value = "internal-token",
                                type = DSecret.Field.Type.Text,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("field:api", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Field,
                text = "api-key",
            )
        }

    @Test
    fun `field query matches custom text field by value`() =
        runTest {
            val secret =
                createSecret(
                    id = "field-value-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "api-key",
                                value = "internal-token",
                                type = DSecret.Field.Type.Text,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("field:token", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Field,
                text = "internal-token",
            )
        }

    @Test
    fun `field query masks hidden field value`() =
        runTest {
            val secret =
                createSecret(
                    id = "field-hidden-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "secret-key",
                                value = "super-secret",
                                type = DSecret.Field.Type.Hidden,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("field:super", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Field,
                text = HIDDEN_FIELD_MASK,
            )
        }

    @Test
    fun `field query matches boolean field by name`() =
        runTest {
            val secret =
                createSecret(
                    id = "field-boolean-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "enabled",
                                value = "true",
                                type = DSecret.Field.Type.Boolean,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("field:enabled", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Field,
                text = "enabled",
            )
        }

    @Test
    fun `field query matches boolean field by value`() =
        runTest {
            val secret =
                createSecret(
                    id = "field-boolean-value-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "enabled",
                                value = "true",
                                type = DSecret.Field.Type.Boolean,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("field:true", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Field,
                text = "true",
            )
        }

    @Test
    fun `bare query matches custom text field by name`() =
        runTest {
            val secret =
                createSecret(
                    id = "bare-field-name-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "api-key",
                                value = "internal-token",
                                type = DSecret.Field.Type.Text,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("api", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.FieldName,
                text = "api-key",
            )
        }

    @Test
    fun `bare query does not match custom text field by value`() =
        runTest {
            val secret =
                createSecret(
                    id = "bare-field-value-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "api-key",
                                value = "internal-token",
                                type = DSecret.Field.Type.Text,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("token", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(emptyList(), out)
        }

    private suspend fun evaluateIds(
        index: VaultSearchIndex,
        query: String,
        candidates: List<com.artemchep.keyguard.feature.home.vault.model.VaultItem2.Item>,
    ): List<String> {
        val plan = index.compile(query, VaultRoute.Args.SearchBy.ALL)
        assertNotNull(plan)
        val out =
            index.evaluate(
                plan = plan,
                candidates = candidates,
                highlightBackgroundColor = Color.Unspecified,
                highlightContentColor = Color.Unspecified,
            )
        return out.map { it.id }
    }

    @Test
    fun `field query does not match linked custom field`() =
        runTest {
            val secret =
                createSecret(
                    id = "field-linked-id",
                    name = "Secure Login",
                    fields =
                        listOf(
                            DSecret.Field(
                                name = "account-link",
                                linkedId = DSecret.Field.LinkedId.Login_Username,
                                type = DSecret.Field.Type.Linked,
                            ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("field:account", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(emptyList(), out)
        }

    @Test
    fun `tag query matches tag by fuzzy substring`() =
        runTest {
            val matching = createItem(
                createSecret(
                    id = "tag-match-id",
                    name = "Ops Account",
                    tags = listOf("Ops-Team"),
                ),
            )
            val other = createItem(
                createSecret(
                    id = "tag-other-id",
                    name = "Home Account",
                    tags = listOf("Personal"),
                ),
            )
            val index = builder.build(listOf(matching.source, other.source))

            assertEquals(
                listOf("tag-match-id"),
                evaluateIds(
                    index = index,
                    query = "tag:ops",
                    candidates = listOf(matching, other),
                ),
            )
        }

    @Test
    fun `quoted tag query matches fuzzy phrase`() =
        runTest {
            val matching = createItem(
                createSecret(
                    id = "quoted-tag-match-id",
                    name = "Ops Account",
                    tags = listOf("Ops-Team"),
                ),
            )
            val other = createItem(
                createSecret(
                    id = "quoted-tag-other-id",
                    name = "Home Account",
                    tags = listOf("Personal"),
                ),
            )
            val index = builder.build(listOf(matching.source, other.source))

            assertEquals(
                listOf("quoted-tag-match-id"),
                evaluateIds(
                    index = index,
                    query = "tag:\"ops team\"",
                    candidates = listOf(matching, other),
                ),
            )
        }

    @Test
    fun `tag query matches separator and accent normalized tag names`() =
        runTest {
            val matching = createItem(
                createSecret(
                    id = "accent-tag-match-id",
                    name = "Ops Account",
                    tags = listOf("Niño-Team"),
                ),
            )
            val other = createItem(
                createSecret(
                    id = "accent-tag-other-id",
                    name = "Home Account",
                    tags = listOf("Personal"),
                ),
            )
            val index = builder.build(listOf(matching.source, other.source))

            assertEquals(
                listOf("accent-tag-match-id"),
                evaluateIds(
                    index = index,
                    query = "tag:nino-team",
                    candidates = listOf(matching, other),
                ),
            )
            assertEquals(
                listOf("accent-tag-match-id"),
                evaluateIds(
                    index = index,
                    query = "tag:team",
                    candidates = listOf(matching, other),
                ),
            )
        }

    @Test
    fun `negated tag query excludes matching items`() =
        runTest {
            val matching = createItem(
                createSecret(
                    id = "negated-tag-match-id",
                    name = "Ops Account",
                    tags = listOf("Ops-Team"),
                ),
            )
            val other = createItem(
                createSecret(
                    id = "negated-tag-other-id",
                    name = "Home Account",
                    tags = listOf("Personal"),
                ),
            )
            val index = builder.build(listOf(matching.source, other.source))

            assertEquals(
                listOf("negated-tag-other-id"),
                evaluateIds(
                    index = index,
                    query = "-tag:ops",
                    candidates = listOf(matching, other),
                ),
            )
        }

    @Test
    fun `tag only query preserves candidate order`() =
        runTest {
            val second = createItem(
                createSecret(
                    id = "tag-order-second-id",
                    name = "Second Account",
                    tags = listOf("Ops-Team"),
                ),
            )
            val first = createItem(
                createSecret(
                    id = "tag-order-first-id",
                    name = "First Account",
                    tags = listOf("Ops-Team"),
                ),
            )
            val index = builder.build(listOf(second.source, first.source))

            assertEquals(
                listOf("tag-order-second-id", "tag-order-first-id"),
                evaluateIds(
                    index = index,
                    query = "tag:ops",
                    candidates = listOf(second, first),
                ),
            )
        }

    @Test
    fun `tag query with multiple facets requires all matching tags`() =
        runTest {
            val matching = createItem(
                createSecret(
                    id = "multi-tag-match-id",
                    name = "Ops Account",
                    tags = listOf("Ops-Team", "Urgent"),
                ),
            )
            val partial = createItem(
                createSecret(
                    id = "multi-tag-partial-id",
                    name = "Home Account",
                    tags = listOf("Ops-Team"),
                ),
            )
            val other = createItem(
                createSecret(
                    id = "multi-tag-other-id",
                    name = "Personal Account",
                    tags = listOf("Personal", "Urgent"),
                ),
            )
            val index = builder.build(listOf(matching.source, partial.source, other.source))

            assertEquals(
                listOf("multi-tag-match-id"),
                evaluateIds(
                    index = index,
                    query = "tag:ops tag:urgent",
                    candidates = listOf(matching, partial, other),
                ),
            )
        }

    @Test
    fun `bare query matches identity name and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "identity-id",
                    name = "Identity Vault",
                    identity =
                        DSecret.Identity(
                            firstName = "Alice",
                            middleName = "Beth",
                            lastName = "Smith",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("beth", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals(item.title, out.single().title)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.IdentityName,
                text = "Alice Beth Smith",
            )
        }

    @Test
    fun `bare query matches identity phone and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "phone-id",
                    name = "Identity Vault",
                    identity =
                        DSecret.Identity(
                            phone = "+1 (555) 123-4567",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("555", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals(item.title, out.single().title)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.Phone,
                text = "+1 (555) 123-4567",
            )
        }

    @Test
    fun `bare query matches cardholder and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "card-id",
                    name = "Corporate Card",
                    card =
                        DSecret.Card(
                            cardholderName = "Jane Cardholder",
                            brand = "Visa",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("cardholder", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals(item.title, out.single().title)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.CardholderName,
                text = "Jane Cardholder",
            )
        }

    @Test
    fun `bare query does not match card brand`() =
        runTest {
            val secret =
                createSecret(
                    id = "brand-id",
                    name = "Corporate Card",
                    card =
                        DSecret.Card(
                            cardholderName = "Jane Cardholder",
                            brand = "Visa",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("visa", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(emptyList(), out)
        }

    @Test
    fun `bare query matches attachment name with lower priority than title`() =
        runTest {
            val titleSecret =
                createSecret(
                    id = "title-id",
                    name = "Report",
                )
            val attachmentSecret =
                createSecret(
                    id = "attachment-id",
                    name = "Archive",
                    attachments =
                        listOf(
                            DSecret.Attachment.Local(
                                id = "attachment-file-id",
                                url = "file:///tmp/report.pdf",
                                fileName = "Report.pdf",
                                size = null,
                            ),
                        ),
                )
            val index = builder.build(listOf(titleSecret, attachmentSecret))

            val plan = index.compile("report", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(createItem(titleSecret), createItem(attachmentSecret)),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(
                listOf("title-id", "attachment-id"),
                out.map { it.id },
            )
            assertSearchContextBadge(
                item = out[1],
                field = VaultTextField.AttachmentName,
                text = "Report.pdf",
            )
        }

    @Test
    fun `attachment qualifier matches attachment name`() =
        runTest {
            val secret =
                createSecret(
                    id = "attachment-qualified-id",
                    name = "Archive",
                    attachments =
                        listOf(
                            DSecret.Attachment.Local(
                                id = "attachment-file-id",
                                url = "file:///tmp/company-report.pdf",
                                fileName = "Company-Report.pdf",
                                size = null,
                            ),
                        ),
                )
            val item = createItem(secret)
            val index = builder.build(listOf(secret))

            val plan = index.compile("attachment:report", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.AttachmentName,
                text = "Company-Report.pdf",
            )
        }

    @Test
    fun `passkey qualifier matches rpId and display name`() =
        runTest {
            val secret =
                createSecret(
                    id = "passkey-qualified-id",
                    name = "Passkey Vault",
                    login =
                        DSecret.Login(
                            fido2Credentials =
                                listOf(
                                    DSecret.Login.Fido2Credentials(
                                        credentialId = "cred-id",
                                        keyType = "public-key",
                                        keyAlgorithm = "ECDSA",
                                        keyCurve = "P-256",
                                        keyValue = "key-value",
                                        rpId = "example.com",
                                        rpName = "Example",
                                        counter = 1,
                                        userHandle = "user-handle",
                                        userName = "alice",
                                        userDisplayName = "Alice Device",
                                        discoverable = true,
                                        creationDate = com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT,
                                    ),
                                ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val rpIdPlan = index.compile("passkey:example", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(rpIdPlan)
            val rpIdOut =
                index.evaluate(
                    plan = rpIdPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )
            assertEquals(1, rpIdOut.size)
            assertEquals("old text", rpIdOut.single().text)
            assertSearchContextBadge(
                item = rpIdOut.single(),
                field = VaultTextField.PasskeyRpId,
                text = "example.com",
            )

            val displayNamePlan = index.compile("passkey:device", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(displayNamePlan)
            val displayNameOut =
                index.evaluate(
                    plan = displayNamePlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )
            assertEquals(1, displayNameOut.size)
            assertEquals("old text", displayNameOut.single().text)
            assertSearchContextBadge(
                item = displayNameOut.single(),
                field = VaultTextField.PasskeyDisplayName,
                text = "Alice Device",
            )
        }

    @Test
    fun `ssh qualifier matches public key private key and fingerprint`() =
        runTest {
            val secret =
                createSecret(
                    id = "ssh-qualified-id",
                    name = "SSH Vault",
                ).copy(
                    sshKey =
                        DSecret.SshKey(
                            privateKey = "private-key-token",
                            publicKey = "public-key-token",
                            fingerprint = "fingerprint-token",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val publicKeyPlan = index.compile("ssh:public", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(publicKeyPlan)
            assertEquals(
                listOf("ssh-qualified-id"),
                index.evaluate(
                    plan = publicKeyPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                ).map { it.id },
            )
            assertEquals(
                "old text",
                index.evaluate(
                    plan = publicKeyPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                ).single().text,
            )
            assertSearchContextBadge(
                item = index.evaluate(
                    plan = publicKeyPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                ).single(),
                field = VaultTextField.Ssh,
                text = "public-key-token",
            )

            val fingerprintPlan = index.compile("ssh:fingerprint", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(fingerprintPlan)
            assertEquals(
                listOf("ssh-qualified-id"),
                index.evaluate(
                    plan = fingerprintPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                ).map { it.id },
            )
            assertEquals(
                "old text",
                index.evaluate(
                    plan = fingerprintPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                ).single().text,
            )
            assertSearchContextBadge(
                item = index.evaluate(
                    plan = fingerprintPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                ).single(),
                field = VaultTextField.Ssh,
                text = "fingerprint-token",
            )

            val privateKeyPlan = index.compile("ssh:private", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(privateKeyPlan)
            val privateKeyOut =
                index.evaluate(
                    plan = privateKeyPlan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )
            assertEquals(1, privateKeyOut.size)
            assertEquals("old text", privateKeyOut.single().text)
            assertSearchContextBadge(
                item = privateKeyOut.single(),
                field = VaultTextField.Ssh,
                text = HIDDEN_FIELD_MASK,
            )
        }

    @Test
    fun `brand qualifier no longer aliases card brand`() =
        runTest {
            val secret =
                createSecret(
                    id = "brand-negative-id",
                    name = "Corporate Card",
                    card =
                        DSecret.Card(
                            cardholderName = "Jane Cardholder",
                            brand = "Visa",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("brand:visa", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(emptyList(), out)
        }

    @Test
    fun `qualified card brand query matches and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "brand-qualified-id",
                    name = "Corporate Card",
                    card =
                        DSecret.Card(
                            cardholderName = "Jane Cardholder",
                            brand = "Visa",
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("card-brand:visa", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.CardBrand,
                text = "Visa",
            )
        }

    @Test
    fun `bare query matches passkey display name and writes context`() =
        runTest {
            val secret =
                createSecret(
                    id = "passkey-id",
                    name = "Passkey Vault",
                    login =
                        DSecret.Login(
                            fido2Credentials =
                                listOf(
                                    DSecret.Login.Fido2Credentials(
                                        credentialId = "cred-id",
                                        keyType = "public-key",
                                        keyAlgorithm = "ECDSA",
                                        keyCurve = "P-256",
                                        keyValue = "key-value",
                                        rpId = "example.com",
                                        rpName = "Example",
                                        counter = 1,
                                        userHandle = "user-handle",
                                        userName = "alice",
                                        userDisplayName = "Alice Device",
                                        discoverable = true,
                                        creationDate = com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT,
                                    ),
                                ),
                        ),
                )
            val item = createItem(secret, text = "old text")
            val index = builder.build(listOf(secret))

            val plan = index.compile("device", VaultRoute.Args.SearchBy.ALL)
            assertNotNull(plan)
            val out =
                index.evaluate(
                    plan = plan,
                    candidates = listOf(item),
                    highlightBackgroundColor = Color.Unspecified,
                    highlightContentColor = Color.Unspecified,
                )

            assertEquals(1, out.size)
            assertEquals(item.title, out.single().title)
            assertEquals("old text", out.single().text)
            assertSearchContextBadge(
                item = out.single(),
                field = VaultTextField.PasskeyDisplayName,
                text = "Alice Device",
            )
        }
}

private fun assertSearchContextBadge(
    item: com.artemchep.keyguard.feature.home.vault.model.VaultItem2.Item,
    field: VaultTextField,
    text: String,
) {
    val badge = assertNotNull(item.searchContextBadge)
    assertEquals(field, badge.field)
    assertEquals(text, badge.text)
}

private class CountingSearchTokenizer(
    private val delegate: SearchTokenizer,
) : SearchTokenizer {
    var tokenizeCalls: Int = 0
        private set

    override fun tokenize(
        value: String,
        profile: SearchTokenizerProfile,
        config: SearchTokenizerConfig,
    ): SearchTokenization {
        tokenizeCalls += 1
        return delegate.tokenize(
            value = value,
            profile = profile,
            config = config,
        )
    }

    fun reset() {
        tokenizeCalls = 0
    }
}
