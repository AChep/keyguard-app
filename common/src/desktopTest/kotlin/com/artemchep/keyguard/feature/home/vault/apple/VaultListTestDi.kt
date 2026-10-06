package com.artemchep.keyguard.feature.home.vault.apple

import arrow.core.Either
import arrow.core.right
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.CipherOpenedHistoryMode
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.DCipherOpenedHistory
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DEquivalentDomains
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DTag
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.FolderOwnership2
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.PatchWatchtowerAlertCipherRequest
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.testCipherFilterContext
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.model.AddCipherFilterRequest
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.ArchiveCipherById
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.ChangeCipherTagsById
import com.artemchep.keyguard.common.usecase.CipherMerge
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.usecase.FavouriteCipherById
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import com.artemchep.keyguard.common.usecase.GetVaultSearchIndex
import com.artemchep.keyguard.common.usecase.GetVaultSearchQualifierCatalog
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.PatchWatchtowerAlertCipher
import com.artemchep.keyguard.common.usecase.QueueSyncAll
import com.artemchep.keyguard.common.usecase.RePromptCipherById
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.usecase.UnarchiveCipherById
import com.artemchep.keyguard.common.usecase.impl.GetSuggestionsImpl
import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactory
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginRouteFactoryDefault
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactoryDefault
import com.artemchep.keyguard.feature.home.vault.search.engine.Bm25SearchScorer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchExecutor
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultVaultSearchIndexBuilder
import com.artemchep.keyguard.feature.home.vault.search.engine.NoOpVaultSearchTraceSink
import com.artemchep.keyguard.feature.home.vault.search.engine.VAULT_SEARCH_SURFACE_VAULT_LIST
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndex
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndexMetadata
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchTraceSink
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.defaultVaultSearchQualifierCatalog
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.DefaultVaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.VaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactory
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRouteFactoryDefault
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

internal class VaultListDiRecorders {
    val renameFolderRequests = CopyOnWriteArrayList<Map<String, String>>()
    val clearVaultSessionRequests = CopyOnWriteArrayList<LockReason>()
    val addCipherFilterRequests = CopyOnWriteArrayList<AddCipherFilterRequest>()
    val queueSyncAllCount = AtomicInteger()
}

internal class InMemoryDeeplinkService : DeeplinkService {
    private val sink = MutableStateFlow<Map<String, String?>>(emptyMap())

    override fun get(key: String): String? = sink.value[key]

    override fun getFlow(key: String): Flow<String?> = sink
        .map { it[key] }
        .distinctUntilChanged()

    override fun put(key: String, value: String?) {
        sink.update { it + (key to value) }
    }

    override fun clear(key: String) {
        sink.update { it - key }
    }
}

private object LastTwoLabelsTldService : TldService {
    override val version: String get() = "test"

    override fun getDomainName(host: String): IO<String> = {
        host.split('.')
            .takeLast(2)
            .joinToString(".")
    }
}

internal fun vaultListTestKoin(
    flows: VaultListFixtureFlows,
    recorders: VaultListDiRecorders = VaultListDiRecorders(),
    clipboardService: ClipboardService = RecordingClipboardService(),
    overrides: Module.() -> Unit = {},
): Koin = Koin().apply {
    // Not an application root: the fixture graph is loaded the way the other
    // isolated test graphs are, so the compiler plugin's root checks stay off.
    loadModules(
        listOf(vaultListTestModule(flows, recorders, clipboardService, overrides)),
        allowOverride = true,
    )
}

private fun vaultListTestModule(
    flows: VaultListFixtureFlows,
    recorders: VaultListDiRecorders,
    clipboardService: ClipboardService,
    overrides: Module.() -> Unit,
): Module = module {
    fixtureReads(flows)
    preferenceReads(flows)
    searchEngine(flows)
    suggestions()
    leafUseCases()
    recordingWriteUseCases(recorders)
    servicesAndRouteFactories(clipboardService)

    // Declares the vault scope so the harness can open one; its fakes are root definitions.
    scope<VaultSessionScope> { }

    overrides()
}

