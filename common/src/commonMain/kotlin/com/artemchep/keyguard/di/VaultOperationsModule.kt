package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationServiceUnsupported
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.RemoveCipherFilterById
import com.artemchep.keyguard.common.service.filter.RenameCipherFilter
import com.artemchep.keyguard.common.service.filter.impl.AddCipherFilterImpl
import com.artemchep.keyguard.common.service.filter.impl.GetCipherFiltersImpl
import com.artemchep.keyguard.common.service.filter.impl.RemoveCipherFilterByIdImpl
import com.artemchep.keyguard.common.service.filter.impl.RenameCipherFilterImpl
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverRefreshWorker
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.GpgKeyserverRefreshWorkerImpl
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.SqlDelightVaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.webdav.KtorWebDavClientFactory
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddPrivilegedApp
import com.artemchep.keyguard.common.usecase.AddSend
import com.artemchep.keyguard.common.usecase.AddUriCipher
import com.artemchep.keyguard.common.usecase.AddUrlBlock
import com.artemchep.keyguard.common.usecase.AddUrlOverride
import com.artemchep.keyguard.common.usecase.ArchiveCipherById
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.ChangeCipherTagsById
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationById
import com.artemchep.keyguard.common.usecase.CheckHibpApiToken
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherFieldSwitchToggle
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherMerge
import com.artemchep.keyguard.common.usecase.CipherSshKeyWeakCheck
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.CipherToolboxImpl
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlAutoFix
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.FavouriteCipherById
import com.artemchep.keyguard.common.usecase.GetAccountHasError
import com.artemchep.keyguard.common.usecase.GetAccountStatus
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAccountsHasError
import com.artemchep.keyguard.common.usecase.GetCanAddAccount
import com.artemchep.keyguard.common.usecase.GetCipherOpenedCount
import com.artemchep.keyguard.common.usecase.GetCipherSnapshots
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetEnvSendUrl
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetFingerprint
import com.artemchep.keyguard.common.usecase.GetFingerprintByAccount
import com.artemchep.keyguard.common.usecase.GetFolderTree
import com.artemchep.keyguard.common.usecase.GetFolderTreeById
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetHibpApiToken
import com.artemchep.keyguard.common.usecase.GetLocalNetworkAccessHint
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.GetNavItemsConfig
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.GetShouldRequestAppReview
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.GetUrlBlocks
import com.artemchep.keyguard.common.usecase.GetUrlOverrides
import com.artemchep.keyguard.common.usecase.GetVaultSearchIndex
import com.artemchep.keyguard.common.usecase.GetVaultSearchQualifierCatalog
import com.artemchep.keyguard.common.usecase.MergeFolderById
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.common.usecase.PatchSendById
import com.artemchep.keyguard.common.usecase.PutAccountColorById
import com.artemchep.keyguard.common.usecase.PutAccountMasterPasswordHintById
import com.artemchep.keyguard.common.usecase.PutAccountNameById
import com.artemchep.keyguard.common.usecase.PutHibpApiToken
import com.artemchep.keyguard.common.usecase.PutProfileHidden
import com.artemchep.keyguard.common.usecase.RePromptCipherById
import com.artemchep.keyguard.common.usecase.RefreshGpgPublicKeys
import com.artemchep.keyguard.common.usecase.RemoveAccountById
import com.artemchep.keyguard.common.usecase.RemoveAccounts
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.common.usecase.RemoveFolderById
import com.artemchep.keyguard.common.usecase.RemovePrivilegedAppById
import com.artemchep.keyguard.common.usecase.RemoveSendById
import com.artemchep.keyguard.common.usecase.RemoveUrlBlockById
import com.artemchep.keyguard.common.usecase.RemoveUrlOverrideById
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.usecase.ResolveFolderHierarchyMode
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.common.usecase.RetryCipher
import com.artemchep.keyguard.common.usecase.RotateDeviceIdUseCase
import com.artemchep.keyguard.common.usecase.SendToolbox
import com.artemchep.keyguard.common.usecase.SendToolboxImpl
import com.artemchep.keyguard.common.usecase.TrashCipherByFolderId
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.usecase.UnarchiveCipherById
import com.artemchep.keyguard.common.usecase.VerifyGpgPublicKey
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.AddUrlBlockImpl
import com.artemchep.keyguard.common.usecase.impl.AddUrlOverrideImpl
import com.artemchep.keyguard.common.usecase.impl.CheckHibpApiTokenImpl
import com.artemchep.keyguard.common.usecase.impl.GetAccountStatusImpl
import com.artemchep.keyguard.common.usecase.impl.GetCanAddAccountImpl
import com.artemchep.keyguard.common.usecase.impl.GetEnvSendUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetHibpApiTokenImpl
import com.artemchep.keyguard.common.usecase.impl.GetLocalNetworkAccessHintImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavItemsConfigImpl
import com.artemchep.keyguard.common.usecase.impl.GetShouldRequestAppReviewImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultSearchIndexImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultSearchQualifierCatalogImpl
import com.artemchep.keyguard.common.usecase.impl.PutHibpApiTokenImpl
import com.artemchep.keyguard.common.usecase.impl.VerifyGpgPublicKeyImpl
import com.artemchep.keyguard.common.usecase.impl.WindowCoroutineScopeImpl
import com.artemchep.keyguard.feature.home.vault.search.engine.Bm25SearchScorer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchExecutor
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultSearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.engine.DefaultVaultSearchIndexBuilder
import com.artemchep.keyguard.feature.home.vault.search.engine.DevVaultSearchTraceSink
import com.artemchep.keyguard.feature.home.vault.search.engine.NoOpVaultSearchTraceSink
import com.artemchep.keyguard.feature.home.vault.search.engine.SearchExecutor
import com.artemchep.keyguard.feature.home.vault.search.engine.SearchScorer
import com.artemchep.keyguard.feature.home.vault.search.engine.SearchTokenizer
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndexBuilder
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchTraceSink
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.DefaultVaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.compiler.VaultSearchQueryCompiler
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.DefaultVaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.query.highlight.VaultSearchQueryHighlighter
import com.artemchep.keyguard.feature.home.vault.search.query.lexer.DefaultVaultSearchLexer
import com.artemchep.keyguard.feature.home.vault.search.query.lexer.VaultSearchLexer
import com.artemchep.keyguard.feature.home.vault.search.query.parser.DefaultVaultSearchParser
import com.artemchep.keyguard.feature.home.vault.search.query.parser.VaultSearchParser
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCredentialCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddFolderImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddPrivilegedAppImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddSendImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddUriCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ArchiveCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ChangeCipherNameByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ChangeCipherPasswordByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ChangeCipherTagsByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ChangeGpgKeyExpirationByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherDuplicatesCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherExpiringCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherFieldSwitchToggleImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherIncompleteCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherMergeImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherSshKeyWeakCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUnsecureUrlAutoFixImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CopyCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.FavouriteCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetAccountHasErrorImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetAccountsHasErrorImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetAccountsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCipherOpenedCountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCipherSnapshotsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCiphersImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCollectionsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetEquivalentDomainsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFingerprintByAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFingerprintImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFolderTreeByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFolderTreeImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFoldersImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetMetasImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetOrganizationsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetPrivilegedAppsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetProfilesImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetSendsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetTagsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetUrlBlocksImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetUrlOverridesImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MergeFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MoveCipherToFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PatchSendByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountColorByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountMasterPasswordHintByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountNameByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutBitwardenAccountColorByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutBitwardenAccountMasterPasswordHintByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutBitwardenAccountNameByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeePassAccountColorById
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeePassAccountColorByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeePassAccountMasterPasswordHintById
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeePassAccountMasterPasswordHintByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeePassAccountNameById
import com.artemchep.keyguard.provider.bitwarden.usecase.PutKeePassAccountNameByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutProfileHiddenImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RePromptCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RefreshGpgPublicKeysImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveAccountByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveAccountsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemovePrivilegedAppByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveSendByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveUrlBlockByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveUrlOverrideByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RenameFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ResolveFolderHierarchyModeImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RestoreCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RetryCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.TrashCipherByFolderIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.TrashCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.UnarchiveCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddKeePassAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionBitwardenAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionBitwardenAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionKeePassAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.ImportCompanionKeePassAccountUseCase
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyFolderById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyProfileById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifySendById
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.scoped

