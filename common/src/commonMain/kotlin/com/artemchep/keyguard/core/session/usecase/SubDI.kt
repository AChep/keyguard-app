package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepository
import com.artemchep.keyguard.android.downloader.journal.CipherHistoryOpenedRepositoryImpl
import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepository
import com.artemchep.keyguard.android.downloader.journal.GeneratorHistoryRepositoryImpl
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.filter.AddCipherFilter
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.service.filter.RemoveCipherFilterById
import com.artemchep.keyguard.common.service.filter.RenameCipherFilter
import com.artemchep.keyguard.common.service.filter.impl.AddCipherFilterImpl
import com.artemchep.keyguard.common.service.filter.impl.GetCipherFiltersImpl
import com.artemchep.keyguard.common.service.filter.impl.RemoveCipherFilterByIdImpl
import com.artemchep.keyguard.common.service.filter.impl.RenameCipherFilterImpl
import com.artemchep.keyguard.common.service.filter.repo.CipherFilterRepository
import com.artemchep.keyguard.common.service.filter.repo.impl.CipherFilterRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistRepository
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.repo.GeneratorWordlistWordRepository
import com.artemchep.keyguard.common.service.wordlist.repo.impl.GeneratorWordlistWordRepositoryImpl
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesLocalDataSource
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRemoteDataSource
import com.artemchep.keyguard.common.service.hibp.breaches.all.BreachesRepository
import com.artemchep.keyguard.common.service.hibp.breaches.all.impl.BreachesLocalDataSourceImpl
import com.artemchep.keyguard.common.service.hibp.breaches.all.impl.BreachesRemoteDataSourceImpl
import com.artemchep.keyguard.common.service.hibp.breaches.all.impl.BreachesRepositoryImpl
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceLocal
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageDataSourceRemote
import com.artemchep.keyguard.common.service.hibp.passwords.PasswordPwnageRepository
import com.artemchep.keyguard.common.service.hibp.passwords.impl.PasswordPwnageDataSourceLocalImpl
import com.artemchep.keyguard.common.service.hibp.passwords.impl.PasswordPwnageDataSourceRemoteImpl
import com.artemchep.keyguard.common.service.hibp.passwords.impl.PasswordPwnageRepositoryImpl
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepository
import com.artemchep.keyguard.common.service.relays.repo.GeneratorEmailRelayRepositoryImpl
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepository
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepositoryImpl
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.AddCipherUsedAutofillHistory
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.AddEmailRelay
import com.artemchep.keyguard.common.usecase.AddFolder
import com.artemchep.keyguard.common.usecase.AddGeneratorHistory
import com.artemchep.keyguard.common.usecase.AddPasskeyCipher
import com.artemchep.keyguard.common.usecase.AddSend
import com.artemchep.keyguard.common.usecase.AddUriCipher
import com.artemchep.keyguard.common.usecase.AddUrlOverride
import com.artemchep.keyguard.common.usecase.BackupSettings
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.CheckPasswordLeak
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CheckUsernameLeak
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherFieldSwitchToggle
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherMerge
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistory
import com.artemchep.keyguard.common.usecase.CipherRemovePasswordHistoryById
import com.artemchep.keyguard.common.usecase.CipherToolbox
import com.artemchep.keyguard.common.usecase.CipherToolboxImpl
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlAutoFix
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.DownloadAttachmentMetadata
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.ExportAccount
import com.artemchep.keyguard.common.usecase.ExportLogs
import com.artemchep.keyguard.common.usecase.FavouriteCipherById
import com.artemchep.keyguard.common.usecase.GetAccountHasError
import com.artemchep.keyguard.common.usecase.GetAccountStatus
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetAccountsHasError
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetBreachesLatestDate
import com.artemchep.keyguard.common.usecase.GetCanAddAccount
import com.artemchep.keyguard.common.usecase.GetCipherOpenedCount
import com.artemchep.keyguard.common.usecase.GetCipherOpenedHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetWordlists
import com.artemchep.keyguard.common.usecase.GetEmailRelays
import com.artemchep.keyguard.common.usecase.GetEnvSendUrl
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetFingerprint
import com.artemchep.keyguard.common.usecase.GetFolderTree
import com.artemchep.keyguard.common.usecase.GetFolderTreeById
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetGeneratorHistory
import com.artemchep.keyguard.common.usecase.GetMetas
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.GetSends
import com.artemchep.keyguard.common.usecase.GetShouldRequestAppReview
import com.artemchep.keyguard.common.usecase.GetUrlOverrides
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadAlerts
import com.artemchep.keyguard.common.usecase.GetWatchtowerUnreadCount
import com.artemchep.keyguard.common.usecase.GetWordlistPrimitive
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsNotRead
import com.artemchep.keyguard.common.usecase.MarkAllWatchtowerAlertAsRead
import com.artemchep.keyguard.common.usecase.MarkWatchtowerAlertAsRead
import com.artemchep.keyguard.common.usecase.MergeFolderById
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.common.usecase.PatchSendById
import com.artemchep.keyguard.common.usecase.PatchWatchtowerAlertCipher
import com.artemchep.keyguard.common.usecase.PutAccountColorById
import com.artemchep.keyguard.common.usecase.PutAccountMasterPasswordHintById
import com.artemchep.keyguard.common.usecase.PutAccountNameById
import com.artemchep.keyguard.common.usecase.PutProfileHidden
import com.artemchep.keyguard.common.usecase.RePromptCipherById
import com.artemchep.keyguard.common.usecase.RemoveAccountById
import com.artemchep.keyguard.common.usecase.RemoveAccounts
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.common.usecase.RemoveEmailRelayById
import com.artemchep.keyguard.common.usecase.RemoveFolderById
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistory
import com.artemchep.keyguard.common.usecase.RemoveGeneratorHistoryById
import com.artemchep.keyguard.common.usecase.RemoveSendById
import com.artemchep.keyguard.common.usecase.RemoveUrlOverrideById
import com.artemchep.keyguard.common.usecase.RenameFolderById
import com.artemchep.keyguard.common.usecase.ResetAllWatchtowerAlert
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.common.usecase.RetryCipher
import com.artemchep.keyguard.common.usecase.RotateDeviceIdUseCase
import com.artemchep.keyguard.common.usecase.SendToolbox
import com.artemchep.keyguard.common.usecase.SendToolboxImpl
import com.artemchep.keyguard.common.usecase.SupervisorRead
import com.artemchep.keyguard.common.usecase.SyncAll
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.common.usecase.TrashCipherByFolderId
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.usecase.Watchdog
import com.artemchep.keyguard.common.usecase.WatchdogImpl
import com.artemchep.keyguard.common.usecase.impl.AddWordlistImpl
import com.artemchep.keyguard.common.usecase.impl.AddEmailRelayImpl
import com.artemchep.keyguard.common.usecase.impl.AddGeneratorHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.AddUrlOverrideImpl
import com.artemchep.keyguard.common.usecase.impl.BackupSettingsImpl
import com.artemchep.keyguard.common.usecase.impl.DownloadAttachmentImpl2
import com.artemchep.keyguard.common.usecase.impl.DownloadAttachmentMetadataImpl2
import com.artemchep.keyguard.common.usecase.impl.EditWordlistImpl
import com.artemchep.keyguard.common.usecase.impl.GetAccountStatusImpl
import com.artemchep.keyguard.common.usecase.impl.GetBreachesImpl
import com.artemchep.keyguard.common.usecase.impl.GetBreachesLatestDateImpl
import com.artemchep.keyguard.common.usecase.impl.GetCanAddAccountImpl
import com.artemchep.keyguard.common.usecase.impl.GetEnvSendUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetGeneratorHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.GetShouldRequestAppReviewImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveGeneratorHistoryByIdImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveGeneratorHistoryImpl
import com.artemchep.keyguard.common.usecase.impl.WatchtowerBroadUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerDuplicateUris
import com.artemchep.keyguard.common.usecase.impl.WatchtowerExpiring
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactivePasskey
import com.artemchep.keyguard.common.usecase.impl.WatchtowerInactiveTfa
import com.artemchep.keyguard.common.usecase.impl.WatchtowerIncomplete
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordPwned
import com.artemchep.keyguard.common.usecase.impl.WatchtowerPasswordStrength
import com.artemchep.keyguard.common.usecase.impl.WatchtowerUnsecureWebsite
import com.artemchep.keyguard.common.usecase.impl.WatchtowerWebsitePwned
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipherRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollectionRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenEquivalentDomainRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolderRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMetaRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganizationRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfileRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSendRepositoryImpl
import com.artemchep.keyguard.core.store.bitwarden.BitwardenTokenRepositoryImpl
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCipherRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenCollectionRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenDomainRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenFolderRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenMetaRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenOrganizationRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenSendRepository
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenTokenRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherOpenedHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherUsedAutofillHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddCipherUsedPasskeyHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddFolderImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddPasskeyCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddSendImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.AddUriCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ChangeCipherNameByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ChangeCipherPasswordByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CheckPasswordLeakImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CheckPasswordSetLeakImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CheckUsernameLeakImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherBreachCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherDuplicatesCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherExpiringCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherFieldSwitchToggleImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherIncompleteCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherMergeImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherRemovePasswordHistoryByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherRemovePasswordHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUnsecureUrlAutoFixImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUnsecureUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CopyCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ExportAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ExportLogsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.FavouriteCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetAccountHasErrorImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetAccountsHasErrorImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetAccountsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCipherOpenedCountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCipherOpenedHistoryImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCiphersImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetCollectionsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWordlistsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetEmailRelaysImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetEquivalentDomainsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFingerprintImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFolderTreeByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFolderTreeImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetFoldersImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetMetasImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetOrganizationsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetProfilesImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetSendsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetUrlOverridesImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWatchtowerAlertsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWatchtowerUnreadAlertsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWatchtowerUnreadCountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.GetWordlistPrimitiveImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MarkAllWatchtowerAlertAsNotReadImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MarkAllWatchtowerAlertAsReadImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MarkWatchtowerAlertAsReadImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MergeFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.MoveCipherToFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PatchSendByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PatchWatchtowerAlertCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountColorByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountMasterPasswordHintByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutAccountNameByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.PutProfileHiddenImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RePromptCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveAccountByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveAccountsImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveWordlistByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveEmailRelayByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveSendByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RemoveUrlOverrideByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RenameFolderByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.ResetAllWatchtowerAlertImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RestoreCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.RetryCipherImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.SyncAllImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.SyncByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.TrashCipherByFolderIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.TrashCipherByIdImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.AddAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfaImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByToken
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SyncByTokenImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyDatabase
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyFolderById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyProfileById
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifySendById
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance

expect fun DI.Builder.createSubDi(
    masterKey: MasterKey,
)

fun DI.Builder.createSubDi2(
    masterKey: MasterKey,
) {
    bindSingleton<DownloadAttachment> {
        DownloadAttachmentImpl2(this)
    }
    bindSingleton<DownloadAttachmentMetadata> {
        DownloadAttachmentMetadataImpl2(this)
    }
    bindSingleton<ExportAccount> {
        ExportAccountImpl(this)
    }
    bindSingleton<GetCanAddAccount> {
        GetCanAddAccountImpl(this)
    }
    bindSingleton<GetEnvSendUrl> {
        GetEnvSendUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<GetEmailRelays> {
        GetEmailRelaysImpl(this)
    }
    bindSingleton<AddEmailRelay> {
        AddEmailRelayImpl(this)
    }
    bindSingleton<RemoveEmailRelayById> {
        RemoveEmailRelayByIdImpl(this)
    }
    bindSingleton<GetWordlistPrimitive> {
        GetWordlistPrimitiveImpl(this)
    }
    bindSingleton<GetWordlists> {
        GetWordlistsImpl(this)
    }
    bindSingleton<AddWordlist> {
        AddWordlistImpl(this)
    }
    bindSingleton<EditWordlist> {
        EditWordlistImpl(this)
    }
    bindSingleton<RemoveWordlistById> {
        RemoveWordlistByIdImpl(this)
    }
    bindSingleton<BackupSettings> {
        BackupSettingsImpl(this)
    }
    bindSingleton<AddUrlOverride> {
        AddUrlOverrideImpl(this)
    }
    bindSingleton<GetUrlOverrides> {
        GetUrlOverridesImpl(this)
    }
    bindSingleton<RemoveUrlOverrideById> {
        RemoveUrlOverrideByIdImpl(this)
    }
    bindSingleton<GetWatchtowerAlerts> {
        GetWatchtowerAlertsImpl(this)
    }
    bindSingleton<GetWatchtowerUnreadAlerts> {
        GetWatchtowerUnreadAlertsImpl(this)
    }
    bindSingleton<GetWatchtowerUnreadCount> {
        GetWatchtowerUnreadCountImpl(this)
    }
    bindSingleton<MarkAllWatchtowerAlertAsNotRead> {
        MarkAllWatchtowerAlertAsNotReadImpl(this)
    }
    bindSingleton<MarkAllWatchtowerAlertAsRead> {
        MarkAllWatchtowerAlertAsReadImpl(this)
    }
    bindSingleton<MarkWatchtowerAlertAsRead> {
        MarkWatchtowerAlertAsReadImpl(this)
    }
    bindSingleton<GetAccounts> {
        GetAccountsImpl(this)
    }
    bindSingleton<GetAccountStatus> {
        GetAccountStatusImpl(this)
    }
    bindSingleton<GetAccountsHasError> {
        GetAccountsHasErrorImpl(this)
    }
    bindSingleton<GetAccountHasError> {
        GetAccountHasErrorImpl(this)
    }
    bindSingleton<GetCiphers> {
        GetCiphersImpl(this)
    }
    bindSingleton<GetSends> {
        GetSendsImpl(this)
    }
    bindSingleton<GetCollections> {
        GetCollectionsImpl(this)
    }
    bindSingleton<GetOrganizations> {
        GetOrganizationsImpl(this)
    }
    bindSingleton<GetFingerprint> {
        GetFingerprintImpl(this)
    }
    bindSingleton<GetEquivalentDomains> {
        GetEquivalentDomainsImpl(this)
    }
    bindSingleton<EquivalentDomainsBuilderFactory> {
        EquivalentDomainsBuilderFactory(this)
    }
    bindSingleton<GetFolders> {
        GetFoldersImpl(this)
    }
    bindSingleton<GetFolderTree> {
        GetFolderTreeImpl(this)
    }
    bindSingleton<GetFolderTreeById> {
        GetFolderTreeByIdImpl(this)
    }
    bindSingleton<GetProfiles> {
        GetProfilesImpl(this)
    }
    bindSingleton<GetMetas> {
        GetMetasImpl(this)
    }
    bindSingleton<AddAccount> {
        AddAccountImpl(this)
    }
    bindSingleton<RequestEmailTfa> {
        RequestEmailTfaImpl(this)
    }
    bindSingleton<RotateDeviceIdUseCase> {
        RotateDeviceIdUseCase(this)
    }
    bindSingleton<RemoveAccounts> {
        RemoveAccountsImpl(this)
    }
    bindSingleton<RemoveAccountById> {
        RemoveAccountByIdImpl(this)
    }
    bindSingleton<ModifyCipherById> {
        ModifyCipherById(this)
    }
    bindSingleton<ModifySendById> {
        ModifySendById(this)
    }
    bindSingleton<ModifyFolderById> {
        ModifyFolderById(this)
    }
    bindSingleton<ModifyProfileById> {
        ModifyProfileById(this)
    }
    bindSingleton<ModifyDatabase> {
        ModifyDatabase(this)
    }
    bindSingleton<TrashCipherById> {
        TrashCipherByIdImpl(this)
    }
    bindSingleton<TrashCipherByFolderId> {
        TrashCipherByFolderIdImpl(this)
    }
    bindSingleton<RestoreCipherById> {
        RestoreCipherByIdImpl(this)
    }
    bindSingleton<MoveCipherToFolderById> {
        MoveCipherToFolderByIdImpl(this)
    }
    bindSingleton<RemoveCipherById> {
        RemoveCipherByIdImpl(this)
    }
    bindSingleton<RemoveSendById> {
        RemoveSendByIdImpl(this)
    }
    bindSingleton<PatchSendById> {
        PatchSendByIdImpl(this)
    }
    bindSingleton<RemoveFolderById> {
        RemoveFolderByIdImpl(this)
    }
    bindSingleton<MergeFolderById> {
        MergeFolderByIdImpl(this)
    }
    bindSingleton<ChangeCipherPasswordById> {
        ChangeCipherPasswordByIdImpl(this)
    }
    bindSingleton<ChangeCipherNameById> {
        ChangeCipherNameByIdImpl(this)
    }
    bindSingleton<RetryCipher> {
        RetryCipherImpl(this)
    }
    bindSingleton<CipherFieldSwitchToggle> {
        CipherFieldSwitchToggleImpl(this)
    }
    bindSingleton<CipherUnsecureUrlAutoFix> {
        CipherUnsecureUrlAutoFixImpl(this)
    }
    bindSingleton<CipherToolbox> {
        CipherToolboxImpl(this)
    }
    bindSingleton<SendToolbox> {
        SendToolboxImpl(this)
    }
    bindSingleton<WatchtowerInactivePasskey>() {
        WatchtowerInactivePasskey(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerInactiveTfa>() {
        WatchtowerInactiveTfa(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerDuplicateUris>() {
        WatchtowerDuplicateUris(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerBroadUris>() {
        WatchtowerBroadUris(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerPasswordStrength>() {
        WatchtowerPasswordStrength(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerPasswordPwned>() {
        WatchtowerPasswordPwned(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerWebsitePwned>() {
        WatchtowerWebsitePwned(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerIncomplete>() {
        WatchtowerIncomplete(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerExpiring>() {
        WatchtowerExpiring(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerUnsecureWebsite>() {
        WatchtowerUnsecureWebsite(
            directDI = this,
        )
    }
    bindSingleton<CipherUnsecureUrlCheck> {
        CipherUnsecureUrlCheckImpl(this)
    }
    bindSingleton<CipherIncompleteCheck> {
        CipherIncompleteCheckImpl(this)
    }
    bindSingleton<CipherMerge> {
        CipherMergeImpl(this)
    }
    bindSingleton<CipherExpiringCheck> {
        CipherExpiringCheckImpl(this)
    }
    bindSingleton<CipherBreachCheck> {
        CipherBreachCheckImpl(this)
    }
    bindSingleton<CipherDuplicatesCheck> {
        CipherDuplicatesCheckImpl(this)
    }
    bindSingleton<RenameFolderById> {
        RenameFolderByIdImpl(this)
    }
    bindSingleton<CheckPasswordLeak> {
        CheckPasswordLeakImpl(this)
    }
    bindSingleton<CheckPasswordSetLeak> {
        CheckPasswordSetLeakImpl(this)
    }
    bindSingleton<CheckUsernameLeak> {
        CheckUsernameLeakImpl(this)
    }
    bindSingleton<AddFolder> {
        AddFolderImpl(this)
    }
    bindSingleton<ExportLogs> {
        ExportLogsImpl(this)
    }
    bindSingleton<PutAccountColorById> {
        PutAccountColorByIdImpl(this)
    }
    bindSingleton<PutAccountMasterPasswordHintById> {
        PutAccountMasterPasswordHintByIdImpl(this)
    }
    bindSingleton<PutAccountNameById> {
        PutAccountNameByIdImpl(this)
    }
    bindSingleton<PutProfileHidden> {
        PutProfileHiddenImpl(this)
    }
    bindSingleton<GetGeneratorHistory> {
        GetGeneratorHistoryImpl(
            directDI = this,
        )
    }
    bindSingleton<RemoveGeneratorHistory> {
        RemoveGeneratorHistoryImpl(
            directDI = this,
        )
    }
    bindSingleton<RemoveGeneratorHistoryById> {
        RemoveGeneratorHistoryByIdImpl(
            directDI = this,
        )
    }
    bindSingleton<AddGeneratorHistory> {
        AddGeneratorHistoryImpl(
            directDI = this,
        )
    }
    bindSingleton<AddCipher> {
        AddCipherImpl(this)
    }
    bindSingleton<AddSend> {
        AddSendImpl(this)
    }
    bindSingleton<AddCipherOpenedHistory> {
        AddCipherOpenedHistoryImpl(this)
    }
    bindSingleton<AddCipherUsedAutofillHistory> {
        AddCipherUsedAutofillHistoryImpl(this)
    }
    bindSingleton<AddCipherUsedPasskeyHistory> {
        AddCipherUsedPasskeyHistoryImpl(this)
    }
    bindSingleton<GetCipherOpenedHistory> {
        GetCipherOpenedHistoryImpl(this)
    }
    bindSingleton<GetCipherOpenedCount> {
        GetCipherOpenedCountImpl(this)
    }
    bindSingleton<GetShouldRequestAppReview> {
        GetShouldRequestAppReviewImpl(this)
    }
    bindSingleton<CipherHistoryOpenedRepository> {
        CipherHistoryOpenedRepositoryImpl(this)
    }
    bindSingleton<GeneratorHistoryRepository> {
        GeneratorHistoryRepositoryImpl(this)
    }
    bindSingleton<GeneratorEmailRelayRepository> {
        GeneratorEmailRelayRepositoryImpl(this)
    }
    bindSingleton<GeneratorWordlistRepository> {
        GeneratorWordlistRepositoryImpl(this)
    }
    bindSingleton<GeneratorWordlistWordRepository> {
        GeneratorWordlistWordRepositoryImpl(this)
    }
    bindSingleton<CipherFilterRepository> {
        CipherFilterRepositoryImpl(this)
    }
    bindSingleton<AddCipherFilter> {
        AddCipherFilterImpl(this)
    }
    bindSingleton<GetCipherFilters> {
        GetCipherFiltersImpl(this)
    }
    bindSingleton<RemoveCipherFilterById> {
        RemoveCipherFilterByIdImpl(this)
    }
    bindSingleton<RenameCipherFilter> {
        RenameCipherFilterImpl(this)
    }
    bindSingleton<UrlOverrideRepository> {
        UrlOverrideRepositoryImpl(this)
    }
    bindSingleton<PasswordPwnageDataSourceLocal> {
        PasswordPwnageDataSourceLocalImpl(this)
    }
    bindSingleton<PasswordPwnageDataSourceRemote> {
        PasswordPwnageDataSourceRemoteImpl(this)
    }
    bindSingleton<PasswordPwnageRepository> {
        PasswordPwnageRepositoryImpl(this)
    }
    bindSingleton<BreachesRemoteDataSource> {
        BreachesRemoteDataSourceImpl(this)
    }
    bindSingleton<BreachesLocalDataSource> {
        BreachesLocalDataSourceImpl(this)
    }
    bindSingleton<BreachesRepository> {
        BreachesRepositoryImpl(this)
    }
    bindSingleton<GetBreaches> {
        GetBreachesImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBreachesLatestDate> {
        GetBreachesLatestDateImpl(
            directDI = this,
        )
    }
    bindSingleton<AddUriCipher> {
        AddUriCipherImpl(this)
    }
    bindSingleton<AddPasskeyCipher> {
        AddPasskeyCipherImpl(this)
    }
    bindSingleton<PatchWatchtowerAlertCipher> {
        PatchWatchtowerAlertCipherImpl(this)
    }
    bindSingleton<FavouriteCipherById> {
        FavouriteCipherByIdImpl(this)
    }
    bindSingleton<RePromptCipherById> {
        RePromptCipherByIdImpl(this)
    }
    bindSingleton<ResetAllWatchtowerAlert> {
        ResetAllWatchtowerAlertImpl(this)
    }
    bindSingleton<CopyCipherById> {
        CopyCipherByIdImpl(this)
    }
    bindSingleton<CipherRemovePasswordHistoryById> {
        CipherRemovePasswordHistoryByIdImpl(this)
    }
    bindSingleton<CipherRemovePasswordHistory> {
        CipherRemovePasswordHistoryImpl(this)
    }
    bindSingleton<SyncAll> {
        SyncAllImpl(this)
    }
    bindSingleton<SyncById> {
        SyncByIdImpl(this)
    }
    bindSingleton<SyncByToken> {
        SyncByTokenImpl(this)
    }
    bindSingleton<WatchdogImpl> {
        WatchdogImpl(this)
    }
    bindSingleton<Watchdog> {
        instance<WatchdogImpl>()
    }
    bindSingleton<SupervisorRead> {
        instance<WatchdogImpl>()
    }

    bindSingleton { masterKey }
    bindSingleton<BitwardenTokenRepository> {
        BitwardenTokenRepositoryImpl(this)
    }
    bindSingleton<BitwardenSendRepository> {
        BitwardenSendRepositoryImpl(this)
    }
    bindSingleton<BitwardenCipherRepository> {
        BitwardenCipherRepositoryImpl(this)
    }
    bindSingleton<BitwardenCollectionRepository> {
        BitwardenCollectionRepositoryImpl(this)
    }
    bindSingleton<BitwardenOrganizationRepository> {
        BitwardenOrganizationRepositoryImpl(this)
    }
    bindSingleton<BitwardenDomainRepository> {
        BitwardenEquivalentDomainRepositoryImpl(this)
    }
    bindSingleton<BitwardenFolderRepository> {
        BitwardenFolderRepositoryImpl(this)
    }
    bindSingleton<BitwardenProfileRepository> {
        BitwardenProfileRepositoryImpl(this)
    }
    bindSingleton<BitwardenMetaRepository> {
        BitwardenMetaRepositoryImpl(this)
    }
    bindSingleton<DatabaseSyncer> {
        DatabaseSyncer(
            cryptoGenerator = instance(),
        )
    }
}