// Fixture-backed reads.
private fun Module.fixtureReads(flows: VaultListFixtureFlows) {
    single<GetCiphers> {
        object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = flows.ciphers
        }
    }
    single<GetProfiles> {
        object : GetProfiles {
            override fun invoke(): Flow<List<DProfile>> = flows.profiles
        }
    }
    single<GetAccounts> {
        object : GetAccounts {
            override fun invoke(): Flow<List<DAccount>> = flows.accounts
        }
    }
    single<GetFolders> {
        object : GetFolders {
            override fun invoke(): Flow<List<DFolder>> = flows.folders
        }
    }
    single<GetTags> {
        object : GetTags {
            override fun invoke(): Flow<List<DTag>> = flows.tags
        }
    }
    single<GetCollections> {
        object : GetCollections {
            override fun invoke(): Flow<List<DCollection>> = flows.collections
        }
    }
    single<GetOrganizations> {
        object : GetOrganizations {
            override fun invoke(): Flow<List<DOrganization>> = flows.organizations
        }
    }
    single<GetCipherFilters> {
        object : GetCipherFilters {
            override fun invoke(): Flow<List<DCipherFilter>> = flowOf(emptyList())
        }
    }
    single<GetCipherOpenedHistory> {
        object : GetCipherOpenedHistory {
            override fun invoke(p1: CipherOpenedHistoryMode): Flow<List<DCipherOpenedHistory>> =
                flowOf(emptyList())
        }
    }
}

// Preference reads.
private fun Module.preferenceReads(flows: VaultListFixtureFlows) {
    single<GetCanWrite> {
        object : GetCanWrite {
            override fun invoke(): Flow<Boolean> = flows.canWrite
        }
    }
    single<GetConcealFields> {
        object : GetConcealFields {
            override fun invoke(): Flow<Boolean> = flows.concealFields
        }
    }
    single<GetAppIcons> {
        object : GetAppIcons {
            override fun invoke(): Flow<Boolean> = flows.appIcons
        }
    }
    single<GetWebsiteIcons> {
        object : GetWebsiteIcons {
            override fun invoke(): Flow<Boolean> = flows.websiteIcons
        }
    }
}

// Search engine: real index/highlighter over the fixture flows.
private fun Module.searchEngine(flows: VaultListFixtureFlows) {
    single<GetVaultSearchIndex> {
        val metadataFlow = combine(
            flows.accounts,
            flows.folders,
            flows.tags,
            flows.collections,
            flows.organizations,
        ) { accounts, folders, tags, collections, organizations ->
            VaultSearchIndexMetadata(
                accounts = accounts,
                folders = folders,
                tags = tags,
                collections = collections,
                organizations = organizations,
            )
        }
        object : GetVaultSearchIndex {
            override fun invoke(surface: String?): Flow<VaultSearchIndex> = combine(
                flows.ciphers,
                metadataFlow,
            ) { ciphers, metadata -> ciphers to metadata }
                .mapLatest { (ciphers, metadata) ->
                    val tokenizer = DefaultSearchTokenizer()
                    DefaultVaultSearchIndexBuilder(
                        tokenizer = tokenizer,
                        scorer = Bm25SearchScorer(),
                        executor = DefaultSearchExecutor(),
                        parser = DefaultVaultSearchParser(),
                        compiler = DefaultVaultSearchQueryCompiler(tokenizer),
                    ).build(
                        items = ciphers,
                        metadata = metadata,
                        surface = surface ?: VAULT_SEARCH_SURFACE_VAULT_LIST,
                    )
                }
        }
    }
    single<GetVaultSearchQualifierCatalog> {
        object : GetVaultSearchQualifierCatalog {
            override fun invoke() = flowOf(defaultVaultSearchQualifierCatalog)
        }
    }
    single<VaultSearchQueryHighlighter> {
        DefaultVaultSearchQueryHighlighter(
            parser = DefaultVaultSearchParser(),
        )
    }
    single<VaultSearchTraceSink> {
        NoOpVaultSearchTraceSink
    }
}