internal class VaultOperationsModule {
    val module = module {
        scope<VaultSessionScope> {
            scoped<VaultSettingsKeyValueStore> {
                SqlDelightVaultSettingsKeyValueStore(
                    databaseManager = get(),
                    dispatcher = databaseDispatcher(),
                )
            }

            scoped<GetHibpApiTokenImpl>() bind GetHibpApiToken::class

            scoped<PutHibpApiTokenImpl>() bind PutHibpApiToken::class

            scoped<CheckHibpApiTokenImpl>() bind CheckHibpApiToken::class

            scoped<GetCanAddAccountImpl>() bind GetCanAddAccount::class

            scoped<GetEnvSendUrlImpl>() bind GetEnvSendUrl::class

            scoped<GetNavItemsConfig> {
                GetNavItemsConfigImpl(
                    getAccounts = get(),
                    getProfiles = get(),
                    getCiphers = get(),
                    settingsReadRepository = get(),
                    settingsReadWriteRepository = get(),
                    windowCoroutineScope = get(),
                )
            }

            scoped<AddUrlOverrideImpl>() bind AddUrlOverride::class

            scoped<AddUrlBlockImpl>() bind AddUrlBlock::class

            scoped<GetUrlOverridesImpl> {
                GetUrlOverridesImpl(
                    urlOverrideRepository = get(),
                )
            } bind GetUrlOverrides::class

            scoped<GetUrlBlocksImpl> {
                GetUrlBlocksImpl(
                    urlBlockRepository = get(),
                )
            } bind GetUrlBlocks::class

            scoped<RemoveUrlOverrideByIdImpl>() bind RemoveUrlOverrideById::class

            scoped<RemoveUrlBlockByIdImpl>() bind RemoveUrlBlockById::class

            scoped<RemovePrivilegedAppByIdImpl>() bind RemovePrivilegedAppById::class

            scoped<GetAccountsImpl> {
                GetAccountsImpl(
                    tokenRepository = get(),
                )
            } bind GetAccounts::class

            scoped<GetAccountStatusImpl>() bind GetAccountStatus::class

            scoped<GetLocalNetworkAccessHint> {
                GetLocalNetworkAccessHintImpl(
                    tokenRepository = get(),
                    backupConfigRepository = get(),
                )
            }

            scoped<GetAccountsHasErrorImpl>() bind GetAccountsHasError::class

            scoped<GetAccountHasErrorImpl>() bind GetAccountHasError::class

            scoped<GetCipherSnapshots> {
                GetCipherSnapshotsImpl(
                    logRepository = get(),
                    databaseManager = get(),
                    getPasswordStrength = get(),
                    windowCoroutineScope = get(),
                    dbDispatcher = databaseDispatcher(),
                    gpgKeyMetadataResolver = get(),
                )
            }

            scoped<GetCiphersImpl>() bind GetCiphers::class

            scoped<RefreshGpgPublicKeysImpl>() bind RefreshGpgPublicKeys::class

            scoped<GpgKeyserverRefreshWorkerImpl> {
                GpgKeyserverRefreshWorkerImpl(
                    getGpgKeyserverAutoRefresh = get(),
                    getGpgKeyserverRefreshInterval = get(),
                    getGpgKeyserverLastRefresh = get(),
                    getCiphers = get(),
                    refreshGpgPublicKeys = get(),
                    logRepository = get(),
                )
            } bind GpgKeyserverRefreshWorker::class

            scoped<VerifyGpgPublicKeyImpl>() bind VerifyGpgPublicKey::class

            scoped<GetSendsImpl> {
                GetSendsImpl(
                    logRepository = get(),
                    sendRepository = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetSends::class

            scoped<GetTagsImpl>() bind GetTags::class

            scoped<GetCollectionsImpl> {
                GetCollectionsImpl(
                    logRepository = get(),
                    collectionRepository = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetCollections::class

            scoped<GetOrganizationsImpl> {
                GetOrganizationsImpl(
                    logRepository = get(),
                    organizationRepository = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetOrganizations::class

            scoped<GetPrivilegedAppsImpl> {
                GetPrivilegedAppsImpl(
                    appPrivilegedAppRepository = get(),
                    userPrivilegedAppRepository = get(),
                )
            } bind GetPrivilegedApps::class

            scoped<GetFingerprintImpl> {
                GetFingerprintImpl(
                    cryptoGenerator = get(),
                    wordlistService = get(),
                )
            } bind GetFingerprint::class

            scoped<GetFingerprintByAccountImpl> {
                GetFingerprintByAccountImpl(
                    profileRepository = get(),
                    base64Service = get(),
                    getFingerprint = get(),
                )
            } bind GetFingerprintByAccount::class

            scoped<GetEquivalentDomainsImpl> {
                GetEquivalentDomainsImpl(
                    logRepository = get(),
                    domainRepository = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetEquivalentDomains::class

            scoped<EquivalentDomainsBuilderFactory>()

            scoped<DefaultSearchTokenizer> { DefaultSearchTokenizer() } bind SearchTokenizer::class

            scoped<Bm25SearchScorer> { Bm25SearchScorer() } bind SearchScorer::class

            scoped<DefaultSearchExecutor>() bind SearchExecutor::class

            scoped<VaultSearchTraceSink> {
                if (isRelease) {
                    NoOpVaultSearchTraceSink
                } else {
                    DevVaultSearchTraceSink(
                        logRepository = get<LogRepository>(),
                    )
                }
            }

            scoped<DefaultVaultSearchLexer>() bind VaultSearchLexer::class

            scoped<VaultSearchParser> {
                DefaultVaultSearchParser(
                    lexer = get(),
                )
            }

            scoped<GetVaultSearchQualifierCatalogImpl>() bind GetVaultSearchQualifierCatalog::class

            scoped<VaultSearchQueryCompiler> {
                DefaultVaultSearchQueryCompiler(
                    tokenizer = get(),
                )
            }

            scoped<VaultSearchQueryHighlighter> {
                DefaultVaultSearchQueryHighlighter(
                    parser = get(),
                )
            }

            scoped<VaultSearchIndexBuilder> {
                DefaultVaultSearchIndexBuilder(
                    tokenizer = get(),
                    scorer = get(),
                    executor = get(),
                    parser = get(),
                    compiler = get(),
                    traceSink = get(),
                )
            }

            scoped<GetVaultSearchIndexImpl> {
                GetVaultSearchIndexImpl(
                    logRepository = get(),
                    getCipherSnapshots = get(),
                    getAccounts = get(),
                    getFolders = get(),
                    getTags = get(),
                    getCollections = get(),
                    getOrganizations = get(),
                    searchIndexBuilder = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetVaultSearchIndex::class

            scoped<GetFoldersImpl> {
                GetFoldersImpl(
                    logRepository = get(),
                    folderRepository = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetFolders::class

            scoped<GetFolderTreeImpl>() bind GetFolderTree::class

            scoped<GetFolderTreeByIdImpl> {
                GetFolderTreeByIdImpl(
                    getFolders = get(),
                    getFolderTree = get(),
                )
            } bind GetFolderTreeById::class

            scoped<GetProfilesImpl> {
                GetProfilesImpl(
                    tokenRepository = get(),
                    profileRepository = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetProfiles::class

            scoped<GetMetasImpl> {
                GetMetasImpl(
                    metaRepository = get(),
                    windowCoroutineScope = get(),
                )
            } bind GetMetas::class

            scoped<AddAccountImpl>() bind AddAccount::class

            scoped<AddKeePassAccount> {
                AddKeePassAccountImpl(
                    getPurchased = get(),
                    getAccounts = get(),
                    queueSyncById = get(),
                    syncById = get(),
                    windowCoroutineScope = get(),
                    logRepository = get(),
                    cryptoGenerator = get(),
                    fileService = get(),
                    base64Service = get(),
                    webDavClientFactory = KtorWebDavClientFactory(
                        httpClient = get(),
                    ),
                    db = get(),
                )
            }

            scoped<ImportCompanionBitwardenAccountImpl>() bind ImportCompanionBitwardenAccount::class

            scoped<ImportCompanionKeePassAccountUseCase> {
                ImportCompanionKeePassAccountImpl(
                    addKeePassAccount = get(),
                )
            }

            scoped<RotateDeviceIdUseCase>()

            scoped<RemoveAccountsImpl>() bind RemoveAccounts::class

            scoped<RemoveAccountByIdImpl>() bind RemoveAccountById::class

            scoped<ModifyCipherById>()

            scoped<ModifySendById>()

            scoped<ModifyFolderById>()

            scoped<ModifyProfileById>()

            scoped<TrashCipherByIdImpl>() bind TrashCipherById::class

            scoped<ArchiveCipherByIdImpl>() bind ArchiveCipherById::class

            scoped<TrashCipherByFolderIdImpl>() bind TrashCipherByFolderId::class

            scoped<RestoreCipherByIdImpl>() bind RestoreCipherById::class

            scoped<UnarchiveCipherByIdImpl>() bind UnarchiveCipherById::class

            scoped<MoveCipherToFolderByIdImpl>() bind MoveCipherToFolderById::class

            scoped<RemoveCipherByIdImpl>() bind RemoveCipherById::class

            scoped<RemoveSendByIdImpl>() bind RemoveSendById::class

            scoped<PatchSendByIdImpl>() bind PatchSendById::class

            scoped<RemoveFolderByIdImpl>() bind RemoveFolderById::class

            scoped<MergeFolderByIdImpl>() bind MergeFolderById::class

            scoped<ChangeCipherPasswordByIdImpl>() bind ChangeCipherPasswordById::class

            scoped<ChangeCipherNameByIdImpl>() bind ChangeCipherNameById::class

            scoped<ChangeGpgKeyExpirationById> {
                ChangeGpgKeyExpirationByIdImpl(
                    getCiphers = get(),
                    modifyCipherById = get(),
                    gpgKeyExpirationService = getOrNull()
                    ?: GpgKeyExpirationServiceUnsupported,
                )
            }

            scoped<ChangeCipherTagsByIdImpl>() bind ChangeCipherTagsById::class

            scoped<RetryCipherImpl>() bind RetryCipher::class

            scoped<CipherFieldSwitchToggleImpl>() bind CipherFieldSwitchToggle::class

            scoped<CipherUnsecureUrlAutoFixImpl>() bind CipherUnsecureUrlAutoFix::class

            scoped<CipherToolboxImpl>() bind CipherToolbox::class

            scoped<SendToolboxImpl>() bind SendToolbox::class

            scoped<CipherIncompleteCheckImpl>() bind CipherIncompleteCheck::class

            scoped<CipherSshKeyWeakCheckImpl>() bind CipherSshKeyWeakCheck::class

            scoped<CipherMergeImpl>() bind CipherMerge::class

            scoped<CipherExpiringCheckImpl>() bind CipherExpiringCheck::class

            scoped<CipherDuplicatesCheckImpl> {
                CipherDuplicatesCheckImpl(
                    cryptoGenerator = get(),
                    base64Service = get(),
                    similarityService = get(),
                    logRepository = get(),
                )
            } bind CipherDuplicatesCheck::class

            scoped<RenameFolderByIdImpl>() bind RenameFolderById::class

            scoped<AddFolderImpl>() bind AddFolder::class

            scoped<ResolveFolderHierarchyModeImpl>() bind ResolveFolderHierarchyMode::class

            scoped<PutBitwardenAccountColorByIdImpl>()

            scoped<PutKeePassAccountColorByIdImpl>() bind PutKeePassAccountColorById::class

            scoped<PutAccountColorByIdImpl>() bind PutAccountColorById::class

            scoped<PutBitwardenAccountMasterPasswordHintByIdImpl>()

            scoped<PutKeePassAccountMasterPasswordHintByIdImpl>() bind PutKeePassAccountMasterPasswordHintById::class

            scoped<PutAccountMasterPasswordHintByIdImpl>() bind PutAccountMasterPasswordHintById::class

            scoped<PutBitwardenAccountNameByIdImpl>()

            scoped<PutKeePassAccountNameByIdImpl>() bind PutKeePassAccountNameById::class

            scoped<PutAccountNameByIdImpl>() bind PutAccountNameById::class

            scoped<PutProfileHiddenImpl>() bind PutProfileHidden::class

            scoped<AddCipher> {
                AddCipherImpl(
                    modifyDatabase = get(),
                    addFolder = get(),
                    resolveFolderHierarchyMode = get(),
                    archiveCipherById = get(),
                    trashCipherById = get(),
                    cryptoGenerator = get(),
                    getPasswordStrength = get(),
                    gpgKeyMetadataResolver = getOrNull(),
                    base64Service = get(),
                    pendingUploadCoordinator = get(),
                )
            }

            scoped<AddSendImpl>() bind AddSend::class

            scoped<GetCipherOpenedCountImpl>() bind GetCipherOpenedCount::class

            scoped<GetShouldRequestAppReviewImpl>() bind GetShouldRequestAppReview::class

            scoped<AddCipherFilterImpl>() bind AddCipherFilter::class

            scoped<GetCipherFiltersImpl>() bind GetCipherFilters::class

            scoped<RemoveCipherFilterByIdImpl>() bind RemoveCipherFilterById::class

            scoped<RenameCipherFilterImpl>() bind RenameCipherFilter::class

            scoped<AddUriCipherImpl>() bind AddUriCipher::class

            scoped<AddCredentialCipherImpl>() bind AddCredentialCipher::class

            scoped<AddPrivilegedAppImpl>() bind AddPrivilegedApp::class

            scoped<FavouriteCipherByIdImpl>() bind FavouriteCipherById::class

            scoped<RePromptCipherByIdImpl>() bind RePromptCipherById::class

            scoped<CopyCipherByIdImpl>() bind CopyCipherById::class

            scoped<MasterKey> {
                checkNotNull(getSource<VaultScopeSource>()).masterKey
            }

            scoped<WindowCoroutineScope> {
                WindowCoroutineScopeImpl(
                    scope = checkNotNull(getSource<VaultScopeSource>()).coroutineScope,
                    showMessage = get(),
                )
            }
        }
    }
}
