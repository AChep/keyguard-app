package com.artemchep.keyguard.feature.home.vault.search.benchmark

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndexMetadata
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant

private val TEST_INSTANT: Instant = Instant.parse("2024-01-01T00:00:00Z")

internal enum class BenchmarkCorpusSize(
    val label: String,
    val itemCount: Int,
) {
    Small(label = "small", itemCount = 250),
    Medium(label = "medium", itemCount = 2000),
    Large(label = "large", itemCount = 5000),
}

internal data class BenchmarkCorpus(
    val size: BenchmarkCorpusSize,
    val items: List<DSecret>,
    val candidates: List<VaultItem2.Item>,
    val metadata: VaultSearchIndexMetadata,
) {
    val itemCount: Int
        get() = items.size
}

internal data class BenchmarkQueryCase(
    val name: String,
    val query: String,
    val searchBy: VaultRoute.Args.SearchBy,
    val corpusSize: BenchmarkCorpusSize,
)

internal object VaultSearchBenchmarkFixtures {
    private val accounts = listOf(
        AccountSpec(
            id = "account-work",
            username = "alice",
            host = "vault.example.com",
        ),
        AccountSpec(
            id = "account-personal",
            username = "bob",
            host = "vault.example.com",
        ),
        AccountSpec(
            id = "account-shared",
            username = "carol",
            host = "vault.example.com",
        ),
    )

    private val folders = listOf(
        FolderSpec(
            id = "folder-work",
            name = "Work",
        ),
        FolderSpec(
            id = "folder-personal",
            name = "Personal",
        ),
        FolderSpec(
            id = "folder-archive",
            name = "Archive",
        ),
    )

    private val tags = listOf(
        "ops",
        "archive",
        "billing",
        "travel",
    )

    private val collections = listOf(
        CollectionSpec(
            id = "collection-work",
            organizationId = "org-engineering",
            name = "Work",
        ),
        CollectionSpec(
            id = "collection-shared",
            organizationId = "org-operations",
            name = "Shared",
        ),
    )

    private val organizations = listOf(
        OrganizationSpec(
            id = "org-engineering",
            name = "Engineering",
        ),
        OrganizationSpec(
            id = "org-operations",
            name = "Operations",
        ),
    )

    fun buildCorpora(): Map<BenchmarkCorpusSize, BenchmarkCorpus> = BenchmarkCorpusSize.values().associateWith { size ->
        buildCorpus(size)
    }

    fun buildQueries(): List<BenchmarkQueryCase> = listOf(
        BenchmarkQueryCase(
            name = "hot-text-alice",
            query = "alice",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Medium,
        ),
        BenchmarkQueryCase(
            name = "hot-text-bank-portal",
            query = "bank portal",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Medium,
        ),
        BenchmarkQueryCase(
            name = "facet-folder",
            query = "folder:work",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Medium,
        ),
        BenchmarkQueryCase(
            name = "facet-tag",
            query = "tag:ops",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Medium,
        ),
        BenchmarkQueryCase(
            name = "mixed-username-tag",
            query = "username:alice tag:ops",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Medium,
        ),
        BenchmarkQueryCase(
            name = "cold-note",
            query = "note:\"temp pin\"",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Medium,
        ),
        BenchmarkQueryCase(
            name = "negation",
            query = "project -tag:archive",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Medium,
        ),
        BenchmarkQueryCase(
            name = "parallel-threshold",
            query = "portal",
            searchBy = VaultRoute.Args.SearchBy.ALL,
            corpusSize = BenchmarkCorpusSize.Large,
        ),
    )

    fun withSingleChangedItem(corpus: BenchmarkCorpus): BenchmarkCorpus {
        val changed = corpus.items.toMutableList()
        val index = corpus.items.lastIndex / 2
        changed[index] =
            changed[index].copy(
                name = changed[index].name + " updated",
                notes = changed[index].notes + " refreshed",
            )
        return buildCorpus(
            size = corpus.size,
            items = changed,
        )
    }

    fun withChangedMetadata(corpus: BenchmarkCorpus): BenchmarkCorpus {
        val metadata =
            corpus.metadata.copy(
                folders =
                    corpus.metadata.folders.map { folder ->
                        if (folder.id == "folder-work") {
                            folder.copy(name = "Office")
                        } else {
                            folder
                        }
                    },
            )
        return corpus.copy(metadata = metadata)
    }

    fun digest(corpus: BenchmarkCorpus): List<CorpusDigestEntry> = corpus.items.map { item ->
        CorpusDigestEntry(
            id = item.id,
            accountId = item.accountId,
            folderId = item.folderId,
            organizationId = item.organizationId,
            collectionIds = item.collectionIds,
            name = item.name,
            notes = item.notes,
            tags = item.tags,
            username = item.login?.username,
            attachmentCount = item.attachments.size,
            passkeyCount = item.login?.fido2Credentials?.size ?: 0,
        )
    }