// Suggestions: the real engine over fake preference sources.
private fun Module.suggestions() {
    single<GetAutofillDefaultMatchDetection> {
        object : GetAutofillDefaultMatchDetection {
            override fun invoke(): Flow<DSecret.Uri.MatchType> =
                flowOf(DSecret.Uri.MatchType.default)
        }
    }
    single<GetUrlBlocks> {
        object : GetUrlBlocks {
            override fun invoke(): Flow<List<DGlobalUrlBlock>> = flowOf(emptyList())
        }
    }
    single<CipherUrlCheck> {
        CipherUrlCheckImpl(
            tldService = LastTwoLabelsTldService,
        )
    }
    single<GetEquivalentDomains> {
        object : GetEquivalentDomains {
            override fun invoke(): Flow<List<DEquivalentDomains>> = flowOf(emptyList())
        }
    }
    single<EquivalentDomainsBuilderFactory> {
        EquivalentDomainsBuilderFactory(
            logRepository = LogRepositoryBridge(emptyList()),
            getEquivalentDomains = get(),
        )
    }
    single<GetSuggestions<Any?>> {
        GetSuggestionsImpl(
            getAutofillDefaultMatchDetection = get(),
            getUrlBlocks = get(),
            cipherUrlCheck = get(),
        )
    }
    single<CipherFilterContext> {
        testCipherFilterContext(
            getAutofillDefaultMatchDetection = get(),
            equivalentDomainsBuilderFactory = get(),
        )
    }
}

// Small leaf use cases.
private fun Module.leafUseCases() {
    single<GetTotpCode> {
        object : GetTotpCode {
            override fun invoke(p1: TotpToken): Flow<Either<Throwable, TotpCode>> = flowOf(
                TotpCode(
                    code = "123456",
                    counter = TotpCode.TimeBasedCounter(
                        timestamp = TEST_INSTANT,
                        expiration = TEST_INSTANT + 30.seconds,
                        duration = 30.seconds,
                    ),
                ).right(),
            )
        }
    }
    single<GetPasswordStrength> {
        object : GetPasswordStrength {
            override fun invoke(password: String): IO<PasswordStrength> = {
                PasswordStrength(
                    crackTimeSeconds = password.length.toLong() * 10_000L,
                    version = 1L,
                )
            }
        }
    }
    single<PasskeyTargetCheck> {
        object : PasskeyTargetCheck {
            override fun invoke(
                credential: DSecret.Login.Fido2Credentials,
                target: PasskeyTarget,
            ): IO<Boolean> = {
                target.rpId == null || credential.rpId == target.rpId
            }
        }
    }
    single<DateFormatter> {
        object : DateFormatter {
            override fun formatDateTimeMachine(instant: Instant): String = instant.toString()
            override fun formatDateTime(instant: Instant): String = instant.toString()
            override fun formatDate(instant: Instant): String = instant.toString().take(10)
            override suspend fun formatDateShort(instant: Instant): String =
                instant.toString().take(7)

            override suspend fun formatDateShort(date: LocalDate): String =
                date.toString().take(7)

            override fun formatDateMedium(date: LocalDate): String = date.toString()
            override fun formatTimeShort(time: LocalTime): String = time.toString()
        }
    }
    single<SupervisorRead> {
        object : SupervisorRead {
            override fun get(): Flow<Map<AccountTask, Set<AccountId>>> = flowOf(emptyMap())
            override fun get(accountTask: AccountTask): Flow<Set<AccountId>> = flowOf(emptySet())
        }
    }
}

