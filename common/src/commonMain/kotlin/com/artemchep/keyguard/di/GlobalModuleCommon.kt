package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.AppWorker
import com.artemchep.keyguard.common.AppWorkerIm
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.app.parser.AndroidAppFDroidParser
import com.artemchep.keyguard.common.service.app.parser.AndroidAppGooglePlayParser
import com.artemchep.keyguard.common.service.app.parser.IosAppAppStoreParser
import com.artemchep.keyguard.common.service.clipboard.ClipboardEventBus
import com.artemchep.keyguard.common.usecase.KeyPairExport
import com.artemchep.keyguard.common.usecase.KeyPrivateExport
import com.artemchep.keyguard.common.usecase.KeyPublicExport
import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import com.artemchep.keyguard.common.service.deeplink.impl.DeeplinkServiceImpl
import com.artemchep.keyguard.common.service.download.DownloadService
import com.artemchep.keyguard.common.service.download.DownloadServiceImpl
import com.artemchep.keyguard.common.service.export.JsonExportService
import com.artemchep.keyguard.common.service.export.impl.JsonExportServiceImpl
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoExtractorExecute
import com.artemchep.keyguard.common.service.extract.impl.LinkInfoPlatformExtractor
import com.artemchep.keyguard.common.service.googleauthenticator.OtpMigrationService
import com.artemchep.keyguard.common.service.googleauthenticator.impl.OtpMigrationServiceImpl
import com.artemchep.keyguard.common.service.googleauthenticator.util.OtpMigrationParser
import com.artemchep.keyguard.common.service.gpmprivapps.PrivilegedAppsService
import com.artemchep.keyguard.common.service.gpmprivapps.impl.PrivilegedAppsServiceImpl
import com.artemchep.keyguard.common.service.id.IdRepository
import com.artemchep.keyguard.common.service.id.impl.IdRepositoryImpl
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justdeleteme.impl.JustDeleteMeServiceImpl
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.service.justgetmydata.impl.JustGetMyDataServiceImpl
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsService
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceHost
import com.artemchep.keyguard.common.service.keyboard.KeyboardShortcutsServiceImpl
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.license.LicenseService
import com.artemchep.keyguard.common.service.license.impl.LicenseServiceImpl
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributorsService
import com.artemchep.keyguard.common.service.localizationcontributors.impl.LocalizationContributorsServiceImpl
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepository
import com.artemchep.keyguard.common.service.logging.inmemory.InMemoryLogRepositoryImpl
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.notification.NotificationFingerprintRepository
import com.artemchep.keyguard.common.service.notification.impl.NotificationFingerprintRepositoryImpl
import com.artemchep.keyguard.common.service.passkey.PassKeyService
import com.artemchep.keyguard.common.service.passkey.impl.PassKeyServiceImpl
import com.artemchep.keyguard.common.service.placeholder.impl.CipherPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CommentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.CustomPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.DateTimePlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.EnvironmentPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextReplaceRegexPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.TextTransformPlaceholder
import com.artemchep.keyguard.common.service.placeholder.impl.UrlPlaceholder
import com.artemchep.keyguard.common.service.relays.di.emailRelayDiModule
import com.artemchep.keyguard.common.service.review.ReviewLog
import com.artemchep.keyguard.common.service.review.impl.ReviewLogImpl
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.service.settings.SettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.impl.SettingsRepositoryImpl
import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.service.state.StateRepository
import com.artemchep.keyguard.common.service.state.impl.StateRepositoryImpl
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.tld.impl.TldServiceImpl
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.service.totp.impl.TotpServiceImpl
import com.artemchep.keyguard.common.service.twofa.TwoFaService
import com.artemchep.keyguard.common.service.twofa.impl.TwoFaServiceImpl
import com.artemchep.keyguard.common.service.vault.FingerprintReadRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.service.vault.KeyReadRepository
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadRepository
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadWriteRepository
import com.artemchep.keyguard.common.service.vault.SessionReadRepository
import com.artemchep.keyguard.common.service.vault.SessionReadWriteRepository
import com.artemchep.keyguard.common.service.vault.impl.FingerprintRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.KeyRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.SessionMetadataRepositoryImpl
import com.artemchep.keyguard.common.service.vault.impl.SessionRepositoryImpl
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.service.wordlist.impl.WordlistServiceImpl
import com.artemchep.keyguard.common.usecase.AuthConfirmMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyDecryptUseCase
import com.artemchep.keyguard.common.usecase.BiometricKeyEncryptUseCase
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.ConfirmAccessByYubiKeyUseCase
import com.artemchep.keyguard.common.usecase.ConfirmAccessByPasswordUseCase
import com.artemchep.keyguard.common.usecase.DeviceEncryptionKeyUseCase
import com.artemchep.keyguard.common.usecase.DeviceIdUseCase
import com.artemchep.keyguard.common.usecase.DisableBiometric
import com.artemchep.keyguard.common.usecase.DisableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.DismissNotificationsByChannel
import com.artemchep.keyguard.common.usecase.EnableBiometric
import com.artemchep.keyguard.common.usecase.EnableYubiKeyUnlock
import com.artemchep.keyguard.common.usecase.GenerateMasterHashUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.GenerateMasterSaltUseCase
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.GetAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.GetAppBuildType
import com.artemchep.keyguard.common.usecase.GetAppIcons
import com.artemchep.keyguard.common.usecase.GetAppVersion
import com.artemchep.keyguard.common.usecase.GetAppVersionCode
import com.artemchep.keyguard.common.usecase.GetAppVersionName
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.GetAutofillManualSelection
import com.artemchep.keyguard.common.usecase.GetAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.GetAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.GetAutofillSaveUri
import com.artemchep.keyguard.common.usecase.GetBiometricRemainingDuration
import com.artemchep.keyguard.common.usecase.GetBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.GetBiometricTimeout
import com.artemchep.keyguard.common.usecase.GetBiometricTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetCachePremium
import com.artemchep.keyguard.common.usecase.GetCanWrite
import com.artemchep.keyguard.common.usecase.GetCheckPasskeys
import com.artemchep.keyguard.common.usecase.GetCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.GetCheckPwnedServices
import com.artemchep.keyguard.common.usecase.GetCheckTwoFA
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClear
import com.artemchep.keyguard.common.usecase.GetClipboardAutoClearVariants
import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.GetClipboardAutoRefreshVariants
import com.artemchep.keyguard.common.usecase.GetCloseToTray
import com.artemchep.keyguard.common.usecase.GetColors
import com.artemchep.keyguard.common.usecase.GetColorsVariants
import com.artemchep.keyguard.common.usecase.GetConcealFields
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.GetDebugScreenDelay
import com.artemchep.keyguard.common.usecase.GetFont
import com.artemchep.keyguard.common.usecase.GetFontVariants
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.common.usecase.GetInMemoryLogs
import com.artemchep.keyguard.common.usecase.GetInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.GetJustDeleteMeByUrl
import com.artemchep.keyguard.common.usecase.GetJustGetMyDataByUrl
import com.artemchep.keyguard.common.usecase.GetKeepScreenOn
import com.artemchep.keyguard.common.usecase.GetLocaleVariants
import com.artemchep.keyguard.common.usecase.GetMarkdown
import com.artemchep.keyguard.common.usecase.GetMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.GetNavAnimation
import com.artemchep.keyguard.common.usecase.GetNavAnimationVariants
import com.artemchep.keyguard.common.usecase.GetNavLabel
import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetPassphrase
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetPinCode
import com.artemchep.keyguard.common.usecase.GetProducts
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.common.usecase.GetSubscriptions
import com.artemchep.keyguard.common.usecase.GetTheme
import com.artemchep.keyguard.common.usecase.GetThemeExpressive
import com.artemchep.keyguard.common.usecase.GetThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.GetThemeVariants
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetUseExternalBrowser
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.GetVaultLockAfterTimeoutVariants
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetVersionLog
import com.artemchep.keyguard.common.usecase.GetWebsiteIcons
import com.artemchep.keyguard.common.usecase.GetWriteAccess
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.common.usecase.PasskeyTargetCheck
import com.artemchep.keyguard.common.usecase.PutAllowScreenshots
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInLandscape
import com.artemchep.keyguard.common.usecase.PutAllowTwoPanelLayoutInPortrait
import com.artemchep.keyguard.common.usecase.PutAppIcons
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.PutAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.PutAutofillInlineSuggestions
import com.artemchep.keyguard.common.usecase.PutAutofillManualSelection
import com.artemchep.keyguard.common.usecase.PutAutofillRespectAutofillOff
import com.artemchep.keyguard.common.usecase.PutAutofillSaveRequest
import com.artemchep.keyguard.common.usecase.PutAutofillSaveUri
import com.artemchep.keyguard.common.usecase.PutBiometricRequireConfirmation
import com.artemchep.keyguard.common.usecase.PutBiometricTimeout
import com.artemchep.keyguard.common.usecase.PutCachePremium
import com.artemchep.keyguard.common.usecase.PutCheckPasskeys
import com.artemchep.keyguard.common.usecase.PutCheckPwnedPasswords
import com.artemchep.keyguard.common.usecase.PutCheckPwnedServices
import com.artemchep.keyguard.common.usecase.PutCheckTwoFA
import com.artemchep.keyguard.common.usecase.PutClipboardAutoClear
import com.artemchep.keyguard.common.usecase.PutClipboardAutoRefresh
import com.artemchep.keyguard.common.usecase.PutCloseToTray
import com.artemchep.keyguard.common.usecase.PutColors
import com.artemchep.keyguard.common.usecase.PutConcealFields
import com.artemchep.keyguard.common.usecase.PutDebugPremium
import com.artemchep.keyguard.common.usecase.PutDebugScreenDelay
import com.artemchep.keyguard.common.usecase.PutFont
import com.artemchep.keyguard.common.usecase.PutGravatar
import com.artemchep.keyguard.common.usecase.PutInMemoryLogsEnabled
import com.artemchep.keyguard.common.usecase.PutKeepScreenOn
import com.artemchep.keyguard.common.usecase.PutMarkdown
import com.artemchep.keyguard.common.usecase.PutMinimizeOnCopy
import com.artemchep.keyguard.common.usecase.PutNavAnimation
import com.artemchep.keyguard.common.usecase.PutNavLabel
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import com.artemchep.keyguard.common.usecase.PutScreenState
import com.artemchep.keyguard.common.usecase.PutTheme
import com.artemchep.keyguard.common.usecase.PutThemeExpressive
import com.artemchep.keyguard.common.usecase.PutThemeUseAmoledDark
import com.artemchep.keyguard.common.usecase.PutUseExternalBrowser
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterReboot
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterScreenOff
import com.artemchep.keyguard.common.usecase.PutVaultLockAfterTimeout
import com.artemchep.keyguard.common.usecase.PutVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultSession
import com.artemchep.keyguard.common.usecase.PutWebsiteIcons
import com.artemchep.keyguard.common.usecase.PutWriteAccess
import com.artemchep.keyguard.common.usecase.ReadWordlistFromFile
import com.artemchep.keyguard.common.usecase.ReadWordlistFromUrl
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import com.artemchep.keyguard.common.usecase.RequestAppReview
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.ShowNotification
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import com.artemchep.keyguard.common.usecase.WatchtowerSyncer
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.impl.AuthConfirmMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.AuthGenerateMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.BiometricKeyDecryptUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.BiometricKeyEncryptUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.ClearVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.ConfirmAccessByYubiKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.ConfirmAccessByPasswordUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.DisableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.DisableYubiKeyUnlockImpl
import com.artemchep.keyguard.common.usecase.impl.DismissNotificationsByChannelImpl
import com.artemchep.keyguard.common.usecase.impl.EnableBiometricImpl
import com.artemchep.keyguard.common.usecase.impl.EnableYubiKeyUnlockImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterHashUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterKeyUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GenerateMasterSaltUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowScreenshotsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowTwoPanelLayoutInLandscapeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAllowTwoPanelLayoutInPortraitImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppBuildTypeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppIconsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAppVersionNameImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillCopyTotpImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillDefaultMatchDetectionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillInlineSuggestionsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillManualSelectionImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillRespectAutofillOffImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillSaveRequestImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillSaveUriImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricRemainingDurationImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricRequireConfirmationImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.GetBiometricTimeoutVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetCachePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.GetCanWriteImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPwnedPasswordsImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckPwnedServicesImpl
import com.artemchep.keyguard.common.usecase.impl.GetCheckTwoFAImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoClearImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoClearVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.GetClipboardAutoRefreshVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetCloseToTrayImpl
import com.artemchep.keyguard.common.usecase.impl.GetColorsImpl
import com.artemchep.keyguard.common.usecase.impl.GetColorsVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetConcealFieldsImpl
import com.artemchep.keyguard.common.usecase.impl.GetDebugPremiumImpl
import com.artemchep.keyguard.common.usecase.impl.GetDebugScreenDelayImpl
import com.artemchep.keyguard.common.usecase.impl.GetFontImpl
import com.artemchep.keyguard.common.usecase.impl.GetFontVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetGravatarImpl
import com.artemchep.keyguard.common.usecase.impl.GetGravatarUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetInMemoryLogsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.GetInMemoryLogsImpl
import com.artemchep.keyguard.common.usecase.impl.GetJustDeleteMeByUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetJustGetMyDataByUrlImpl
import com.artemchep.keyguard.common.usecase.impl.GetKeepScreenOnImpl
import com.artemchep.keyguard.common.usecase.impl.GetLocaleVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetMarkdownImpl
import com.artemchep.keyguard.common.usecase.impl.GetMinimizeOnCopyImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavAnimationImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavAnimationVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavLabelImpl
import com.artemchep.keyguard.common.usecase.impl.GetOnboardingLastVisitInstantImpl
import com.artemchep.keyguard.common.usecase.impl.GetPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.GetPasswordImpl
import com.artemchep.keyguard.common.usecase.impl.GetPinCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetProductsImpl
import com.artemchep.keyguard.common.usecase.impl.GetScreenStateImpl
import com.artemchep.keyguard.common.usecase.impl.GetSubscriptionsImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeExpressiveImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeUseAmoledDarkImpl
import com.artemchep.keyguard.common.usecase.impl.GetThemeVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetTotpCodeImpl
import com.artemchep.keyguard.common.usecase.impl.GetTwoFaImpl
import com.artemchep.keyguard.common.usecase.impl.GetUseExternalBrowserImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterRebootImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterScreenOffImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultLockAfterTimeoutVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultPersistImpl
import com.artemchep.keyguard.common.usecase.impl.GetVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.GetVersionLogImpl
import com.artemchep.keyguard.common.usecase.impl.GetWebsiteIconsImpl
import com.artemchep.keyguard.common.usecase.impl.GetWriteAccessImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPairExportImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPrivateExportImpl
import com.artemchep.keyguard.common.usecase.impl.KeyPublicExportImpl
import com.artemchep.keyguard.common.usecase.impl.MessageHubImpl
import com.artemchep.keyguard.common.usecase.impl.PasskeyTargetCheckImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowScreenshotsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowTwoPanelLayoutInLandscapeImpl
import com.artemchep.keyguard.common.usecase.impl.PutAllowTwoPanelLayoutInPortraitImpl
import com.artemchep.keyguard.common.usecase.impl.PutAppIconsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillCopyTotpImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillDefaultMatchDetectionImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillInlineSuggestionsImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillManualSelectionImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillRespectAutofillOffImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillSaveRequestImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillSaveUriImpl
import com.artemchep.keyguard.common.usecase.impl.PutBiometricRequireConfirmationImpl
import com.artemchep.keyguard.common.usecase.impl.PutBiometricTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.PutCachePremiumImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPasskeysImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPwnedPasswordsImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckPwnedServicesImpl
import com.artemchep.keyguard.common.usecase.impl.PutCheckTwoFAImpl
import com.artemchep.keyguard.common.usecase.impl.PutClipboardAutoClearImpl
import com.artemchep.keyguard.common.usecase.impl.PutClipboardAutoRefreshImpl
import com.artemchep.keyguard.common.usecase.impl.PutCloseToTrayImpl
import com.artemchep.keyguard.common.usecase.impl.PutColorsImpl
import com.artemchep.keyguard.common.usecase.impl.PutConcealFieldsImpl
import com.artemchep.keyguard.common.usecase.impl.PutDebugPremiumImpl
import com.artemchep.keyguard.common.usecase.impl.PutDebugScreenDelayImpl
import com.artemchep.keyguard.common.usecase.impl.PutFontImpl
import com.artemchep.keyguard.common.usecase.impl.PutGravatarImpl
import com.artemchep.keyguard.common.usecase.impl.PutInMemoryLogsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.PutKeepScreenOnImpl
import com.artemchep.keyguard.common.usecase.impl.PutMarkdownImpl
import com.artemchep.keyguard.common.usecase.impl.PutMinimizeOnCopyImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavAnimationImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavLabelImpl
import com.artemchep.keyguard.common.usecase.impl.PutOnboardingLastVisitInstantImpl
import com.artemchep.keyguard.common.usecase.impl.PutScreenStateImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeExpressiveImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeImpl
import com.artemchep.keyguard.common.usecase.impl.PutThemeUseAmoledDarkImpl
import com.artemchep.keyguard.common.usecase.impl.PutUserExternalBrowserImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterRebootImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterScreenOffImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultLockAfterTimeoutImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultPersistImpl
import com.artemchep.keyguard.common.usecase.impl.PutVaultSessionImpl
import com.artemchep.keyguard.common.usecase.impl.PutWebsiteIconsImpl
import com.artemchep.keyguard.common.usecase.impl.PutWriteAccessImpl
import com.artemchep.keyguard.common.usecase.impl.ReadWordlistFromFileImpl
import com.artemchep.keyguard.common.usecase.impl.ReadWordlistFromUrlImpl
import com.artemchep.keyguard.common.usecase.impl.RemoveAttachmentImpl
import com.artemchep.keyguard.common.usecase.impl.RequestAppReviewImpl
import com.artemchep.keyguard.common.usecase.impl.ShowNotificationImpl
import com.artemchep.keyguard.common.usecase.impl.UnlockUseCaseImpl
import com.artemchep.keyguard.common.usecase.impl.UpdateVersionLogImpl
import com.artemchep.keyguard.common.usecase.impl.WatchtowerSyncerImpl
import com.artemchep.keyguard.common.usecase.impl.WindowCoroutineScopeImpl
import com.artemchep.keyguard.common.usecase.impl.PasswordGeneratorDiceware
import com.artemchep.keyguard.common.service.similarity.impl.SimilarityServiceImpl
import com.artemchep.keyguard.common.service.urlblock.impl.UrlBlockRepositoryExposed
import com.artemchep.keyguard.common.usecase.BlockedUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.GetAllowScreenshotsVariants
import com.artemchep.keyguard.common.usecase.GetAutofillBlockedUrisExposed
import com.artemchep.keyguard.common.usecase.GetAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.GetAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.GetCacheHiddenSend
import com.artemchep.keyguard.common.usecase.GetNavForceHiddenSend
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindowVariants
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetSshAgentStatus
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.common.usecase.PutAutofillPasskeysEnabled
import com.artemchep.keyguard.common.usecase.PutAutofillPasswordsEnabled
import com.artemchep.keyguard.common.usecase.PutCacheHiddenSend
import com.artemchep.keyguard.common.usecase.PutNavForceHiddenSend
import com.artemchep.keyguard.common.usecase.PutSshAgent
import com.artemchep.keyguard.common.usecase.PutSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.PutSshAgentFilter
import com.artemchep.keyguard.common.usecase.impl.GetAllowScreenshotsVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillBlockedUrisExposedImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillPasskeysEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.GetAutofillPasswordsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.GetCacheHiddenSendImpl
import com.artemchep.keyguard.common.usecase.impl.GetNavForceHiddenSendImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentApprovalWindowImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentApprovalWindowVariantsImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentFilterImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentImpl
import com.artemchep.keyguard.common.usecase.impl.GetSshAgentStatusImpl
import com.artemchep.keyguard.common.usecase.impl.GetTotpCodeWithOffsetImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillPasskeysEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.PutAutofillPasswordsEnabledImpl
import com.artemchep.keyguard.common.usecase.impl.PutCacheHiddenSendImpl
import com.artemchep.keyguard.common.usecase.impl.PutNavForceForceHiddenSendImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentApprovalWindowImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentImpl
import com.artemchep.keyguard.common.usecase.impl.PutSshAgentFilterImpl
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.impl.PendingUploadCoordinatorImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.BlockedUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUnsecureUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlBroadCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlDuplicateCheckImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfa
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.RequestEmailTfaImpl
import org.kodein.di.DI
import org.kodein.di.bindProvider
import org.kodein.di.bindSingleton
import org.kodein.di.instance