    private fun buildCorpus(
        size: BenchmarkCorpusSize,
        items: List<DSecret>? = null,
    ): BenchmarkCorpus {
        val actualItems =
            items ?: List(size.itemCount) { index ->
                buildSecret(
                    index = index,
                )
            }
        return BenchmarkCorpus(
            size = size,
            items = actualItems,
            candidates = actualItems.map(::createItem),
            metadata = buildMetadata(),
        )
    }

    private fun buildMetadata(): VaultSearchIndexMetadata = VaultSearchIndexMetadata(
        accounts =
            accounts.map { account ->
                DAccount(
                    id = AccountId(account.id),
                    username = account.username,
                    host = account.host,
                    webVaultUrl = "https://${account.host}",
                    localVaultUrl = null,
                    type = AccountType.BITWARDEN,
                    faviconServer = null,
                )
            },
        folders =
            folders.mapIndexed { index, folder ->
                DFolder(
                    id = folder.id,
                    accountId = accounts[index % accounts.size].id,
                    revisionDate = TEST_INSTANT,
                    service = BitwardenService(),
                    deleted = false,
                    synced = true,
                    name = folder.name,
                )
            },
        tags = tags.map(::DTag),
        collections =
            collections.mapIndexed { index, collection ->
                DCollection(
                    id = collection.id,
                    externalId = null,
                    organizationId = collection.organizationId,
                    accountId = accounts[index % accounts.size].id,
                    revisionDate = TEST_INSTANT,
                    name = collection.name,
                    readOnly = false,
                    hidePasswords = false,
                )
            },
        organizations =
            organizations.mapIndexed { index, organization ->
                DOrganization(
                    id = organization.id,
                    accountId = accounts[index % accounts.size].id,
                    revisionDate = TEST_INSTANT,
                    keyBase64 = "key-${organization.id}",
                    name = organization.name,
                    accentColor = generateAccentColors(organization.name),
                    selfHost = false,
                )
            },
    )

    private fun buildSecret(
        index: Int,
    ): DSecret {
        val account = accounts[index % accounts.size]
        val folder = folders[index % folders.size]
        val tagValues =
            buildList {
                if (index % 2 == 0) add("ops")
                if (index % 3 == 0) add("archive")
                if (index % 5 == 0) add("billing")
                if (index % 7 == 0) add("travel")
            }
        val collectionIds =
            buildSet {
                add(collections[index % collections.size].id)
                if (index % 4 == 0) add(collections.last().id)
            }
        val login =
            DSecret.Login(
                username =
                    if (index % 2 == 0) {
                        "alice$index@bank.example.com"
                    } else {
                        "user$index@portal.example.com"
                    },
                password = "super-secret-$index",
                passwordRevisionDate = TEST_INSTANT,
                fido2Credentials =
                    if (index % 11 == 0) {
                        listOf(
                            DSecret.Login.Fido2Credentials(
                                credentialId = "credential-$index",
                                keyType = "public-key",
                                keyAlgorithm = "ECDSA",
                                keyCurve = "P-256",
                                keyValue = "key-value-$index",
                                rpId = "portal.example.com",
                                rpName = "Portal",
                                counter = index,
                                userHandle = "user-handle-$index",
                                userName = "user-$index",
                                userDisplayName = "Portal user $index",
                                discoverable = true,
                                creationDate = TEST_INSTANT,
                            ),
                        )
                    } else {
                        emptyList()
                    },
            )
        val card =
            if (index % 6 == 2) {
                DSecret.Card(
                    cardholderName = "Alice Example $index",
                    brand = "Visa",
                    number = "4111111111111111",
                    fromMonth = "01",
                    fromYear = "2020",
                    expMonth = "12",
                    expYear = "2030",
                    code = "123",
                )
            } else {
                null
            }
        val identity =
            if (index % 6 == 3) {
                DSecret.Identity(
                    title = "Ms",
                    firstName = "Alice",
                    middleName = "Q",
                    lastName = "Example",
                    address1 = "1 Portal Way",
                    address2 = "Suite $index",
                    city = "Kiev",
                    state = "KY",
                    postalCode = "0100$index",
                    country = "US",
                    company = "Portal Inc",
                    email = "alice-$index@example.com",
                    phone = "+1555000$index",
                    username = "alice-$index",
                )
            } else {
                null
            }
        val sshKey =
            if (index % 6 == 4) {
                DSecret.SshKey(
                    privateKey = "-----BEGIN PRIVATE KEY-----\nprivate-$index\n-----END PRIVATE KEY-----",
                    publicKey = "ssh-ed25519 public-$index",
                    fingerprint = "SHA256:portal-$index",
                )
            } else {
                null
            }
        val attachments =
            if (index % 5 == 0) {
                listOf(
                    DSecret.Attachment.Remote(
                        id = "attachment-$index",
                        url = "https://files.example.com/$index.pdf",
                        remoteCipherId = "remote-$index",
                        fileName = "portal-contract-$index.pdf",
                        keyBase64 = null,
                        size = 1024L + index,
                    ),
                )
            } else {
                emptyList()
            }
        val fields =
            buildList {
                if (index % 4 == 0) {
                    add(
                        DSecret.Field(
                            name = "api token",
                            value = "token-$index",
                            type = DSecret.Field.Type.Text,
                        ),
                    )
                }
                if (index % 4 == 1) {
                    add(
                        DSecret.Field(
                            name = "is archived",
                            value = if (index % 2 == 0) "true" else "false",
                            type = DSecret.Field.Type.Boolean,
                        ),
                    )
                }
                if (index % 6 == 0) {
                    add(
                        DSecret.Field(
                            name = "linked username",
                            value = "alice-$index",
                            linkedId = DSecret.Field.LinkedId.Login_Username,
                            type = DSecret.Field.Type.Linked,
                        ),
                    )
                }
            }

        return DSecret(
            id = "secret-$index",
            accountId = account.id,
            folderId = folder.id,
            organizationId = organizations[index % organizations.size].id.takeIf { index % 4 == 0 },
            collectionIds = collectionIds,
            revisionDate = TEST_INSTANT,
            createdDate = TEST_INSTANT,
            archivedDate = null,
            deletedDate = null,
            service = BitwardenService(),
            name =
                when (index % 4) {
                    0 -> "Bank Portal $index"
                    1 -> "Project Portal $index"
                    2 -> "Shared Portal $index"
                    else -> "Ops Portal $index"
                },
            notes =
                when (index % 3) {
                    0 -> "temp pin $index keep quiet"
                    1 -> "project $index notes archive"
                    else -> "shared portal note $index"
                },
            favorite = index % 10 == 0,
            reprompt = index % 13 == 0,
            synced = true,
            tags = tagValues,
            uris =
                listOf(
                    DSecret.Uri(uri = "https://portal.example.com/$index"),
                    DSecret.Uri(uri = "https://bank.example.com/$index"),
                ),
            fields = fields,
            attachments = attachments,
            passwordHistory = emptyList(),
            type =
                when (index % 6) {
                    0 -> DSecret.Type.Login
                    1 -> DSecret.Type.SecureNote
                    2 -> DSecret.Type.Card
                    3 -> DSecret.Type.Identity
                    4 -> DSecret.Type.SshKey
                    else -> DSecret.Type.Login
                },
            login = login,
            card = card,
            identity = identity,
            sshKey = sshKey,
        )
    }