// Write-shaped use cases: record and succeed.
private fun Module.recordingWriteUseCases(recorders: VaultListDiRecorders) {
    single<QueueSyncAll> {
        object : QueueSyncAll {
            override fun invoke(): IO<Unit> = {
                recorders.queueSyncAllCount.incrementAndGet()
                Unit
            }
        }
    }
    single<ClearVaultSession> {
        object : ClearVaultSession {
            override fun invoke(p1: LockReason, p2: TextHolder): IO<Unit> = {
                recorders.clearVaultSessionRequests += p1
            }
        }
    }
    single<RenameFolderById> {
        object : RenameFolderById {
            override fun invoke(p1: Map<String, String>): IO<Unit> = {
                recorders.renameFolderRequests += p1
            }
        }
    }
    single<AddCipherFilter> {
        object : AddCipherFilter {
            override fun invoke(p1: AddCipherFilterRequest): IO<Unit> = {
                recorders.addCipherFilterRequests += p1
            }
        }
    }
    single<CipherToolbox> {
        noOpCipherToolbox()
    }
}

// Services & route factories.
private fun Module.servicesAndRouteFactories(clipboardService: ClipboardService) {
    single<ClipboardService> {
        clipboardService
    }
    single<DeeplinkService> {
        InMemoryDeeplinkService()
    }
    single<ConfirmationRouteFactory> {
        ConfirmationRouteFactoryDefault
    }
    single<BitwardenLoginRouteFactory> {
        BitwardenLoginRouteFactoryDefault
    }
    single<PasskeysCredentialViewRouteFactory> {
        PasskeysCredentialViewRouteFactoryDefault
    }
}

private fun noOpCipherToolbox(): CipherToolbox = object : CipherToolbox {
    override val favouriteCipherById = object : FavouriteCipherById {
        override fun invoke(p1: Set<String>, p2: Boolean): IO<Unit> = {}
    }
    override val rePromptCipherById = object : RePromptCipherById {
        override fun invoke(p1: Set<String>, p2: Boolean): IO<Unit> = {}
    }
    override val changeCipherNameById = object : ChangeCipherNameById {
        override fun invoke(p1: Map<String, String>): IO<Unit> = {}
    }
    override val changeCipherTagsById = object : ChangeCipherTagsById {
        override fun invoke(p1: Map<String, List<String>>): IO<Unit> = {}
    }
    override val changeCipherPasswordById = object : ChangeCipherPasswordById {
        override fun invoke(p1: Map<String, String>): IO<Unit> = {}
    }
    override val copyCipherById = object : CopyCipherById {
        override fun invoke(p1: Map<String, CreateRequest.Ownership2>): IO<Unit> = {}
    }
    override val moveCipherToFolderById = object : MoveCipherToFolderById {
        override fun invoke(p1: Set<String>, p2: FolderOwnership2): IO<Unit> = {}
    }
    override val patchWatchtowerAlertCipher = object : PatchWatchtowerAlertCipher {
        override fun invoke(p1: PatchWatchtowerAlertCipherRequest): IO<Boolean> = { true }
    }
    override val restoreCipherById = object : RestoreCipherById {
        override fun invoke(p1: Set<String>): IO<Unit> = {}
    }
    override val trashCipherById = object : TrashCipherById {
        override fun invoke(p1: Set<String>): IO<Unit> = {}
    }
    override val unarchiveCipherById = object : UnarchiveCipherById {
        override fun invoke(p1: Set<String>): IO<Unit> = {}
    }
    override val archiveCipherById = object : ArchiveCipherById {
        override fun invoke(p1: Set<String>): IO<Unit> = {}
    }
    override val removeCipherById = object : RemoveCipherById {
        override fun invoke(p1: Set<String>): IO<Unit> = {}
    }
    override val cipherMerge = object : CipherMerge {
        override fun invoke(p1: List<DSecret>): DSecret = p1.first()
    }
}