fun globalModuleCommon() = DI.Module(
    name = "globalModuleCommon",
) {
    bindSingleton<DownloadService> {
        DownloadServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyboardShortcutsServiceImpl> {
        KeyboardShortcutsServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyboardShortcutsService> {
        instance<KeyboardShortcutsServiceImpl>()
    }
    bindSingleton<KeyboardShortcutsServiceHost> {
        instance<KeyboardShortcutsServiceImpl>()
    }
    bindSingleton<AuthConfirmMasterKeyUseCase> {
        AuthConfirmMasterKeyUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<AuthGenerateMasterKeyUseCase> {
        AuthGenerateMasterKeyUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<BiometricKeyDecryptUseCase> {
        BiometricKeyDecryptUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<BiometricKeyEncryptUseCase> {
        BiometricKeyEncryptUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricRemainingDuration> {
        GetBiometricRemainingDurationImpl(
            directDI = this,
        )
    }
    bindSingleton<DisableBiometric> {
        DisableBiometricImpl(
            directDI = this,
        )
    }
    bindSingleton<EnableBiometric> {
        EnableBiometricImpl(
            directDI = this,
        )
    }
    bindSingleton<DisableYubiKeyUnlock> {
        DisableYubiKeyUnlockImpl(
            directDI = this,
        )
    }
    bindSingleton<EnableYubiKeyUnlock> {
        EnableYubiKeyUnlockImpl(
            directDI = this,
        )
    }
    bindSingleton<GenerateMasterHashUseCase> {
        GenerateMasterHashUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<GenerateMasterKeyUseCase> {
        GenerateMasterKeyUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<GenerateMasterSaltUseCase> {
        GenerateMasterSaltUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<ConfirmAccessByPasswordUseCase> {
        ConfirmAccessByPasswordUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<ConfirmAccessByYubiKeyUseCase> {
        ConfirmAccessByYubiKeyUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<UnlockUseCase> {
        UnlockUseCaseImpl(
            directDI = this,
        )
    }
    bindSingleton<GetTotpCode> {
        GetTotpCodeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetTotpCodeWithOffset> {
        GetTotpCodeWithOffsetImpl(
            directDI = this,
        )
    }
    bindSingleton<GetTwoFa> {
        GetTwoFaImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPasskeys> {
        GetPasskeysImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPassword> {
        GetPasswordImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPinCode> {
        GetPinCodeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetScreenState> {
        GetScreenStateImpl(
            directDI = this,
        )
    }
    bindSingleton<GetConcealFields> {
        GetConcealFieldsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetKeepScreenOn> {
        GetKeepScreenOnImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterTimeout> {
        GetVaultLockAfterTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterTimeoutVariants> {
        GetVaultLockAfterTimeoutVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricTimeout> {
        GetBiometricTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricTimeoutVariants> {
        GetBiometricTimeoutVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutBiometricTimeout> {
        PutBiometricTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<GetBiometricRequireConfirmation> {
        GetBiometricRequireConfirmationImpl(
            directDI = this,
        )
    }
    bindSingleton<PutBiometricRequireConfirmation> {
        PutBiometricRequireConfirmationImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckPwnedPasswords> {
        GetCheckPwnedPasswordsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillCopyTotp> {
        GetAutofillCopyTotpImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillBlockedUrisExposed> {
        GetAutofillBlockedUrisExposedImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillDefaultMatchDetection> {
        GetAutofillDefaultMatchDetectionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillInlineSuggestions> {
        GetAutofillInlineSuggestionsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillManualSelection> {
        GetAutofillManualSelectionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillRespectAutofillOff> {
        GetAutofillRespectAutofillOffImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillPasskeysEnabled> {
        GetAutofillPasskeysEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillPasswordsEnabled> {
        GetAutofillPasswordsEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillSaveRequest> {
        GetAutofillSaveRequestImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAutofillSaveUri> {
        GetAutofillSaveUriImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckPwnedServices> {
        GetCheckPwnedServicesImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckTwoFA> {
        GetCheckTwoFAImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCheckPasskeys> {
        GetCheckPasskeysImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckPwnedPasswords> {
        PutCheckPwnedPasswordsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckPwnedServices> {
        PutCheckPwnedServicesImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckTwoFA> {
        PutCheckTwoFAImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCheckPasskeys> {
        PutCheckPasskeysImpl(
            directDI = this,
        )
    }
    bindSingleton<PutKeepScreenOn> {
        PutKeepScreenOnImpl(
            directDI = this,
        )
    }
    bindSingleton<VaultSessionLocker> {
        VaultSessionLocker(
            directDI = this,
        )
    }
    bindSingleton<PutVaultSession> {
        PutVaultSessionImpl(
            directDI = this,
        )
    }
    bindSingleton<ClearVaultSession> {
        ClearVaultSessionImpl(
            directDI = this,
        )
    }
    bindSingleton<PutNavAnimation> {
        PutNavAnimationImpl(
            directDI = this,
        )
    }
    bindSingleton<PutNavLabel> {
        PutNavLabelImpl(
            directDI = this,
        )
    }
    bindSingleton<PutNavForceHiddenSend> {
        PutNavForceForceHiddenSendImpl(
            directDI = this,
        )
    }
    bindSingleton<PutTheme> {
        PutThemeImpl(
            directDI = this,
        )
    }
    bindSingleton<PutThemeUseAmoledDark> {
        PutThemeUseAmoledDarkImpl(
            directDI = this,
        )
    }
    bindSingleton<PutThemeExpressive> {
        PutThemeExpressiveImpl(
            directDI = this,
        )
    }
    bindSingleton<PutColors> {
        PutColorsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutScreenState> {
        PutScreenStateImpl(
            directDI = this,
        )
    }
    bindSingleton<PutSshAgent> {
        PutSshAgentImpl(this)
    }
    bindSingleton<PutSshAgentApprovalWindow> {
        PutSshAgentApprovalWindowImpl(this)
    }
    bindSingleton<PutSshAgentFilter> {
        PutSshAgentFilterImpl(this)
    }
    bindSingleton<GetSshAgent> {
        GetSshAgentImpl(this)
    }
    bindSingleton<GetSshAgentApprovalWindow> {
        GetSshAgentApprovalWindowImpl(this)
    }
    bindSingleton<GetSshAgentApprovalWindowVariants> {
        GetSshAgentApprovalWindowVariantsImpl(this)
    }
    bindSingleton<GetSshAgentFilter> {
        GetSshAgentFilterImpl(this)
    }
    bindSingleton<GetSshAgentStatus> {
        GetSshAgentStatusImpl(this)
    }
    bindSingleton<GetAllowScreenshots> {
        GetAllowScreenshotsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAllowTwoPanelLayoutInLandscape> {
        GetAllowTwoPanelLayoutInLandscapeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAllowTwoPanelLayoutInPortrait> {
        GetAllowTwoPanelLayoutInPortraitImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterScreenOff> {
        GetVaultLockAfterScreenOffImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultLockAfterReboot> {
        GetVaultLockAfterRebootImpl(
            directDI = this,
        )
    }
    bindSingleton<PasskeyTargetCheck> {
        PasskeyTargetCheckImpl(this)
    }
    bindSingleton<PutAllowScreenshots> {
        PutAllowScreenshotsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetUseExternalBrowser> {
        GetUseExternalBrowserImpl(
            directDI = this,
        )
    }
    bindSingleton<PutUseExternalBrowser> {
        PutUserExternalBrowserImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCloseToTray> {
        GetCloseToTrayImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCloseToTray> {
        PutCloseToTrayImpl(
            directDI = this,
        )
    }
    bindSingleton<GetMinimizeOnCopy> {
        GetMinimizeOnCopyImpl(
            directDI = this,
        )
    }
    bindSingleton<PutMinimizeOnCopy> {
        PutMinimizeOnCopyImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAllowTwoPanelLayoutInLandscape> {
        PutAllowTwoPanelLayoutInLandscapeImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAllowTwoPanelLayoutInPortrait> {
        PutAllowTwoPanelLayoutInPortraitImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultLockAfterReboot> {
        PutVaultLockAfterRebootImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultLockAfterScreenOff> {
        PutVaultLockAfterScreenOffImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillCopyTotp> {
        PutAutofillCopyTotpImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillDefaultMatchDetection> {
        PutAutofillDefaultMatchDetectionImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillInlineSuggestions> {
        PutAutofillInlineSuggestionsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillManualSelection> {
        PutAutofillManualSelectionImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillRespectAutofillOff> {
        PutAutofillRespectAutofillOffImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillPasskeysEnabled> {
        PutAutofillPasskeysEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillPasswordsEnabled> {
        PutAutofillPasswordsEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillSaveRequest> {
        PutAutofillSaveRequestImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAutofillSaveUri> {
        PutAutofillSaveUriImpl(
            directDI = this,
        )
    }
    bindSingleton<GetThemeVariants> {
        GetThemeVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetColorsVariants> {
        GetColorsVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetNavAnimation> {
        GetNavAnimationImpl(
            directDI = this,
        )
    }
    bindSingleton<GetNavLabel> {
        GetNavLabelImpl(
            directDI = this,
        )
    }
    bindSingleton<GetNavForceHiddenSend> {
        GetNavForceHiddenSendImpl(
            directDI = this,
        )
    }
    bindSingleton<GetNavAnimationVariants> {
        GetNavAnimationVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetFont> {
        GetFontImpl(
            directDI = this,
        )
    }
    bindSingleton<GetFontVariants> {
        GetFontVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutFont> {
        PutFontImpl(
            directDI = this,
        )
    }
    bindSingleton<GetLocaleVariants> {
        GetLocaleVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetTheme> {
        GetThemeImpl(
            directDI = this,
        )
    }
    bindSingleton<GetThemeUseAmoledDark> {
        GetThemeUseAmoledDarkImpl(
            directDI = this,
        )
    }
    bindSingleton<GetThemeExpressive> {
        GetThemeExpressiveImpl(
            directDI = this,
        )
    }
    bindSingleton<GetColors> {
        GetColorsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetSubscriptions> {
        GetSubscriptionsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetProducts> {
        GetProductsImpl(
            directDI = this,
        )
    }
    bindSingleton<BlockedUrlCheck> {
        BlockedUrlCheckImpl(this)
    }
    bindSingleton<CipherUrlCheck> {
        CipherUrlCheckImpl(this)
    }
    bindSingleton<CipherUrlDuplicateCheck> {
        CipherUrlDuplicateCheckImpl(this)
    }
    bindSingleton<CipherUrlBroadCheck> {
        CipherUrlBroadCheckImpl(this)
    }
    bindSingleton<GetCanWrite> {
        GetCanWriteImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCachePremium> {
        GetCachePremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<GetCacheHiddenSend> {
        GetCacheHiddenSendImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCachePremium> {
        PutCachePremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<PutCacheHiddenSend> {
        PutCacheHiddenSendImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultLockAfterTimeout> {
        PutVaultLockAfterTimeoutImpl(
            directDI = this,
        )
    }
    bindSingleton<PutConcealFields> {
        PutConcealFieldsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutClipboardAutoRefresh> {
        PutClipboardAutoRefreshImpl(
            directDI = this,
        )
    }
    bindSingleton<PutClipboardAutoClear> {
        PutClipboardAutoClearImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoClear> {
        GetClipboardAutoClearImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoClearVariants> {
        GetClipboardAutoClearVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoRefresh> {
        GetClipboardAutoRefreshImpl(
            directDI = this,
        )
    }
    bindSingleton<GetClipboardAutoRefreshVariants> {
        GetClipboardAutoRefreshVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAllowScreenshotsVariants> {
        GetAllowScreenshotsVariantsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetDebugPremium> {
        GetDebugPremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<GetWriteAccess> {
        GetWriteAccessImpl(
            directDI = this,
        )
    }
    bindSingleton<GetDebugScreenDelay> {
        GetDebugScreenDelayImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAppIcons> {
        GetAppIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetWebsiteIcons> {
        GetWebsiteIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<GetMarkdown> {
        GetMarkdownImpl(
            directDI = this,
        )
    }
    bindSingleton<GetGravatarUrl> {
        GetGravatarUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<GetGravatar> {
        GetGravatarImpl(
            directDI = this,
        )
    }
    bindSingleton<PutGravatar> {
        PutGravatarImpl(
            directDI = this,
        )
    }
    bindSingleton<GetInMemoryLogsEnabled> {
        GetInMemoryLogsEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<GetInMemoryLogs> {
        GetInMemoryLogsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutInMemoryLogsEnabled> {
        PutInMemoryLogsEnabledImpl(
            directDI = this,
        )
    }
    bindSingleton<InMemoryLogRepository> {
        InMemoryLogRepositoryImpl(
            directDI = this,
        )
    }
    bindSingleton<LogRepository> {
        LogRepositoryBridge(
            directDI = this,
        )
    }
    bindSingleton<GetJustDeleteMeByUrl> {
        GetJustDeleteMeByUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<GetJustGetMyDataByUrl> {
        GetJustGetMyDataByUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<ReadWordlistFromFile> {
        ReadWordlistFromFileImpl(
            directDI = this,
        )
    }
    bindSingleton<ReadWordlistFromUrl> {
        ReadWordlistFromUrlImpl(
            directDI = this,
        )
    }
    bindSingleton<PutWriteAccess> {
        PutWriteAccessImpl(
            directDI = this,
        )
    }
    bindSingleton<PutDebugPremium> {
        PutDebugPremiumImpl(
            directDI = this,
        )
    }
    bindSingleton<PutDebugScreenDelay> {
        PutDebugScreenDelayImpl(
            directDI = this,
        )
    }
    bindSingleton<PutAppIcons> {
        PutAppIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutWebsiteIcons> {
        PutWebsiteIconsImpl(
            directDI = this,
        )
    }
    bindSingleton<PutMarkdown> {
        PutMarkdownImpl(
            directDI = this,
        )
    }
    bindSingleton<UpdateVersionLog> {
        UpdateVersionLogImpl(
            directDI = this,
        )
    }
    bindSingleton<ShowNotification> {
        ShowNotificationImpl(
            directDI = this,
        )
    }
    bindSingleton<DismissNotificationsByChannel> {
        DismissNotificationsByChannelImpl(
            directDI = this,
        )
    }
    bindSingleton<PutOnboardingLastVisitInstant> {
        PutOnboardingLastVisitInstantImpl(
            directDI = this,
        )
    }
    bindSingleton<GetOnboardingLastVisitInstant> {
        GetOnboardingLastVisitInstantImpl(
            directDI = this,
        )
    }
    bindSingleton<AppWorker>(tag = AppWorker.Feature.SYNC) {
        AppWorkerIm(
            directDI = this,
        )
    }
    bindSingleton<WatchtowerSyncer>() {
        WatchtowerSyncerImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultSession> {
        GetVaultSessionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVaultPersist> {
        GetVaultPersistImpl(
            directDI = this,
        )
    }
    bindSingleton<PutVaultPersist> {
        PutVaultPersistImpl(
            directDI = this,
        )
    }
    bindSingleton<GetAppVersion> {
        GetAppVersionImpl(
            directDI = this,
        )
    }
    bindSingleton<GetVersionLog> {
        GetVersionLogImpl(
            directDI = this,
        )
    }
    bindProvider<MessageHub> {
        instance<MessageHubImpl>()
    }
    bindProvider<ShowMessage> {
        instance<MessageHubImpl>()
    }
    bindSingleton {
        MessageHubImpl(
            directDI = this,
        )
    }
    bindSingleton<RemoveAttachment> {
        RemoveAttachmentImpl(
            directDI = this,
        )
    }
    bindSingleton<RequestAppReview> {
        RequestAppReviewImpl(
            directDI = this,
        )
    }
    bindSingleton<WindowCoroutineScope> {
        WindowCoroutineScopeImpl(
            directDI = this,
        )
    }
    bindSingleton<PendingUploadCoordinator> {
        PendingUploadCoordinatorImpl(
            directDI = this,
        )
    }
    bindSingleton<GetPassphrase> {
        PasswordGeneratorDiceware(
            directDI = this,
        )
    }
    bindSingleton<ReviewLog> {
        ReviewLogImpl(
            directDI = this,
        )
    }
    import(emailRelayDiModule())
    bindSingleton<CipherPlaceholder.Factory> {
        CipherPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<CommentPlaceholder.Factory> {
        CommentPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<CustomPlaceholder.Factory> {
        CustomPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<DateTimePlaceholder.Factory> {
        DateTimePlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<EnvironmentPlaceholder.Factory> {
        EnvironmentPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<TextReplaceRegexPlaceholder.Factory> {
        TextReplaceRegexPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<TextTransformPlaceholder.Factory> {
        TextTransformPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<UrlPlaceholder.Factory> {
        UrlPlaceholder.Factory(
            directDI = this,
        )
    }
    bindSingleton<DeeplinkService> {
        DeeplinkServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<CipherUnsecureUrlCheck> {
        CipherUnsecureUrlCheckImpl(this)
    }
    bindSingleton<AndroidAppGooglePlayParser> {
        AndroidAppGooglePlayParser(
            directDI = this,
        )
    }
    bindSingleton<AndroidAppFDroidParser> {
        AndroidAppFDroidParser(
            directDI = this,
        )
    }
    bindSingleton<IosAppAppStoreParser> {
        IosAppAppStoreParser(
            directDI = this,
        )
    }
    bindSingleton<JsonExportService> {
        JsonExportServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyPairExport> {
        KeyPairExportImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyPublicExport> {
        KeyPublicExportImpl(
            directDI = this,
        )
    }
    bindSingleton<KeyPrivateExport> {
        KeyPrivateExportImpl(
            directDI = this,
        )
    }
    bindSingleton<WordlistService> {
        WordlistServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<TwoFaService> {
        TwoFaServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<PassKeyService> {
        PassKeyServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<NotificationFingerprintRepository> {
        NotificationFingerprintRepositoryImpl(
            directDI = this,
        )
    }
    bindSingleton<JustDeleteMeService> {
        JustDeleteMeServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<LocalizationContributorsService> {
        LocalizationContributorsServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<OtpMigrationService> {
        OtpMigrationServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<OtpMigrationParser> {
        OtpMigrationParser(
            directDI = this,
        )
    }
    bindSingleton<PrivilegedAppsService> {
        PrivilegedAppsServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<ClipboardEventBus> {
        ClipboardEventBus(
            directDI = this,
        )
    }
    bindSingleton<JustGetMyDataService> {
        JustGetMyDataServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<LicenseService> {
        LicenseServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<TldService> {
        TldServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<LinkInfoPlatformExtractor> {
        LinkInfoPlatformExtractor()
    }
    bindSingleton<LinkInfoExtractorExecute> {
        LinkInfoExtractorExecute()
    }
    bindSingleton<SimilarityService> {
        SimilarityServiceImpl(
            directDI = this,
        )
    }
    bindSingleton<RequestEmailTfa> {
        RequestEmailTfaImpl(this)
    }
    bindSingleton<IdRepository> {
        val store = instance<Files, KeyValueStore>(
            arg = Files.DEVICE_ID,
        )
        IdRepositoryImpl(
            store = store,
        )
    }
    bindSingleton<StateRepository> {
        val store = instance<Files, KeyValueStore>(
            arg = Files.UI_STATE,
        )
        StateRepositoryImpl(
            store = store,
            json = instance(),
        )
    }
    bindSingleton<DeviceIdUseCase> {
        DeviceIdUseCase(
            directDI = this,
        )
    }
    bindSingleton<DeviceEncryptionKeyUseCase> {
        DeviceEncryptionKeyUseCase(
            directDI = this,
        )
    }
    bindSingleton<GetAppVersionCode> {
        GetAppVersionCodeImpl()
    }
    bindSingleton<GetAppVersionName> {
        GetAppVersionNameImpl()
    }
    bindSingleton<GetAppBuildType> {
        GetAppBuildTypeImpl(this)
    }
    bindSingleton<TotpService> {
        TotpServiceImpl(
            directDI = this,
        )
    }
    installFingerprintRepo()
    installKeyRepo()
    installSessionRepo()
    installSessionMetadataRepo()
    installSettingsRepo()
    installExposedRepo()
}

private fun DI.Builder.installFingerprintRepo() {
    bindSingleton {
        FingerprintRepositoryImpl(directDI)
    }
    bindProvider<FingerprintReadRepository> {
        instance<FingerprintRepositoryImpl>()
    }
    bindProvider<FingerprintReadWriteRepository> {
        instance<FingerprintRepositoryImpl>()
    }
}

private fun DI.Builder.installKeyRepo() {
    bindSingleton {
        KeyRepositoryImpl(directDI)
    }
    bindProvider<KeyReadRepository> {
        instance<KeyRepositoryImpl>()
    }
    bindProvider<KeyReadWriteRepository> {
        instance<KeyRepositoryImpl>()
    }
}

private fun DI.Builder.installSessionRepo() {
    bindSingleton {
        SessionRepositoryImpl()
    }
    bindProvider<SessionReadRepository> {
        instance<SessionRepositoryImpl>()
    }
    bindProvider<SessionReadWriteRepository> {
        instance<SessionRepositoryImpl>()
    }
}

private fun DI.Builder.installSessionMetadataRepo() {
    bindSingleton {
        SessionMetadataRepositoryImpl(
            directDI = directDI,
        )
    }
    bindProvider<SessionMetadataReadRepository> {
        instance<SessionMetadataRepositoryImpl>()
    }
    bindProvider<SessionMetadataReadWriteRepository> {
        instance<SessionMetadataRepositoryImpl>()
    }
}

private fun DI.Builder.installSettingsRepo() {
    bindSingleton {
        SettingsRepositoryImpl(
            directDI = directDI,
        )
    }
    bindProvider<SettingsReadRepository> {
        instance<SettingsRepositoryImpl>()
    }
    bindProvider<SettingsReadWriteRepository> {
        instance<SettingsRepositoryImpl>()
    }
}

private fun DI.Builder.installExposedRepo() {
    bindSingleton<UrlBlockRepositoryExposed> {
        UrlBlockRepositoryExposed(this)
    }
}