    private fun createItem(
        source: DSecret,
        title: String = source.name,
        text: String? = source.login?.username ?: source.notes.takeIf { it.isNotBlank() },
    ): VaultItem2.Item = VaultItem2.Item(
        id = source.id,
        source = source,
        accentLight = Color(0xFF2196F3),
        accentDark = Color(0xFF64B5F6),
        accountId = source.accountId,
        groupId = null,
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        password = source.login?.password,
        passwordRevisionDate = source.login?.passwordRevisionDate,
        score = source.login?.passwordStrength,
        type = source.type.name,
        folderId = source.folderId,
        icon = VaultItemIcon.TextIcon("T"),
        feature = VaultItem2.Item.Feature.None,
        copyText = createCopyText(),
        token = source.login?.totp?.token,
        passwords = persistentListOf(),
        passkeys = persistentListOf(),
        attachments2 = persistentListOf(),
        title = AnnotatedString(title),
        text = text,
        favourite = source.favorite,
        attachments = source.attachments.isNotEmpty(),
        action = VaultItem2.Item.Action.None,
        localStateFlow =
            MutableStateFlow(
                VaultItem2.Item.LocalState(
                    openedState = VaultItem2.Item.OpenedState(isOpened = false),
                    selectableItemState =
                        SelectableItemState(
                            selecting = false,
                            selected = false,
                            onClick = null,
                            onLongClick = null,
                        ),
                ),
            ),
    )

    private fun createCopyText(): CopyText = CopyText(
        clipboardService =
            object : ClipboardService {
                override fun setPrimaryClip(value: String, concealed: Boolean) = Unit
                override fun clearPrimaryClip() = Unit
                override fun hasCopyNotification(): Boolean = true
            },
        translator =
            object : TranslatorScope {
                override suspend fun translate(res: StringResource): String = res.toString()
                override suspend fun translate(res: StringResource, vararg args: Any): String = res.toString()
                override suspend fun translate(
                    res: PluralStringResource,
                    quantity: Int,
                    vararg args: Any,
                ): String = res.toString()
            },
        onMessage = { _: com.artemchep.keyguard.common.model.ToastMessage -> },
    )

    private data class AccountSpec(
        val id: String,
        val username: String,
        val host: String,
    )

    private data class FolderSpec(
        val id: String,
        val name: String,
    )

    private data class CollectionSpec(
        val id: String,
        val organizationId: String?,
        val name: String,
    )

    private data class OrganizationSpec(
        val id: String,
        val name: String,
    )
}

internal data class CorpusDigestEntry(
    val id: String,
    val accountId: String,
    val folderId: String?,
    val organizationId: String?,
    val collectionIds: Set<String>,
    val name: String,
    val notes: String,
    val tags: List<String>,
    val username: String?,
    val attachmentCount: Int,
    val passkeyCount: Int,
)
